/* Ever Dialer+ — privileged runtime notification manager.
 *
 * Three-phase notification flow matching the real Shizuku manager:
 *
 *   Phase 1 — user taps "Gérer le pairing" → opens Dev Settings AND shows
 *   an ongoing notification "Recherche du service d'association…".
 *
 *   Phase 2 — mDNS discovers `_adb-tls-pairing._tcp` → notification is
 *   REPLACED with "Service d'association trouvé" + RemoteInput action
 *   "Code d'association" where the user types the 6-digit code inline
 *   in the notification and taps "Envoyer". NO Activity opens.
 *
 *   Phase 3 — after successful pairing, notification is replaced with
 *   "✔ Appairé — démarrage du moteur…" and auto-dismissed.
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
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.coolappstore.evercallrecorder.by.svhp.utils.NtfyReporter
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
        detectedPort = 0
        detectedHost = "127.0.0.1"
        NtfyReporter.publish("pairing", "waiting for wireless debugging pairing service")

        ensureChannel(appCtx)

        if (!ensureNotificationPermission(appCtx)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — starting mDNS watcher only")
            startMdnsWatcher(appCtx)
            return
        }

        // Open Dev Settings on tap (Phase 1)
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

    fun showWirelessDebuggingRequiredNotification(context: Context) {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        startMdnsWatcher(appCtx)
        if (!ensureNotificationPermission(appCtx)) return
        ensureChannel(appCtx)

        val devSettingsIntent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            appCtx, 1, devSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Active le débogage sans fil")
            .setContentText("Le moteur démarrera ensuite depuis l’accueil")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Le débogage sans fil est désactivé. Ouvre les Options pour les développeurs, " +
                    "active « Débogage sans fil », puis reviens dans Ever Dialer et appuie sur le badge."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(
                android.R.drawable.ic_menu_manage,
                "Ouvrir les Options développeur",
                pi
            )
            .build()
        post(appCtx, n, "Wireless debugging required")
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

    fun onPairingStarted(context: Context) {
        Log.i(TAG, "Pairing started — showing progress notification")
        NtfyReporter.publish("pairing", "remote code submitted; starting handshake")
        showProgressNotification(context.applicationContext)
    }

    fun onPairingSucceeded(context: Context) {
        Log.i(TAG, "Pairing succeeded — showing Phase 3")
        NtfyReporter.publish("pairing", "pairing completed")
        stopWatching(context)
        showPhase3(context.applicationContext)
    }

    fun onPairingFailed(context: Context, error: String) {
        Log.w(TAG, "Pairing failed: $error")
        NtfyReporter.publish("pairing", "failed: $error", "high")
        showFailedNotification(context.applicationContext, error)
        if (detectedPort > 0) showPhase2(context.applicationContext, error)
    }

    fun showStartingNotification(context: Context) {
        val appCtx = context.applicationContext
        if (!ensureNotificationPermission(appCtx)) return
        ensureChannel(appCtx)
        val n = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Démarrage du moteur intégré…")
            .setContentText("Ever Dialer prépare l'enregistrement des appels")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        post(appCtx, n, "Runtime: starting")
    }

    fun onRuntimeStarted(context: Context) {
        Log.i(TAG, "Embedded runtime started")
        NtfyReporter.publish("runtime", "embedded runtime started from foreground service")
        showPhase3(context.applicationContext)
    }

    fun onRuntimeFailed(context: Context, error: Throwable) {
        val detail = "${error.javaClass.simpleName}: ${error.message ?: "unknown"}"
        Log.e(TAG, "Embedded runtime failed: $detail", error)
        NtfyReporter.publish("runtime", "foreground startup failed: $detail", "high")
        val appCtx = context.applicationContext
        if (!ensureNotificationPermission(appCtx)) return
        ensureChannel(appCtx)
        val n = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Échec du moteur intégré")
            .setContentText("Appuie sur le badge pour réessayer")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        post(appCtx, n, "Runtime: failed")
    }

    // ── Phase 2: mDNS watcher ───────────────────────────────────────────

    private fun startMdnsWatcher(appCtx: Context) {
        if (isWatching) return
        isWatching = true
        Log.d(TAG, "Starting mDNS watcher for _adb-tls-pairing._tcp")

        try {
            val watcher = AdbMdns(appCtx, AdbMdns.TLS_PAIRING) { (host, port) ->
                Log.d(TAG, "mDNS resolved: host=$host port=$port")
                if (port > 0) {
                    detectedHost = host.ifBlank { "127.0.0.1" }
                    detectedPort = port
                    NtfyReporter.publish("pairing", "service discovered host=$detectedHost port=$detectedPort")
                    showPhase2(appCtx)
                }
            }
            mdnsWatcher = watcher
            runCatching { watcher.start() }.onFailure {
                mdnsWatcher = null
                isWatching = false
                Log.e(TAG, "mDNS watcher failed to start: ${it.message}", it)
                NtfyReporter.publish(
                    "pairing",
                    "mDNS start error ${it.javaClass.simpleName}: ${it.message ?: "unknown"}",
                    "high"
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "mDNS watcher failed: ${e.message}", e)
            NtfyReporter.publish(
                "pairing",
                "mDNS watcher error ${e.javaClass.simpleName}: ${e.message ?: "unknown"}",
                "high"
            )
            isWatching = false
        }
    }

    // ── Phase 2: notification with RemoteInput (inline code entry) ──────

    private fun showPhase2(appCtx: Context, error: String? = null) {
        if (!ensureNotificationPermission(appCtx)) return
        ensureChannel(appCtx)

        // BroadcastReceiver PendingIntent — MUST be MUTABLE for RemoteInput
        val replyIntent = Intent(appCtx, PairingReplyReceiver::class.java).apply {
            putExtra(EXTRA_PAIRING_PORT, detectedPort)
            putExtra(EXTRA_PAIRING_HOST, detectedHost)
        }
        val replyPending = PendingIntent.getBroadcast(
            appCtx, 2, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // RemoteInput: inline text field in the notification
        val remoteInput = RemoteInput.Builder(PairingReplyReceiver.KEY_PAIRING_CODE)
            .setLabel("Code d'association")
            .build()

        // Action with RemoteInput attached
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Entrer le code d'association",
            replyPending
        ).addRemoteInput(remoteInput).build()

        val n = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (error == null) "Service d'association trouvé" else "Réessaie le pairing")
            .setContentText(
                if (error == null) "Port : $detectedPort — Entres le code à 6 chiffres"
                else "Échec précédent : ${error.take(90)}"
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                (if (error == null) "Service de pairing détecté sur le port $detectedPort. "
                else "Le dernier pairing a échoué : $error. ") +
                    "Tape « Code d'association » puis entre le code à 6 chiffres et envoie."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(false)
            .addAction(action)
            .apply {
                if (error != null) {
                    addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Recommencer",
                        resetPairingPendingIntent(appCtx)
                    )
                }
            }
            .build()

        post(appCtx, n, "Phase 2: RemoteInput code entry")
    }

    // ── Phase 3: success ────────────────────────────────────────────────

    private fun showPhase3(appCtx: Context) {
        if (!ensureNotificationPermission(appCtx)) return
        ensureChannel(appCtx)

        val n = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✔ Moteur démarré")
            .setContentText("Retourne à l’accueil pour vérifier le badge")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Appairage réussi ! Le moteur privilégié est démarré. " +
                    "Retourne à l’accueil : si le badge affiche « Autoriser », appuie dessus " +
                    "pour donner à Ever Dialer l’accès au moteur."
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(10_000L)
            .build()

        post(appCtx, n, "Phase 3: paired")
    }

    // ── Progress notification (shown while SPAKE2+ handshake runs) ────

    private fun showProgressNotification(appCtx: Context) {
        if (!ensureNotificationPermission(appCtx)) return
        ensureChannel(appCtx)

        val n = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Pairing en cours…")
            .setContentText("Handshake SPAKE2+ en cours")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Connexion au port de pairing en cours. " +
                    "Le handshake SPAKE2+ ne prend que quelques secondes…"
            ))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        post(appCtx, n, "Progress: SPAKE2+ handshake")
    }

    private fun showFailedNotification(appCtx: Context, error: String) {
        if (!ensureNotificationPermission(appCtx)) return
        ensureChannel(appCtx)

        val n = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Échec du pairing")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Le pairing a échoué : $error\n\n" +
                    "Appuie sur « Recommencer le pairing » pour effacer l’ancien pairing " +
                    "et reprendre proprement."
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000L)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Recommencer le pairing",
                resetPairingPendingIntent(appCtx)
            )
            .build()

        post(appCtx, n, "Failed: $error")
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

    private fun resetPairingPendingIntent(appCtx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            appCtx,
            3,
            Intent(appCtx, PrivilegedBroadcastReceiver::class.java).apply {
                action = PrivilegedBroadcastReceiver.ACTION_RESET_PAIRING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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

/**
 * BroadcastReceiver that handles the RemoteInput reply from Phase 2 notification.
 * The user types the 6-digit code inline in the notification and taps "Envoyer".
 * It immediately hands the long operation to the foreground service. A receiver
 * must not perform pairing itself because Android can stop it after onReceive.
 */
