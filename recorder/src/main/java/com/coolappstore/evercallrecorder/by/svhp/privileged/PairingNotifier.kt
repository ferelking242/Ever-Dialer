/* Ever Dialer+ — privileged runtime (Phase 2).
 * Two-phase notification mirroring the real Shizuku manager:
 *
 *   Phase 1 — user taps "Gérer le pairing" → opens Dev Settings AND shows
 *   an immediate notification "En attente du débogage sans fil…".
 *
 *   Phase 2 — mDNS discovers `_adb-tls-pairing._tcp` → notification is
 *   updated to "Débogage sans fil détecté" and opens PairingActivity.
 *
 *   Phase 3 — after successful pairing, notification is dismissed.
 */
package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import moe.shizuku.manager.adb.AdbMdns

object PairingNotifier {

    private const val TAG = "PairingNotifier"
    private const val CHANNEL_ID = "privileged_pairing"
    private const val NOTIFICATION_ID = 0x5E1A
    private const val CHANNEL_NAME = "Privilèges système"
    private const val CHANNEL_DESC = "Notifications pour le pairing du moteur privilégié"

    private var mdnsWatcher: AdbMdns? = null
    private var isWatching = false

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Phase 1 — called when user taps "Gérer le pairing" in Settings.
     * Shows an IMMEDIATE notification "En attente du débogage sans fil…"
     * AND starts the mDNS watcher for Phase 2.
     */
    fun showWaitingNotification(context: Context) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (PrivilegedRuntime.isPaired(appContext)) {
            cancelNotification(appContext)
            return
        }

        ensureChannel(appContext)

        // Show immediate "waiting" notification
        showNotification(
            appContext,
            title = "En attente du débogage sans fil…",
            text = "Active le débogage sans fil puis tape « Associer avec un code »",
            bigText = "Ouvre les Options Développeur → Débogage sans fil → Associer avec un code. " +
                "Quand le code à 6 chiffres apparaîtra, cette notification s'activera pour te laisser entrer le code.",
            priority = NotificationCompat.PRIORITY_DEFAULT,
            ongoing = true
        )

        // Start mDNS watcher for Phase 2
        startMdnsWatcher(appContext)
    }

    /**
     * Start monitoring for wireless-debugging pairing availability via mDNS.
     * When a `_adb-tls-pairing._tcp` service is found, the notification is
     * updated (Phase 2). Safe to call multiple times.
     */
    fun startWatching(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appContext = context.applicationContext
        if (PrivilegedRuntime.isPaired(appContext)) {
            cancelNotification(appContext)
            return
        }
        ensureChannel(appContext)
        startMdnsWatcher(appContext)
    }

    /** Stop monitoring and dismiss any visible notification. */
    fun stopWatching(context: Context) {
        runCatching { mdnsWatcher?.stop() }
        mdnsWatcher = null
        isWatching = false
        cancelNotification(context.applicationContext)
    }

    /** Call after successful pairing to dismiss the notification. */
    fun onPairingSucceeded(context: Context) {
        cancelNotification(context.applicationContext)
        stopWatching(context)
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun startMdnsWatcher(appContext: Context) {
        if (isWatching) return
        isWatching = true

        Log.d(TAG, "Starting mDNS watcher for _adb-tls-pairing._tcp")

        mdnsWatcher = AdbMdns(appContext, AdbMdns.TLS_PAIRING) { (host, port) ->
            if (port > 0 && !PrivilegedRuntime.isPaired(appContext)) {
                Log.i(TAG, "Pairing service detected on $host:$port — updating notification")
                // Phase 2: update notification to "pairing detected"
                showNotification(
                    appContext,
                    title = "Débogage sans fil détecté !",
                    text = "Appuie ici pour entrer le code à 6 chiffres",
                    bigText = "Débogage sans fil disponible sur le port $port. " +
                        "Appuie sur cette notification pour ouvrir l'écran de pairing et saisir le code à 6 chiffres.",
                    priority = NotificationCompat.PRIORITY_HIGH,
                    ongoing = false,
                    openPairingScreen = true
                )
            }
        }.also { runCatching { it.start() } }
    }

    // ── Notification helpers ────────────────────────────────────────────

    private fun showNotification(
        context: Context,
        title: String,
        text: String,
        bigText: String,
        priority: Int,
        ongoing: Boolean,
        openPairingScreen: Boolean = false
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android 13+ requires runtime POST_NOTIFICATIONS permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted — cannot show pairing notification")
                return
            }
        }

        ensureChannel(context)

        val intent = if (openPairingScreen) {
            Intent(context, PairingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        } else {
            // "Waiting" notification: open Dev Settings so user can enable wireless debugging
            Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val pending = PendingIntent.getActivity(
            context, if (openPairingScreen) 1 else 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(priority)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setContentIntent(pending)
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
