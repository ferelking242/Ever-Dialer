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
        showProgressNotification(context.applicationContext)
    }

    fun onPairingSucceeded(context: Context) {
        Log.i(TAG, "Pairing succeeded — showing Phase 3")
        showPhase3(context.applicationContext)
        stopWatching(context)
    }

    fun onPairingFailed(context: Context, error: String) {
        Log.w(TAG, "Pairing failed: $error")
        showFailedNotification(context.applicationContext, error)
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

    // ── Phase 2: notification with RemoteInput (inline code entry) ──────

    private fun showPhase2(appCtx: Context) {
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
            .setContentTitle("Service d'association trouvé")
            .setContentText("Port : $detectedPort — Entres le code à 6 chiffres")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Service de pairing détecté sur le port $detectedPort. " +
                    "Tape « Code d'association » puis entre le code à 6 chiffres et envoie."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(false)
            .addAction(action)
            .build()

        post(appCtx, n, "Phase 2: RemoteInput code entry")
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
                    "Vérifie que le débogage sans fil est actif, " +
                    "puis réessaie."
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000L)
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
 * Includes a 20-second timeout so the pairing attempt doesn't hang forever.
 */
class PairingReplyReceiver : BroadcastReceiver() {

    companion object {
        const val KEY_PAIRING_CODE = "pairing_code"
        private const val TAG = "PairingReplyReceiver"
        private const val PAIRING_TIMEOUT_MS = 20_000L // 20s max for SPAKE2+ handshake
    }

    override fun onReceive(context: Context, intent: Intent) {
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PAIRING_PORT, 0)
        val host = intent.getStringExtra(PairingNotifier.EXTRA_PAIRING_HOST) ?: "127.0.0.1"
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_PAIRING_CODE)?.toString()

        if (code.isNullOrBlank() || code.length != 6) {
            Log.w(TAG, "Invalid pairing code: '${code?.take(3)}...'")
            return
        }
        if (port <= 0) {
            Log.w(TAG, "No pairing port available (port=$port)")
            return
        }

        Log.i(TAG, "Pairing code received: host=$host port=$port")

        // Show "Pairing en cours…" notification immediately
        PairingNotifier.onPairingStarted(context)

        // goAsync() gives up to ~30s but we cap at 20s ourselves
        val pendingResult = goAsync()
        Thread {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(PAIRING_TIMEOUT_MS) {
                        PrivilegedRuntime.pairWithCode(context, host, port, code)
                    } ?: Result.failure(java.util.concurrent.TimeoutException(
                        "Le pairing a pris trop de temps (>${PAIRING_TIMEOUT_MS / 1000}s). " +
                            "Vérifie que le débogage sans fil est actif et réessaie."
                    ))
                }
                if (result.isSuccess) {
                    Log.i(TAG, "Pairing succeeded!")
                    PairingNotifier.onPairingSucceeded(context)
                } else {
                    // Get the FULL error message including root cause
                    val throwable = result.exceptionOrNull()
                    val errorMsg = throwable?.let {
                        val root = it.cause ?: it
                        "${it.message ?: "Erreur"} (${root.javaClass.simpleName})"
                    } ?: "Erreur inconnue"
                    Log.e(TAG, "Pairing failed: $errorMsg", throwable)
                    PairingNotifier.onPairingFailed(context, errorMsg)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Pairing error: ${e.message}", e)
                PairingNotifier.onPairingFailed(context, e.message ?: "Erreur fatale")
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
