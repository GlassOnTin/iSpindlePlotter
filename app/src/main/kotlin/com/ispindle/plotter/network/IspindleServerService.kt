package com.ispindle.plotter.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ispindle.plotter.IspindleApp
import com.ispindle.plotter.MainActivity
import com.ispindle.plotter.R
import com.ispindle.plotter.data.ServerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class IspindleServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private lateinit var server: IspindleHttpServer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as IspindleApp
        server = app.httpServer
        ensureChannel()
        // Android 14+ raises ForegroundServiceStartNotAllowedException if the
        // app isn't visible when the service starts, or if the dataSync FGS
        // time budget is exhausted. Either case means we can't legally raise
        // the notification — log and stop quietly rather than crash. The
        // user-facing Start/Stop buttons run while the activity is in the
        // foreground, so this is only the background-restart edge case.
        try {
            startForegroundInternal("Starting…")
            server.start()
            observerJob = scope.launch {
                server.state.collectLatest { updateNotification(it) }
            }
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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        observerJob?.cancel()
        server.stop()
        scope.cancel()
        super.onDestroy()
    }

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
                "Listening on http://$ip:${state.port}"
            }
            IspindleHttpServer.State.Stopped -> "Stopped"
            is IspindleHttpServer.State.Error -> "Error: ${state.message}"
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

        /** User-driven start. Marks the server as desired and brings it up. */
        fun start(ctx: Context) {
            ServerPrefs.setEnabled(ctx, true)
            launch(ctx)
        }

        /** User-driven stop. Marks the server as disabled and tears it down. */
        fun stop(ctx: Context) {
            ServerPrefs.setEnabled(ctx, false)
            ctx.stopService(Intent(ctx, IspindleServerService::class.java))
        }

        /**
         * App-launch / boot-time auto-start. Honours the persisted preference
         * so an explicit Stop survives reboots and re-opens.
         */
        fun startIfEnabled(ctx: Context) {
            if (ServerPrefs.isEnabled(ctx)) launch(ctx)
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
