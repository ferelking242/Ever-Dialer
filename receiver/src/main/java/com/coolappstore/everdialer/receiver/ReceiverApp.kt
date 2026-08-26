/*
 * Ever Call Recording (phone B) — minimal companion that ONLY receives what
 * the main Ever Dialer+ app (phone A) pushes over the local network.
 */
package com.coolappstore.everdialer.receiver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.coolappstore.everdialer.by.svhp.sync.SyncManager

class ReceiverApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // Boots the same sync engine embedded in the dialer. On this device it
        // stays a RECEIVER: it serves one paired sender (phone A) and stores
        // everything under filesDir/EverSync.
        SyncManager.init(this)
    }

    companion object {
        const val CHANNEL_SYNC = "ever_sync"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SYNC,
                    "Réception des appels",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Maintient la réception P2P depuis le téléphone A"
                    setShowBadge(false)
                }
            )
        }
    }
}
