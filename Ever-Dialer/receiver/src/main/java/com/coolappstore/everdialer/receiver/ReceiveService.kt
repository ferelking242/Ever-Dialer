/*
 * Ever Call Recording (phone B) — keeps the P2P listener alive in the
 * background so phone A can push recordings + call logs any time both
 * devices are online. Restarted automatically after boot.
 */
package com.coolappstore.everdialer.receiver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.coolappstore.everdialer.by.svhp.sync.SyncServer
import com.coolappstore.everdialer.by.svhp.sync.SyncStore

class ReceiveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReceiverApp.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (SyncStore.isEnabled(this)) SyncServer.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        SyncServer.stop()
        super.onDestroy()
    }

    private fun goForeground() {
        val notification = NotificationCompat.Builder(this, ReceiverApp.CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Ever Call Recording")
            .setContentText("En attente du téléphone A…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context.applicationContext, ReceiveService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context.applicationContext, ReceiveService::class.java))
        }
    }
}
