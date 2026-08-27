/* Ever Dialer+ — privileged runtime notification manager.
 *
 * Three-phase notification flow mirroring the real Shizuku manager:
 *
 *   Phase 1 — user taps "Gérer le pairing" → opens Dev Settings AND shows
 *   an immediate ongoing notification "En attente du débogage sans fil…".
 *
 *   Phase 2 — mDNS discovers `_adb-tls-pairing._tcp` → notification is
 *   REPLACED with "Débogage sans fil disponible" + an inline text input
 *   (RemoteInput) where the user types the 6-digit pairing code and taps
 *   "Envoyer". No Activity needed — everything happens in the notification.
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

    /** Stored when mDNS detects the port, so the BroadcastReceiver can use it. */
    @Volatile
    var detectedPort: Int = 0
        private set

    @Volatile
    var detectedHost: String = "127.0.0.1"
        private set

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Phase 1 — called when user taps "Gérer le pairing" in Settings.
     * Shows an IMMEDIATE notification "En attente du débogage sans fil…"
     * AND starts the mDNS watcher for Phase 2.
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
                startMdnsWatcher(appContext)
                return
            }
        }

        ensureChannel(appContext)

        // Phase 1: immediate "waiting" notification
        val devSettingsIntent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingDevSettings = PendingIntent.getActivity(
            appContext, 0, devSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("En attente du débogage sans fil…")
            .setContentText("Active le débogage sans fil puis tape « Associer avec un code »")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Ouvre les Options Développeur → Débogage sans fil → Associer avec un code. " +
                        "Quand le service de pairing sera disponible, cette notification se mettra à jour " +
                        "pour te laisser entrer le code directement."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingDevSettings)
            .build()

        postNotification(appContext, notification, "Phase 1: waiting")

        Log.d(TAG, "Phase 1 notification shown — starting mDNS watcher")
        startMdnsWatcher(appContext)
    }

    /** Stop monitoring and dismiss any visible notification. */
    fun stopWatching(context: Context) {
        runCatching { mdnsWatcher?.stop() }
        mdnsWatcher = null
        isWatching = false
        cancelNotification(context.applicationContext)
    }

    /** Call after successful pairing to show success then dismiss. */
    fun onPairingSucceeded(context: Context) {
        Log.d(TAG, "Pairing succeeded — showing Phase 3 notification")
        showPhase3Notification(context.applicationContext)
        stopWatching(context)
    }

    /** Call on pairing failure — shows error toast via notification. */
    fun onPairingFailed(context: Context, error: String) {
        Log.w(TAG, "Pairing failed: $error")
        // Re-show Phase 2 so user can try again
        if (detectedPort > 0) {
            showPhase2Notification(context.applicationContext)
        }
    }

    // ── Phase 2: mDNS watcher ───────────────────────────────────────────

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
                    detectedHost = host.ifBlank { "127.0.0.1" }
                    detectedPort = port
                    Log.i(TAG, "Pairing service detected — showing Phase 2 notification")
                    showPhase2Notification(appContext)
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

    // ── Phase 2: notification with inline code input ────────────────────

    private fun showPhase2Notification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted — cannot show Phase 2")
                return
            }
        }

        ensureChannel(context)

        // PendingIntent for the reply BroadcastReceiver (MUTABLE for RemoteInput)
        val replyIntent = Intent(context, PairingReplyReceiver::class.java).apply {
            putExtra(EXTRA_PAIRING_PORT, detectedPort)
            putExtra(EXTRA_PAIRING_HOST, detectedHost)
        }
        val replyPending = PendingIntent.getBroadcast(
            context, 2, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // RemoteInput: inline text field in the notification
        val remoteInput = RemoteInput.Builder(PairingReplyReceiver.KEY_PAIRING_CODE)
            .setLabel("Code à 6 chiffres")
            .setAllowFreeFormInput(false)
            .build()

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.stat_sys_send,
            "Envoyer",
            replyPending
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Débogage sans fil disponible !")
            .setContentText("Entre le code à 6 chiffres puis tape « Envoyer »")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Service de pairing détecté sur le port $detectedPort. " +
                        "Entre le code à 6 chiffres affiché par Android puis tape « Envoyer »."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(replyAction)
            .build()

        postNotification(context, notification, "Phase 2: inline code input")
    }

    // ── Phase 3: pairing success notification ───────────────────────────

    private fun showPhase3Notification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return
        }

        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✔ Appairé et actif")
            .setContentText("Le moteur privilégié démarre…")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Appairage réussi ! Le moteur privilégié démarre automatiquement. " +
                        "L'enregistrement des appels est prêt."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
            .setTimeoutAfter(10_000L) // auto-dismiss after 10s
            .build()

        postNotification(context, notification, "Phase 3: paired + starting")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun postNotification(context: Context, notification: android.app.Notification, label: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Notification posted: $label (id=$NOTIFICATION_ID)")
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

/**
 * BroadcastReceiver that handles the inline reply from the Phase 2 notification.
 * The user types the 6-digit code directly in the notification and taps "Envoyer".
 */
class PairingReplyReceiver : BroadcastReceiver() {

    companion object {
        const val KEY_PAIRING_CODE = "pairing_code"
        private const val TAG = "PairingReplyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PAIRING_PORT, 0)
        val host = intent.getStringExtra(PairingNotifier.EXTRA_PAIRING_HOST) ?: "127.0.0.1"

        val code = extractCode(intent)
        if (code.isNullOrBlank() || code.length != 6) {
            Log.w(TAG, "Invalid or missing pairing code from notification reply")
            return
        }
        if (port <= 0) {
            Log.w(TAG, "No pairing port available — mDNS may not have detected the service yet")
            return
        }

        Log.i(TAG, "Received pairing code from notification reply — port=$port code=***")

        // goAsync() gives us ~30s to complete the SPAKE2p handshake
        val pendingResult = goAsync()

        Thread {
            try {
                val result = PrivilegedRuntime.pairWithCode(context, host, port, code)
                if (result.isSuccess) {
                    Log.i(TAG, "Pairing succeeded from notification reply!")
                    PairingNotifier.onPairingSucceeded(context)
                } else {
                    val err = result.exceptionOrNull()
                    Log.e(TAG, "Pairing failed from notification reply: ${err?.message}")
                    PairingNotifier.onPairingFailed(context, err?.message ?: "Erreur inconnue")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected error during pairing: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun extractCode(intent: Intent): String? {
        val results = RemoteInput.getResultsFromIntent(intent)
        return results?.getCharSequence(KEY_PAIRING_CODE)?.toString()
    }
}
