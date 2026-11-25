package com.torrentx.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps downloads running while the app is in the background. */
class TorrentService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1
        const val ACTION_EXIT = "com.torrentx.app.EXIT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TorrentService::class.java))
        }

        fun exit(context: Context) {
            context.startService(Intent(context, TorrentService::class.java).setAction(ACTION_EXIT))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Starting…"), type)

        TorrentEngine.start(this)

        lifecycleScope.launch {
            combine(TorrentEngine.torrents, TorrentEngine.stats) { list, stats ->
                val active = list.count { !it.paused && !it.finished }
                "$active active • ↓ ${formatSpeed(stats.down)} ↑ ${formatSpeed(stats.up)}"
            }.collect { text -> nm.notify(NOTIF_ID, buildNotification(text)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_EXIT) {
            TorrentEngine.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                0, "Exit",
                PendingIntent.getService(
                    this, 1, Intent(this, TorrentService::class.java).setAction(ACTION_EXIT),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
}
