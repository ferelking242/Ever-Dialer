/* Ever Dialer+ — privileged runtime (Phase 2).
 * Monitors mDNS for the Android wireless-debugging pairing service and shows
 * a persistent notification prompting the user to pair — mirroring the real
 * Shizuku manager behaviour.
 */
package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import moe.shizuku.manager.adb.AdbMdns

object PairingNotifier {

    private const val TAG = "PairingNotifier"
    private const val CHANNEL_ID = "privileged_pairing"
    private const val NOTIFICATION_ID = 0x5E1A
    private const val CHANNEL_NAME = "Privilèges système"
    private const val CHANNEL_DESC = "Notifications pour le pairing du moteur privilégié"

    private var mdnsWatcher: AdbMdns? = null

    /**
     * Start monitoring for wireless-debugging pairing availability.
     * When a `_adb-tls-pairing._tcp` service is found and the device is NOT
     * yet paired, a persistent notification appears. Tapping it opens the
     * [PairingActivity] so the user can enter the 6-digit code.
     *
     * Safe to call multiple times — duplicates are avoided.
     */
    fun startWatching(context: Context) {
        if (mdnsWatcher != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val appContext = context.applicationContext

        // Already paired → no need to notify.
        if (PrivilegedRuntime.isPaired(appContext)) {
            cancelNotification(appContext)
            return
        }

        ensureChannel(appContext)

        Log.d(TAG, "Starting mDNS watcher for wireless-debugging pairing")

        mdnsWatcher = AdbMdns(appContext, AdbMdns.TLS_PAIRING) { (host, port) ->
            if (port > 0 && !PrivilegedRuntime.isPaired(appContext)) {
                Log.i(TAG, "Pairing service detected on $host:$port — showing notification")
                showPairingNotification(appContext)
            }
        }.also { it.start() }
    }

    /** Stop monitoring and dismiss any visible notification. */
    fun stopWatching(context: Context) {
        runCatching { mdnsWatcher?.stop() }
        mdnsWatcher = null
        cancelNotification(context.applicationContext)
    }

    /** Call after successful pairing to dismiss the notification. */
    fun onPairingSucceeded(context: Context) {
        cancelNotification(context.applicationContext)
        stopWatching(context)
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun showPairingNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android 13+ requires POST_NOTIFICATIONS — silently skip if not granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) return
        }

        val intent = Intent(context, PairingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Appairage privilégié disponible")
            .setContentText("Appuie ici pour entrer le code de débogage sans fil")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Débogage sans fil détecté. Ouvre l'app pour saisir le code à 6 chiffres et activer l'enregistrement.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setOngoing(false)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
        }
        nm.createNotificationChannel(channel)
    }
}
