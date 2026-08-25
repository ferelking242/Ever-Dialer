/*
 * Ever Dialer+ — privileged runtime (Phase 2).
 * Discrete foreground service keeping the embedded Shizuku server alive:
 * pings the binder periodically and relaunches it through the stored
 * wireless-debugging connection when it dies (replaces thedjchi's external
 * "Watchdog" fork feature).
 */
package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.coolappstore.evercallrecorder.by.svhp.integrations.shizuku.ShizukuConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EmbeddedShizukuService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { watchdogLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watchdogLoop() {
        var backoffMs = POLL_INTERVAL_MS
        while (scope.isActive) {
            delay(backoffMs)
            val context = applicationContext
            if (!PrivilegedRuntime.isWatchdogEnabled(context)) continue
            if (ShizukuConnectionManager.isAvailable()) {
                backoffMs = POLL_INTERVAL_MS
                continue
            }
            Log.w(TAG, "Binder mort — relance automatique du serveur embarqué…")
            val ok = PrivilegedRuntime.watchdogRestart(context)
            backoffMs = if (ok) POLL_INTERVAL_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Privilèges système",
                    NotificationManager.IMPORTANCE_MIN
                ).apply { description = "Maintient actif des privilèges d'enregistrement" }
            )
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PairingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Ever Dialer — privilèges actifs")
                .setContentText("Enregistrement des appels opérationnel")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()

        // minSdk = 30 → dataSync FGS type always available.
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "EmbeddedShizukuSvc"
        private const val CHANNEL_ID = "privileged_watchdog"
        private const val NOTIFICATION_ID = 0x5E17
        private const val POLL_INTERVAL_MS = 15_000L
        private const val MAX_BACKOFF_MS = 10 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EmbeddedShizukuService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmbeddedShizukuService::class.java))
        }
    }
}
