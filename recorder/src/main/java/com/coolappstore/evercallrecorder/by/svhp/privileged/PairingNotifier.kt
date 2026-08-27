/* Ever Dialer+ — privileged runtime notification manager.
 *
 * Three-phase notification flow matching the real Shizuku manager:
 *
 *   Phase 1 — user taps "Gérer le pairing" → opens Dev Settings AND shows
 *   an ongoing notification "Recherche du service d'association…".
 *
 *   Phase 2 — mDNS discovers `_adb-tls-pairing._tcp` → notification is
 *   REPLACED with "Service d'association trouvé" + action button
 *   "Entrer le code d'association" that opens PairingActivity
 *   (matching Shizuku's AdbPairDialogFragment: port auto-filled + code input).
 *
 *   Phase 3 — after successful pairing, notification is replaced with
 *   "✔ Appairé — démarrage du moteur…" and auto-dismissed.
 *
 * Like real Shizuku, the notification opens a Dialog/Activity for code entry,
 * NOT a RemoteInput (which doesn't work reliably on all devices).
 */
package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
    const val EXTRA_PAIRING_PORT = "pairing_port"
    const val EXTRA_PAIRING_HOST = "pairing_host"

    private var mdnsWatcher: AdbMdns? = null
    private var isWatching = false

    @Volatile var detectedPort: Int = 0
        private set
    @Volatile var detectedHost: String = "127.0.0.1"
        private set

    // ── Public API ──────────────────────────────────────────────────────

    fun showWaitingNotification(context: Context) {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        if (!ensureNotificationPermission(appCtx)) {
            startMdnsWatcher(appCtx)
            return
        }

        ensureChannel(appCtx)

        val devSettingsIntent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            appCtx, 0, devSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Recherche du service d'association…")
            .setContentText("Active le débogage sans fil puis tape « Associer avec un code »")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Active le débogage sans fil dans les Options Développeur, " +
                    "puis tape « Associer avec un code ». " +
                    "La notification se mettra à jour quand le service sera disponible."
            ))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        post(appCtx, n, "Phase 1: searching")
        startMdnsWatcher(appCtx)
    }

    fun startWatching(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appCtx = context.applicationContext
        ensureChannel(appCtx)
        startMdnsWatcher(appCtx)
    }

    fun stopWatching(context: Context) {
        runCatching { mdnsWatcher?.stop() }
        mdnsWatcher = null
        isWatching = false
        cancel(context.applicationContext)
    }

    fun onPairingSucceeded(context: Context) {
        Log.i(TAG, "Pairing succeeded — showing Phase 3")
        showPhase3(context.applicationContext)
        stopWatching(context)
    }

    fun onPairingFailed(context: Context, error: String) {
        Log.w(TAG, "Pairing failed: $error")
        if (detectedPort > 0) showPhase2(context.applicationContext)
    }

    // ── Phase 2: mDNS watcher ───────────────────────────────────────────

    private fun startMdnsWatcher(appCtx: Context) {
        if (isWatching) return
        isWatching = true
        Log.d(TAG, "Starting mDNS watcher for _adb-tls-pairing._tcp")

        try {
            mdnsWatcher = AdbMdns(appCtx, AdbMdns.TLS_PAIRING) { (host, port) ->
                Log.d(TAG, "mDNS resolved: host=$host port=$port")
                if (port > 0) {
                    detectedHost = host.ifBlank { "127.0.0.1" }
                    detectedPort = port
                    showPhase2(appCtx)
                }
            }.also { runCatching { it.start() } }
        } catch (e: Throwable) {
            Log.e(TAG, "mDNS watcher failed: ${e.message}", e)
            isWatching = false
        }
    }

    // ── Phase 2: notification opens PairingActivity ──────────────────────

    private fun showPhase2(appCtx: Context) {
        if (!ensureNotificationPermission(appCtx)) return
        ensureChannel(appCtx)

        // Open PairingActivity with auto-filled port (like Shizuku's AdbPairDialogFragment)
        val pairingIntent = Intent(appCtx, PairingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_PAIRING_PORT, detectedPort)
            putExtra(EXTRA_PAIRING_HOST, detectedHost)
        }
        val pairingPending = PendingIntent.getActivity(
            appCtx, 1, pairingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Service d'association trouvé")
            .setContentText("Port : $detectedPort — Appuie pour entrer le code")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Service de pairing détecté sur le port $detectedPort. " +
                    "Appuie ci-dessous pour ouvrir l'écran de pairing et entrer le code à 6 chiffres."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pairingPending)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Entrer le code d'association",
                pairingPending
            )
            .build()

        post(appCtx, n, "Phase 2: opens PairingActivity")
    }

    // ── Phase 3: success ────────────────────────────────────────────────

    private fun showPhase3(appCtx: Context) {
        if (!ensureNotificationPermission(appCtx)) return
        ensureChannel(appCtx)

        val n = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✔ Appairé et actif")
            .setContentText("Le moteur privilégié démarre…")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Appairage réussi ! Le moteur privilégié démarre automatiquement. " +
                    "L'enregistrement des appels est prêt."
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(10_000L)
            .build()

        post(appCtx, n, "Phase 3: paired")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun ensureNotificationPermission(appCtx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                appCtx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun post(appCtx: Context, n: android.app.Notification, label: String) {
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, n)
            Log.d(TAG, "Posted: $label")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post: ${e.message}")
        }
    }

    private fun cancel(appCtx: Context) {
        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    private fun ensureChannel(appCtx: Context) {
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
            }
        )
    }
}