class PairingReplyReceiver : BroadcastReceiver() {

    companion object {
        const val KEY_PAIRING_CODE = "pairing_code"
        private const val TAG = "PairingReplyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PAIRING_PORT, 0)
        val host = intent.getStringExtra(PairingNotifier.EXTRA_PAIRING_HOST) ?: "127.0.0.1"
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_PAIRING_CODE)?.toString()

        if (code.isNullOrBlank() || code.length != 6) {
            Log.w(TAG, "Invalid pairing code: '${code?.take(3)}...'")
            PairingNotifier.onPairingFailed(context, "Le code doit contenir 6 chiffres")
            return
        }
        if (port <= 0) {
            Log.w(TAG, "No pairing port available (port=$port)")
            PairingNotifier.onPairingFailed(context, "Port de pairing introuvable")
            return
        }

        Log.i(TAG, "Pairing code received: host=$host port=$port")

        // Show progress immediately, then hand the operation to a real
        // foreground service so it survives the receiver lifecycle.
        PairingNotifier.onPairingStarted(context)
        PrivilegedRuntime.markStarting()
        runCatching {
            EmbeddedShizukuService.startPairAndStart(context, host, port, code)
        }.onFailure {
            Log.e(TAG, "Could not start pairing foreground service", it)
            PairingNotifier.onPairingFailed(context, it.message ?: "Impossible de démarrer le service")
        }
    }
}
