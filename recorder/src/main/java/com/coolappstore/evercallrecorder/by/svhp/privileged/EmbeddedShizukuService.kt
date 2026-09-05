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
import com.coolappstore.evercallrecorder.by.svhp.utils.NtfyReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

class EmbeddedShizukuService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null
    private var startupJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (watchdogJob?.isActive != true) {
            watchdogJob = scope.launch { watchdogLoop() }
        }
        NtfyReporter.publish("runtime", "foreground service command=${intent?.action ?: "restart"}")
        when (intent?.action) {
            ACTION_PAIR_AND_START -> {
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
                PrivilegedRuntime.markStarting()
                startupJob?.cancel()
                startupJob = scope.launch {
                    val result: Result<Unit> = withTimeoutOrNull(PAIRING_TIMEOUT_MS) {
                        PrivilegedRuntime.pairAndStart(this@EmbeddedShizukuService, host, port, code)
                    } ?: Result.failure(
                        TimeoutException("Le pairing et le démarrage dépassent ${PAIRING_TIMEOUT_MS / 1000}s")
                    )
                    if (result.isSuccess) {
                        PairingNotifier.onPairingSucceeded(this@EmbeddedShizukuService)
                    } else {
                        PairingNotifier.onPairingFailed(
                            this@EmbeddedShizukuService,
                            result.exceptionOrNull()?.message ?: "Erreur de démarrage"
                        )
                        stopSelf()
                    }
                    startupJob = null
                }
            }
            ACTION_START -> {
                if (startupJob?.isActive != true) startExistingRuntime()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        startupJob?.cancel()
        watchdogJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startExistingRuntime() {
        if (!PrivilegedRuntime.isPaired(applicationContext)) return
        PrivilegedRuntime.markStarting()
        PairingNotifier.showStartingNotification(applicationContext)
        startupJob?.cancel()
        startupJob = scope.launch {
            val result = PrivilegedRuntime.ensureServerStarted(applicationContext)
            if (result.isSuccess) {
                PairingNotifier.onRuntimeStarted(applicationContext)
            } else {
                PairingNotifier.onRuntimeFailed(
                    applicationContext,
                    result.exceptionOrNull() ?: IllegalStateException("Démarrage refusé")
                )
                if (!PrivilegedRuntime.isPaired(applicationContext)) {
                    PairingNotifier.showWaitingNotification(applicationContext)
                }
                stopSelf()
            }
            startupJob = null
        }
    }

    private suspend fun watchdogLoop() {
        var backoffMs = POLL_INTERVAL_MS
        while (scope.isActive) {
            delay(backoffMs)
            val context = applicationContext
            if (!PrivilegedRuntime.isWatchdogEnabled(context)) continue
            if (PrivilegedRuntime.state.value == PrivilegedRuntime.State.STARTING) continue
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
            Intent(this, com.coolappstore.evercallrecorder.by.svhp.MainActivity::class.java),
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
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, EmbeddedShizukuService::class.java).setAction(ACTION_START)
            )
        }

        fun startPairAndStart(context: Context, host: String, port: Int, code: String) {
            val intent = Intent(context, EmbeddedShizukuService::class.java).apply {
                action = ACTION_PAIR_AND_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_CODE, code)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmbeddedShizukuService::class.java))
        }

        const val ACTION_START = "com.coolappstore.evercallrecorder.action.START_RUNTIME"
        const val ACTION_PAIR_AND_START = "com.coolappstore.evercallrecorder.action.PAIR_AND_START"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_CODE = "extra_pairing_code"
        private const val PAIRING_TIMEOUT_MS = 90_000L
    }
}
