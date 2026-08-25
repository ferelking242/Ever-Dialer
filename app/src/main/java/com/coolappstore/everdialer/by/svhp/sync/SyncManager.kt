/*
 * Ever Dialer+ — façade of the P2P sync module: UI state, pairing helpers,
 * background scheduling (WorkManager) and lifecycle of the listener server.
 */
package com.coolappstore.everdialer.by.svhp.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit

object SyncManager {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private const val PERIODIC_WORK = "everdial_sync_periodic"
    private const val ONE_SHOT_WORK = "everdial_sync_now"

    /** Called once from [com.coolappstore.everdialer.by.svhp.RivoApp.onCreate]. */
    fun init(appContext: Context) {
        val ctx = appContext.applicationContext
        refreshState(ctx)
        if (SyncStore.isEnabled(ctx)) {
            ensureRunning(ctx)
            schedulePeriodic(ctx, enabled = true)
        }
        log("Module sync initialisé (${_state.value.role})")
    }

    fun refreshState(context: Context) {
        val ctx = context.applicationContext
        val (id, name) = SyncStore.identity(ctx)
        _state.value = SyncUiState(
            enabled = SyncStore.isEnabled(ctx),
            role = SyncStore.role(ctx),
            myId = id,
            myName = name,
            peerId = SyncStore.peerId(ctx),
            peerName = SyncStore.peerName(ctx),
            lastSyncAt = SyncStore.lastSyncAt(ctx),
            lastStatus = SyncStore.lastStatus(ctx),
            serverPort = _state.value.serverPort
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val ctx = context.applicationContext
        SyncStore.setEnabled(ctx, enabled)
        if (enabled) ensureRunning(ctx) else SyncServer.stop()
        schedulePeriodic(ctx, enabled)
        refreshState(ctx)
        log(if (enabled) "Synchronisation activée" else "Synchronisation désactivée")
    }

    private fun ensureRunning(context: Context) {
        val ctx = context.applicationContext
        when (SyncStore.role(ctx)) {
            SyncRole.RECEIVER -> SyncServer.start(ctx)
            SyncRole.SENDER -> requestSyncNow(ctx)
            SyncRole.UNPAIRED -> Unit
        }
    }

    /** RECEIVER flow: generates the pairing blob to display/copy on phone B. */
    fun generateReceiverPairingCode(context: Context): String {
        val ctx = context.applicationContext
        val (id, name) = SyncStore.identity(ctx)
        val secret = SyncSecrets.randomB64(32)
        SyncStore.ownPairingSecret(ctx, secret)
        SyncStore.setRole(ctx, SyncRole.RECEIVER)
        setEnabled(ctx, true) // brings the NSD listener up immediately
        log("Code de jumelage généré — en attente du téléphone A")
        return SyncJson.encodeToString(
            PairingPayload(id = id, name = name, role = "receiver", secret = secret)
        )
    }

    /** SENDER flow: imports the blob shown on phone B. */
    fun importSenderPairingCode(context: Context, raw: String): Boolean {
        val ctx = context.applicationContext
        val payload = runCatching {
            SyncJson.decodeFromString<PairingPayload>(raw.trim())
        }.getOrElse {
            log("Code invalide : ${it.message}")
            return false
        }
        if (payload.role != "receiver") {
            log("Ce code n'est pas celui d'un récepteur.")
            return false
        }
        SyncStore.importPeer(ctx, payload)
        setEnabled(ctx, true)
        requestSyncNow(ctx)
        log("Appairé avec ${payload.name} ✔")
        return true
    }

    fun requestSyncNow(context: Context) {
        val ctx = context.applicationContext
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            ONE_SHOT_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
        )
    }

    private fun schedulePeriodic(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (enabled) {
            wm.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build()
            )
        } else {
            wm.cancelUniqueWork(PERIODIC_WORK)
        }
    }

    internal fun setServerPort(port: Int) {
        _state.value = _state.value.copy(serverPort = port)
    }

    private fun log(line: String) {
        _logs.value = (listOf("${System.currentTimeMillis()} · $line") + _logs.value).take(50)
    }
}

/** Background entry point used by the periodic schedule and manual triggers. */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!SyncStore.isEnabled(context)) return Result.success()
        if (SyncStore.role(context) != SyncRole.SENDER) return Result.success()

        return SyncClient.runPush(context).fold(
            onSuccess = { Result.success() },
            onFailure = { throwable ->
                val message = throwable.message ?: "erreur"
                if (message.contains("introuvable")) Result.retry() else Result.failure()
            }
        )
    }
}
