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
 *
 * Notifications are supplementary — the PairingActivity works without them.
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
     *
     * This method does NOT check isPaired() — it always tries to show
     * the notification. The PairingActivity handles the paired state.
     */
    fun showWaitingNotification(context: Context) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "Skipping: Android < 11 (R)")
            return
        }

        // Check POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    appContext, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted at runtime — notification won't show")
                // Still start mDNS watcher — PairingActivity will handle it
                startMdnsWatcher(appContext)
                return
            }
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

        Log.d(TAG, "Phase 1 notification shown — starting mDNS watcher")

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
        Log.d(TAG, "Pairing succeeded — dismissing notification")
        cancelNotification(context.applicationContext)
        stopWatching(context)
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun startMdnsWatcher(appContext: Context) {
        if (isWatching) {
            Log.d(TAG, "mDNS watcher already running")
            return
        }
        isWatching = true

        Log.d(TAG, "Starting mDNS watcher for _adb-tls-pairing._tcp")

        try {
            mdnsWatcher = AdbMdns(appContext, AdbMdns.TLS_PAIRING) { (host, port) ->
                Log.d(TAG, "mDNS service resolved: host=$host port=$port")
                if (port > 0) {
                    // Phase 2: update notification to "pairing detected"
                    Log.i(TAG, "Pairing service detected on $host:$port — updating notification")
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
            }.also {
                val started = runCatching { it.start() }.isSuccess
                Log.d(TAG, "mDNS watcher start result: $started")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start mDNS watcher: ${e.message}", e)
            isWatching = false
        }
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
                Log.w(TAG, "POST_NOTIFICATIONS not granted — cannot show notification: $title")
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

        try {
            nm.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Notification posted: $title (id=$NOTIFICATION_ID)")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException posting notification: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification: ${e.message}")
        }
    }

    private fun cancelNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            Log.d(TAG, "Notification cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification: ${e.message}")
        }
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
        Log.d(TAG, "Notification channel created: $CHANNEL_ID")
    }
}
