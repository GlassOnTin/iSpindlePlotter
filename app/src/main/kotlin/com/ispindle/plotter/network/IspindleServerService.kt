package com.ispindle.plotter.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ispindle.plotter.IspindleApp
import com.ispindle.plotter.MainActivity
import com.ispindle.plotter.R
import com.ispindle.plotter.data.ProxyPrefs
import com.ispindle.plotter.data.Repository
import com.ispindle.plotter.data.ServerPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IspindleServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var pollJob: Job? = null
    private lateinit var server: IspindleHttpServer
    private lateinit var repository: Repository
    private var ready = false
    private var mode: Mode? = null
    // CONFLATED so repeated foreground pokes collapse into one pending wake.
    private val pullSignal = Channel<Unit>(Channel.CONFLATED)

    private enum class Mode { Listener, Proxy, Off }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as IspindleApp
        server = app.httpServer
        repository = app.repository
        ensureChannel()
        // Android 14+ raises ForegroundServiceStartNotAllowedException if the
        // app isn't visible when the service starts, or if the dataSync FGS
        // time budget is exhausted. Either case means we can't legally raise
        // the notification — log and stop quietly rather than crash. The
        // user-facing Start/Stop buttons run while the activity is in the
        // foreground, so this is only the background-restart edge case.
        try {
            startForegroundInternal(getString(R.string.server_notif_starting))
            ready = true
            applyMode()
        } catch (t: Throwable) {
            android.util.Log.e(
                "IspindleServerService",
                "Foreground start denied — stopping service",
                t
            )
            stopSelf()
        }
    }

    // START_NOT_STICKY: don't have the OS resurrect us in the background.
    // The user's explicit Start tap is the only path that should bring the
    // server up, and that always happens while the activity is visible.
    // Re-evaluate the mode on every (re)start so toggling the Proxy URL
    // switches between listener and poll without a manual stop/start.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ready) applyMode()
        // Foreground poke: sync now rather than waiting out the interval.
        if (intent?.action == ACTION_PULL_NOW) pullSignal.trySend(Unit)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        pollJob?.cancel()
        server.stop()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Picks listener vs proxy-poll from [ProxyPrefs] and switches if the
     * desired mode differs from what's running. Idempotent — a repeat call
     * with the same mode is a no-op.
     */
    private fun applyMode() {
        // Proxy takes priority over the listener; Off means nothing is wanted.
        val desired = when {
            ProxyPrefs.enabled(this) -> Mode.Proxy
            ServerPrefs.isEnabled(this) -> Mode.Listener
            else -> Mode.Off
        }
        if (mode == desired) return
        // Tear down the previous mode.
        pollJob?.cancel(); pollJob = null
        observerJob?.cancel(); observerJob = null
        server.stop()
        mode = desired
        when (desired) {
            Mode.Proxy -> {
                proxyNotif(getString(R.string.server_notif_proxy_starting))
                pollJob = scope.launch { pollLoop() }
            }
            Mode.Listener -> {
                server.start()
                observerJob = scope.launch {
                    server.state.collectLatest { updateNotification(it) }
                }
            }
            Mode.Off -> {
                // Nothing wanted: drop the foreground notification and stop.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Resolves the proxy address (manual override, else mDNS discovery), pulls
     * readings buffered since our cursor, and replays each through the normal
     * [Repository.ingest] path stamped with the proxy's receive time (not now).
     * The cursor is persisted per reading so a crash re-fetches at most one
     * already-ingested row.
     */
    private suspend fun pollLoop() {
        val client = ProxyClient()
        val nsd = getSystemService(NsdManager::class.java)
        while (true) {
            val manual = ProxyPrefs.manualUrl(this)
            // manual override > cached discovery > fresh mDNS discovery.
            val baseUrl = manual
                ?: ProxyPrefs.discoveredUrl(this)
                ?: nsd?.let { ProxyDiscovery.discover(it, DISCOVERY_TIMEOUT_MS) }
                    ?.also { ProxyPrefs.setDiscoveredUrl(this, it) }
            if (baseUrl == null) {
                proxyNotif(getString(R.string.server_notif_proxy_searching))
                waitForNextPoll(DISCOVERY_RETRY_MS)
                continue
            }
            try {
                var cursor = ProxyPrefs.cursor(this)
                val resp = client.pullSince(baseUrl, cursor)
                for (rec in resp.readings) {
                    repository.ingest(rec.payload, rec.ts, rec.ip)
                    cursor = maxOf(cursor, rec.id)
                    ProxyPrefs.setCursor(this, cursor)
                }
                proxyNotif(getString(R.string.server_notif_proxy_synced, hhmm(), resp.readings.size))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Proxy poll failed: ${t.message}")
                // Drop a discovered URL on failure so the next loop re-discovers
                // (the proxy may have moved); a manual URL is left intact.
                if (manual == null) ProxyPrefs.setDiscoveredUrl(this, null)
                proxyNotif(getString(R.string.server_notif_proxy_error, hhmm()))
            }
            waitForNextPoll(POLL_INTERVAL_MS)
        }
    }

    /** Sleeps up to [timeoutMs], returning early if a foreground poke arrives. */
    private suspend fun waitForNextPoll(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { pullSignal.receive() }
    }

    private fun proxyNotif(text: String) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun hhmm() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.server_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun startForegroundInternal(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // connectedDevice instead of dataSync because Android 15 caps
            // dataSync at 6 hours per 24-hour window, which would silently
            // kill the listener mid-fermentation. connectedDevice has no
            // such cap; we satisfy its prerequisite via CHANGE_NETWORK_STATE
            // / CHANGE_WIFI_STATE permissions already in the manifest.
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(state: IspindleHttpServer.State) {
        val text = when (state) {
            is IspindleHttpServer.State.Running -> {
                val ip = NetworkUtils.preferredIpv4() ?: "?.?.?.?"
                getString(R.string.server_notif_listening, ip, state.port)
            }
            IspindleHttpServer.State.Stopped -> getString(R.string.server_notif_stopped)
            is IspindleHttpServer.State.Error -> getString(R.string.server_notif_error, state.message)
        }
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ispindle_server"
        private const val NOTIF_ID = 1001
        private const val TAG = "IspindleServerService"
        private const val POLL_INTERVAL_MS = 2 * 60 * 1000L
        private const val DISCOVERY_TIMEOUT_MS = 8 * 1000L
        private const val DISCOVERY_RETRY_MS = 30 * 1000L
        private const val ACTION_PULL_NOW = "com.ispindle.plotter.PULL_NOW"

        /**
         * Ask the proxy poll loop to sync immediately (e.g. the app just came
         * to the foreground), instead of waiting out the interval. No-op unless
         * proxy mode is on.
         */
        fun pullNow(ctx: Context) {
            if (!ProxyPrefs.enabled(ctx)) return
            val intent = Intent(ctx, IspindleServerService::class.java).setAction(ACTION_PULL_NOW)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(intent)
                else
                    ctx.startService(intent)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "pullNow start refused: ${t.message}")
            }
        }

        /** Home "Start": the user wants the listener. applyMode resolves the rest. */
        fun start(ctx: Context) {
            ServerPrefs.setEnabled(ctx, true)
            launch(ctx)
        }

        /**
         * Home "Stop": the user no longer wants the listener. Don't kill the
         * service outright — proxy mode may still want it; applyMode switches to
         * proxy or stops the service entirely if nothing is wanted.
         */
        fun stop(ctx: Context) {
            ServerPrefs.setEnabled(ctx, false)
            launch(ctx)
        }

        /**
         * Re-evaluate after a settings change (e.g. the Setup proxy toggle).
         * applyMode picks listener / proxy / off and stops the service if nothing
         * is wanted.
         */
        fun refresh(ctx: Context) = launch(ctx)

        /**
         * App-launch / boot-time auto-start. Runs the service when EITHER the
         * listener or proxy mode is wanted, so proxy polling no longer depends on
         * the listener's Start/Stop switch.
         */
        fun startIfEnabled(ctx: Context) {
            if (ServerPrefs.isEnabled(ctx) || ProxyPrefs.enabled(ctx)) launch(ctx)
        }

        private fun launch(ctx: Context) {
            val intent = Intent(ctx, IspindleServerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(intent)
                else
                    ctx.startService(intent)
            } catch (t: Throwable) {
                // Background-restricted contexts (Doze, etc.) may refuse the
                // FGS start. The boot exemption usually covers us, but if the
                // OS still denies, log and move on rather than crashing.
                android.util.Log.w(TAG, "Service start refused: ${t.message}")
            }
        }
    }
}
