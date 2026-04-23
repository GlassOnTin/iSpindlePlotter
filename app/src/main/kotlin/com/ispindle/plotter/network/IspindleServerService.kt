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
        startForegroundInternal("Starting…")
        server.start()
        observerJob = scope.launch {
            server.state.collectLatest { updateNotification(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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

        fun start(ctx: Context) {
            val intent = Intent(ctx, IspindleServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, IspindleServerService::class.java))
        }
    }
}
