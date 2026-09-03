/*
 * Ever Dialer+ — privileged runtime (Phase 2).
 *
 * Embeds the Shizuku server startup inside this single app, using a local
 * wireless-debugging connection (Android 11+ pairing, LADB/Shizuku-manager style):
 *   1. SPAKE2p pairing against adbd on localhost (vendored moe.shizuku.manager.adb stack)
 *   2. Push of the pinned thedjchi/Shizuku fork APK into a directory named
 *      after this application's package id under /data/local/tmp/
 *   3. Launch of the vendored libshizuku.so starter from a hash-versioned
 *      remote filename:
 *        <nativeLibraryDir>/libshizuku.so --apk=/data/local/tmp/<package>/shizuku-server.apk
 *      (exact replication of Shizuku manager's Starter.internalCommand)
 *   4. The server then pushes its binder into our own rikka.shizuku.ShizukuProvider,
 *      so every existing dev.rikka.shizuku API call keeps working untouched.
 */
package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.coolappstore.evercallrecorder.by.svhp.BuildConfig
import com.coolappstore.evercallrecorder.by.svhp.integrations.shizuku.ShizukuConnectionManager
import com.coolappstore.evercallrecorder.by.svhp.utils.NtfyReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume

object PrivilegedRuntime {

    enum class State { NOT_PAIRED, PAIRED_IDLE, STARTING, RUNNING, FAILED }

    private const val TAG = "PrivilegedRuntime"
    private const val PREFS_NAME = "privileged_runtime"
    private const val KEY_ADB_KEY = "adbkey"
    private const val KEY_LAST_HOST = "last_connect_host"
    private const val KEY_LAST_PORT = "last_connect_port"
    private const val KEY_WATCHDOG_ENABLED = "watchdog_enabled"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /**
     * Remote working directory; writable AND executable by the shell uid.
     *
     * The embedded Shizuku server derives the manager package name from the
     * parent directory of the APK passed to the native starter. This directory
     * must therefore be the real application id, not an arbitrary cache name.
     */
    private const val REMOTE_DIR = "/data/local/tmp/com.coolappstore.everdialer.by.svhp"
    private const val REMOTE_APK_PATH = "$REMOTE_DIR/shizuku-server.apk"
    private const val REMOTE_LOG_PATH = "$REMOTE_DIR/starter.log"

    /**
     * How long to poll for the Shizuku binder after launching the starter.
     *
     * The pushed APK runs under app_process as the shell uid, so ART cannot
     * write oat caches and interprets the dex on every boot; on slow ROMs the
     * server also scans the installed packages before it can push its binder.
     * The fork manager itself waits up to 60 s for the binder ("This may take
     * up to 1 minute"), so any shorter window here reports failure while the
     * server is still booting.
     */
    private const val BINDER_WAIT_MS = 60_000L

    /**
     * Extra chance given to the binder AFTER the diagnostics have run: the
     * server may still be compiling its dex or retrying its binder push while
     * we collect logs, so a timeout right after launch is not final.
     */
    private const val BINDER_GRACE_MS = 8_000L

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state

    @Volatile
    private var startingJobCount = 0
    private val startMutex = Mutex()

    private fun initialState(): State =
        if (ShizukuConnectionManager.isAvailable()) State.RUNNING else State.NOT_PAIRED

    fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPaired(context: Context): Boolean =
        runCatching {
            PreferenceAdbKeyStore(prefs(context)).get() != null
        }.getOrDefault(false)

    /**
     * Forget the local wireless-debugging pairing after adbd rejects the key.
     * The next attempt must go through Android's pairing dialog again.
     */
    fun forgetPairing(context: Context) {
        prefs(context).edit()
            .remove(KEY_ADB_KEY)
            .remove(KEY_LAST_HOST)
            .remove(KEY_LAST_PORT)
            .apply()
        _state.value = State.NOT_PAIRED
    }

    fun isWatchdogEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WATCHDOG_ENABLED, true)

    fun setWatchdogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATCHDOG_ENABLED, enabled).apply()
    }

    fun refreshState() {
        if (startingJobCount > 0) return
        _state.value = when {
            ShizukuConnectionManager.isAvailable() -> State.RUNNING
            else -> State.NOT_PAIRED
        }
    }

    /**
     * Refresh state with access to a Context for the paired check.
     * Call this from UI components that have a Context available.
     */
    fun refreshState(context: Context) {
        if (startingJobCount > 0) return
        _state.value = when {
            ShizukuConnectionManager.isAvailable() -> State.RUNNING
            isPaired(context) -> State.PAIRED_IDLE
            else -> State.NOT_PAIRED
        }
    }

    /** Reads the live binder state for UI indicators and click guards. */
    fun isConnected(): Boolean = ShizukuConnectionManager.isAvailable()

    /**
     * Called from the in-app [EverShizukuProvider] when the embedded server's
     * binder push reaches this process. Lets the badge recover to RUNNING even
     * when the binder arrives after a startup attempt already gave up (e.g.
     * slow first dex compilation on the device), and reports the state change.
     */
    fun notifyBinderDelivered() {
        if (startingJobCount == 0 && ShizukuConnectionManager.isAvailable()) {
            _state.value = State.RUNNING
        }
    }

    /**
     * Reads Android's actual Wireless debugging switch.
     *
     * A remembered ADB key can remain stored after Wireless debugging has been
     * disabled, but it cannot reconnect until the system switch is enabled.
     */
    fun isWirelessDebuggingEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
        }.getOrDefault(false)
    }

    fun markStarting() {
        _state.value = State.STARTING
    }

    /**
     * Opens the same wireless-debugging flow as the Shizuku manager.
     *
     * @return false only when the embedded Shizuku binder is active and the
     * user has already granted this app Shizuku permission.
     */
    fun openManagement(context: Context): Boolean {
        val appContext = context.applicationContext
        if (isConnected()) {
            if (!ShizukuConnectionManager.hasPermission(appContext)) {
                NtfyReporter.publish("runtime", "requesting app Shizuku permission")
                ShizukuConnectionManager.requestPermission()
                return true
            }
            return false
        }

        if (!isWirelessDebuggingEnabled(appContext)) {
            PairingNotifier.showWirelessDebuggingRequiredNotification(appContext)
            openDeveloperSettings(appContext)
            return true
        }

        if (isPaired(appContext)) {
            _state.value = State.STARTING
            NtfyReporter.publish("runtime", "startup requested from header badge")
            PairingNotifier.showStartingNotification(appContext)
            runCatching {
                EmbeddedShizukuService.start(appContext)
            }.onFailure {
                _state.value = State.FAILED
                PairingNotifier.onRuntimeFailed(appContext, it)
            }
            return true
        }

        // Pairing is notification-only. The notification opens Android's
        // developer settings and receives the code inline through RemoteInput.
        PairingNotifier.showWaitingNotification(appContext)
        openDeveloperSettings(appContext)
        return true
    }

    private fun openDeveloperSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ─────────────────────────── Pairing ───────────────────────────

    /**
     * Runs the SPAKE2p pairing handshake against adbd's one-time pairing port,
     * using the 6-digit code displayed by the system pairing dialog.
     * On success the shared RSA key is persisted (encrypted with Android Keystore),
     * enabling silent reconnects later.
     */
    suspend fun pairWithCode(context: Context, host: String, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                check(isWirelessDebuggingEnabled(context)) {
                    "Débogage sans fil désactivé. Active-le dans les Options pour les développeurs."
                }
                require(code.isNotBlank()) { "empty pairing code" }
                NtfyReporter.publish("pairing", "starting host=${host.ifBlank { "127.0.0.1" }} port=$port")
                val key = adbKey(context)
                val client = AdbPairingClient(host.ifBlank { "127.0.0.1" }, port, code.trim(), key)
                val ok = client.use { it.start() }
                if (ok) {
                    Log.i(TAG, "Pairing succeeded")
                    NtfyReporter.publish("pairing", "SPAKE2+ handshake succeeded")
                    _state.value = State.PAIRED_IDLE
                    Result.success(Unit)
                } else {
                    Log.w(TAG, "Pairing failed (wrong code or stale port)")
                    forgetPairing(context)
                    NtfyReporter.publish("pairing", "handshake rejected: invalid code or expired port", "high")
                    Result.failure(AdbException("Code invalide ou port expiré"))
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Pairing error", e)
                NtfyReporter.publish("pairing", "error ${e.javaClass.simpleName}: ${e.message ?: "unknown"}", "high")
                Result.failure(e)
            }
        }

    /**
     * Pair the device and immediately bring the embedded server up.
     *
     * Pairing only stores the ADB key. Keeping the server start in this stable
     * runtime layer (rather than in a Compose effect) means the operation
     * continues even if the pairing screen is recreated or closed.
     */
    suspend fun pairAndStart(
        context: Context,
        host: String,
        port: Int,
        code: String
    ): Result<Unit> {
        val pairing = pairWithCode(context, host, port, code)
        if (pairing.isFailure) return pairing
        return ensureServerStarted(context)
    }

    /**
     * Starts NSD discovery of the one-time `_adb-tls-pairing._tcp` service so the UI
     * can auto-fill the pairing port while the user opens the system pairing dialog.
     * Returns the AdbMdns instance; caller must call .stop() (see DisposableEffect).
     */
    fun observePairingPort(context: Context, onEndpoint: (host: String, port: Int) -> Unit): AdbMdns? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val mdns = AdbMdns(context.applicationContext, AdbMdns.TLS_PAIRING) { (host, resolvedPort) ->
                if (resolvedPort > 0 && host.isNotBlank()) onEndpoint(host, resolvedPort)
            }
            mdns.start()
            mdns
        } catch (e: Throwable) {
            Log.w(TAG, "Pairing mDNS unavailable: ${e.message}")
            null
        }
    }

    // ─────────────────────── Server lifecycle ───────────────────────

    /**
     * Full "make Shizuku work" pipeline. Safe to call repeatedly: returns fast
     * when the binder is already alive.
     */
    suspend fun ensureServerStarted(
        context: Context,
        log: ((String) -> Unit)? = null
    ): Result<Unit> = startMutex.withLock {
        withContext(Dispatchers.IO) {
            startingJobCount++
            val appContext = context.applicationContext
            try {
            if (ShizukuConnectionManager.isAvailable()) {
                log?.invoke("Serveur déjà actif ✔")
                _state.value = State.RUNNING
                return@withContext Result.success(Unit)
            }
            if (!isPaired(appContext)) {
                _state.value = State.NOT_PAIRED
                return@withContext Result.failure(AdbException("Appareil non apparié"))
            }
            if (!isWirelessDebuggingEnabled(appContext)) {
                _state.value = State.FAILED
                val msg = "Débogage sans fil désactivé. Active-le dans les Options pour les développeurs."
                log?.invoke(msg)
                NtfyReporter.publish("runtime", "wireless debugging is disabled", "high")
                return@withContext Result.failure(AdbException(msg))
            }

            _state.value = State.STARTING
            log?.invoke("Recherche du port du débogage sans fil…")
            NtfyReporter.publish("runtime", "starting embedded Shizuku server")
            NtfyReporter.publish(
                "runtime",
                "app binder pre-start: ping=${ShizukuConnectionManager.isAvailable()} " +
                    "perm=${ShizukuConnectionManager.hasPermission(appContext)}"
            )

            val endpoint = resolveConnectEndpoint(appContext, log)
                ?: run {
                    _state.value = State.FAILED
                    val msg = "Port introuvable. Active « Débogage sans fil » puis réessaie."
                    log?.invoke(msg)
                    return@withContext Result.failure(AdbException(msg))
                }
            log?.invoke("Connexion à adbd sur ${endpoint.host}:${endpoint.port}…")

            val key = adbKey(appContext)
            var connected = false
            var lastLaunchError: Throwable? = null
            val launchOutput = StringBuilder()
            val maxAttempts = 3
            // True once a silent-but-alive server has been killed for a single
            // controlled relaunch. At most one escalation is allowed per call.
            var escalated = false

            /*
             * Phase loop:
             *  - phase 0: launch the starter (or adopt a shizuku_server that is
             *    already running) and wait for the binder.
             *  - if the binder never appears while a server process stays alive,
             *    kill it once and relaunch (phase 1), matching the fork
             *    manager's own "kill and try again" recovery.
             */
            for (phase in 0..1) {
            var serverWasRunning = false
            var launched = false
            var attempt = 1
            var attemptError: Throwable? = null

            while (attempt <= maxAttempts) {
                try {
                    AdbClient(endpoint.host, endpoint.port, key).use { client ->
                        client.connect()
                        connected = true
                        prefs(appContext).edit()
                            .putString(KEY_LAST_HOST, endpoint.host)
                            .putInt(KEY_LAST_PORT, endpoint.port)
                            .apply()

                        if (!escalated && isShizukuServerRunning(client)) {
                            /*
                             * A shizuku_server from an earlier attempt is alive
                             * (it may still be booting: app_process dex loading
                             * and package scans can exceed 30 s on slow ROMs).
                             * Do NOT relaunch over it — the native starter
                             * kills the old process, so a retry here murdered
                             * the still-booting server and made every attempt
                             * start from zero. Adopt it and wait for its binder.
                             */
                            serverWasRunning = true
                            log?.invoke(
                                "Un shizuku_server est déjà actif ; attente du binder " +
                                    "(≤${BINDER_WAIT_MS / 1000} s)…"
                            )
                            NtfyReporter.publish(
                                "runtime",
                                "existing shizuku_server alive; skipping re-launch and waiting for binder"
                            )
                        } else {
                            val starterPath = ensureRemotePayloadChecked(client, appContext, log)

                            log?.invoke("Lancement du serveur Shizuku embarqué…")
                            NtfyReporter.publish(
                                "runtime",
                                if (escalated) {
                                    "relaunching silent server on ${endpoint.host}:${endpoint.port}"
                                } else {
                                    "launching starter on ${endpoint.host}:${endpoint.port}"
                                }
                            )

                            /*
                             * Mirror the fork manager's proven invocation exactly:
                             * plain `<starter> --apk=<apk>`. The exit marker is
                             * emitted INSIDE the pipeline so it is also teed into
                             * the remote log: the binder-timeout diagnostic can
                             * then tell a starter that never ran (no log file at
                             * all) from one that ran and failed (exit != 0).
                             */
                            launchOutput.setLength(0)
                            val launchCmd =
                                "shell:{ toybox chmod 0755 '$starterPath' 2>&1 || true; " +
                                    "'$starterPath' --apk='$REMOTE_APK_PATH' 2>&1; " +
                                    "echo EVER_STARTER_EXIT=\$?; } | " +
                                    "toybox tee '$REMOTE_LOG_PATH'"
                            client.command(launchCmd) { bytes ->
                                launchOutput.append(String(bytes))
                            }
                            // The starter ran to completion (or its stream closed
                            // after forking the server): from here on the server
                            // owns its lifecycle, so never re-launch on a later
                            // transient error.
                            launched = true
                            val starterSummary = launchOutput.toString().trim()
                            if (starterSummary.isNotBlank()) {
                                log?.invoke("Starter : ${starterSummary.take(500)}")
                                NtfyReporter.publish(
                                    "runtime",
                                    "starter result: ${starterSummary.take(700)}"
                                )
                            } else {
                                log?.invoke("Starter terminé sans sortie ; vérification du binder…")
                                NtfyReporter.publish("runtime", "starter returned without output")
                            }

                            // Same-connection process snapshot: know immediately
                            // whether shizuku_server came up, even if a later
                            // diagnostic reconnect fails.
                            val snapshot = StringBuilder()
                            runCatching {
                                client.command(
                                    "shell:ps -A -o USER,PID,PPID,NAME,ARGS | " +
                                        "grep -E 'shizuku_server|ShizukuService|app_process' | " +
                                        "grep -v grep || true"
                                ) { bytes ->
                                    snapshot.append(String(bytes))
                                }
                            }
                            if (snapshot.isNotBlank()) {
                                NtfyReporter.publish(
                                    "runtime",
                                    "post-launch ps: ${snapshot.toString().trim().take(700)}"
                                )
                            }
                        }
                    }
                    attemptError = null
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    attemptError = e
                    lastLaunchError = e
                }

                if (attemptError == null) break
                // After a launch (or when adopting a running server) never
                // re-enter: the process owns its lifecycle from here.
                if (launched || serverWasRunning || isPairingInvalid(attemptError) || attempt >= maxAttempts) break

                attempt += 1
                log?.invoke("Tentative ${attempt - 1} échouée : ${attemptError.message} — nouvel essai…")
                NtfyReporter.publish(
                    "runtime",
                    "attempt ${attempt - 1} failed (${attemptError.javaClass.simpleName}: " +
                        "${attemptError.message ?: "unknown"}), retrying on a fresh connection",
                    "high"
                )
                connected = false
                delay(1500L)
            }

            if (!connected) {
                _state.value = State.FAILED
                val err = lastLaunchError ?: AdbException("Connexion à adbd impossible")
                Log.e(TAG, "ensureServerStarted: could not connect to adbd", err)
                NtfyReporter.publish("runtime", "adbd connect failed: ${err.message}", "high")
                return@withContext Result.failure(err)
            }

            if (!launched && !serverWasRunning) {
                /*
                 * The connection succeeded but the pipeline never reached the
                 * starter (payload transfer, install or launch-command write
                 * failed and the retries were exhausted). Fail immediately with
                 * the REAL error instead of silently degrading into a long fake
                 * binder wait, which is what made every earlier run look like a
                 * generic "server did not answer" timeout.
                 */
                _state.value = State.FAILED
                val err = lastLaunchError ?: AdbException("Échec avant le lancement du starter")
                if (isPairingInvalid(err)) {
                    forgetPairing(appContext)
                }
                log?.invoke("Échec avant le lancement : ${err.message}")
                NtfyReporter.publish(
                    "runtime",
                    "startup failed before launch: ${err.javaClass.simpleName}: " +
                        "${err.message ?: "unknown"}",
                    "high"
                )
                return@withContext Result.failure(err)
            }

            /*
             * The native starter detaches the server (fork + setsid) and the
             * launch stream can close before the server is up — or the stream
             * drain can time out while the server still starts fine. Never
             * report failure without giving the binder a real chance (the fork
             * manager waits up to 60 s).
             */
            log?.invoke("En attente du binder Shizuku (≤${BINDER_WAIT_MS / 1000} s)…")
            var up = waitForBinder(timeoutMillis = BINDER_WAIT_MS)
            var remoteLog: String? = null
            if (!up) {
                /*
                 * Diagnostics take several seconds (logcat dump, process list).
                 * The server may still be JIT-compiling its dex or retrying its
                 * binder push during that window, so give the binder one last
                 * short chance before declaring failure.
                 */
                remoteLog = readRemoteStartupLog(endpoint, key)
                up = waitForBinder(timeoutMillis = BINDER_GRACE_MS)
            }
            if (up) {
                _state.value = State.RUNNING
                log?.invoke("Privilèges système actifs ✔")
                NtfyReporter.publish("runtime", "embedded Shizuku binder is running")
                return@withContext Result.success(Unit)
            }

            /*
             * Binder still missing. Report what the device actually shows
             * (process alive or dead) and escalate once if the server process
             * survived the whole window without publishing: such a silent
             * process will not publish later, so replace it with a fresh one.
             */
            val serverAlive = probeShizukuServer(endpoint, key)
            val launchSummary = launchOutput.toString().trim()
            val stateLine = if (serverAlive) {
                "server process ALIVE but binder missing after ${BINDER_WAIT_MS / 1000}s"
            } else {
                "server process NOT running"
            }
            NtfyReporter.publish(
                "runtime",
                "binder timeout; $stateLine; launch=${launchSummary.take(300)}; " +
                    "remote log=${remoteLog ?: "empty"}",
                "high"
            )

            if (!escalated && phase == 0 && (serverAlive || serverWasRunning)) {
                // One controlled replacement: kill the silent server, clear the
                // stale remote log and relaunch through the normal pipeline.
                escalated = true
                log?.invoke("Serveur muet : redémarrage contrôlé…")
                NtfyReporter.publish(
                    "runtime",
                    "silent server: killing it and relaunching once",
                    "high"
                )
                runCatching {
                    AdbClient(endpoint.host, endpoint.port, key).use { client ->
                        client.connect()
                        killRemoteServer(client)
                    }
                }.onFailure {
                    Log.w(TAG, "could not kill silent server: ${it.message}")
                }
                continue
            }

            _state.value = State.FAILED
            val msg = if (remoteLog.isNullOrBlank()) {
                "Le serveur ne répond pas après ${BINDER_WAIT_MS / 1000} s. Vérifie le débogage sans fil."
            } else if (serverAlive) {
                "Le serveur tourne mais ne publie pas le binder après " +
                    "${BINDER_WAIT_MS / 1000} s : ${remoteLog.take(700)}"
            } else {
                "Le starter a échoué : ${remoteLog.take(700)}"
            }
            log?.invoke(msg)
            return@withContext Result.failure(AdbException(msg))
            }
            // Unreachable: every phase path returns above.
            throw IllegalStateException("unreachable")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val pairingInvalid = isPairingInvalid(e)
                if (pairingInvalid) {
                    forgetPairing(appContext)
                }
                Log.e(TAG, "ensureServerStarted failed", e)
                NtfyReporter.publish("runtime", "startup error ${e.javaClass.simpleName}: ${e.message ?: "unknown"}", "high")
                if (!pairingInvalid) _state.value = State.FAILED
                Result.failure(e)
            } finally {
                startingJobCount--
            }
        }
    }

    suspend fun stopServer(context: Context) = withContext(Dispatchers.IO) {
        EmbeddedShizukuService.stop(context.applicationContext)
        try {
            val appContext = context.applicationContext
            val key = adbKey(appContext)
            val port = prefs(appContext).getInt(KEY_LAST_PORT, -1)
            val host = prefs(appContext).getString(KEY_LAST_HOST, null)
                ?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
            if (port > 0) {
                AdbClient(host, port, key).use { client ->
                    client.connect()
                    // Kill the detached server (--nice-name=shizuku_server) and
                    // any leftover starter binaries from previous launches.
                    client.command(
                        "shell:pkill -x shizuku_server || true; " +
                            "pkill -f '[s]hizuku-starter-[0-9a-f]+-' || true"
                    )
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "stopServer: ${e.message}")
        }
        _state.value = if (isPaired(context)) State.PAIRED_IDLE else State.NOT_PAIRED
    }

    /**
     * Watchdog entry point: tries a full restart, swallowing errors (the caller
     * applies backoff). Returns true when the binder ended up alive.
     */
    suspend fun watchdogRestart(context: Context): Boolean =
        ensureServerStarted(context.applicationContext).isSuccess

    // ─────────────────────────── Internals ───────────────────────────

    private fun adbKey(context: Context): AdbKey =
        AdbKey(PreferenceAdbKeyStore(prefs(context)), context.packageName.take(24))

    private data class AdbEndpoint(val host: String, val port: Int)

    private suspend fun resolveConnectEndpoint(
        context: Context,
        log: ((String) -> Unit)?
    ): AdbEndpoint? {
        // 1. Try the remembered endpoint with a cheap probe.
        val last = prefs(context).getInt(KEY_LAST_PORT, -1)
        val lastHost = prefs(context).getString(KEY_LAST_HOST, null)
            ?.takeIf { it.isNotBlank() }
        if (last > 0 && lastHost != null && probeAdbd(lastHost, last)) {
            log?.invoke("Connexion mémorisée $lastHost:$last opérationnel.")
            return AdbEndpoint(lastHost, last)
        }
        // Compatibility with builds that remembered only the port.
        if (last > 0 && probeAdbd("127.0.0.1", last)) {
            log?.invoke("Port mémorisé 127.0.0.1:$last opérationnel.")
            return AdbEndpoint("127.0.0.1", last)
        }
        // 2. mDNS discovery (_adb-tls-connect._tcp), bounded and cancellable.
        // The app pairs with ITSELF: adbd's wireless listener also accepts
        // loopback connections, and loopback avoids hairpin routing issues on
        // some ROMs. Prefer 127.0.0.1 when it answers, fall back to the LAN
        // host only if loopback is not reachable.
        log?.invoke("Découverte mDNS en cours…")
        val discovered = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                lateinit var mdns: AdbMdns
                mdns = AdbMdns(context.applicationContext, AdbMdns.TLS_CONNECT) { (host, port) ->
                    if (port > 0 && host.isNotBlank() && cont.isActive) {
                        runCatching { mdns.stop() }
                        val connectHost =
                            if (probeAdbd("127.0.0.1", port)) "127.0.0.1" else host
                        cont.resume(AdbEndpoint(connectHost, port))
                    }
                }
                cont.invokeOnCancellation { runCatching { mdns.stop() } }
                runCatching { mdns.start() }
                    .onFailure { error ->
                        runCatching { mdns.stop() }
                        if (cont.isActive) cont.resumeWith(Result.failure(error))
                    }
            }
        }
        return discovered
    }

    private fun probeAdbd(host: String, port: Int): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), 1200)
            true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * True when a detached `shizuku_server` process is alive on the device.
     * The native starter names the app_process child via `--nice-name`, so its
     * comm is exactly `shizuku_server` (14 chars, not truncated by the 15-char
     * comm limit).
     */
    private fun isShizukuServerRunning(client: AdbClient): Boolean {
        return runCatching {
            val out = StringBuilder()
            client.command(
                "shell:toybox pidof shizuku_server 2>/dev/null || " +
                    "ps -A -o NAME= 2>/dev/null | grep shizuku_server || true"
            ) { bytes ->
                out.append(String(bytes))
            }
            out.toString().isNotBlank()
        }.getOrDefault(false)
    }

    /** Opens a fresh connection just to check whether shizuku_server is alive. */
    private fun probeShizukuServer(endpoint: AdbEndpoint, key: AdbKey): Boolean = try {
        AdbClient(endpoint.host, endpoint.port, key).use { client ->
            client.connect()
            isShizukuServerRunning(client)
        }
    } catch (e: Throwable) {
        Log.w(TAG, "server liveness probe failed: ${e.message}")
        false
    }

    /** Kills a silent shizuku_server and clears the stale remote log. */
    private fun killRemoteServer(client: AdbClient) {
        client.command(
            "shell:pkill -x shizuku_server 2>/dev/null || true; " +
                "sleep 1; rm -f '$REMOTE_LOG_PATH' 2>/dev/null || true"
        )
    }

    private fun readRemoteStartupLog(endpoint: AdbEndpoint, key: AdbKey): String? {
        val output = StringBuilder()
        try {
            AdbClient(endpoint.host, endpoint.port, key).use { client ->
                client.connect()

                // Each diagnostic is independent: one failing command must not
                // hide the others (this is why previous reports were empty).
                fun collect(title: String, command: String) {
                    output.append("\n-- $title --\n")
                    runCatching {
                        client.command(command) { bytes -> output.append(String(bytes)) }
                    }.onFailure {
                        output.append("[erreur: ${it.javaClass.simpleName}: ${it.message}]")
                    }
                }

                collect("starter.log", "shell:toybox tail -c 4000 '$REMOTE_LOG_PATH' 2>&1")
                // The native starter detaches the Java server and redirects its
                // stdout/stderr to /dev/null, so the server's own logs (and any
                // fatal crash, SELinux denial, or app_process error) only land
                // in logcat. The ring buffer is bounded first (-t 3000) because
                // Samsung's buffer overflows in seconds and a full dump is both
                // huge and stale; the filter is narrow enough to skip noise such
                // as "InterruptionStateProvider" but still catches the fork
                // server's tags (BinderSender, ShizukuService, ...).
                collect(
                    "logcat",
                    "shell:logcat -d -t 3000 -v brief 2>/dev/null | " +
                        "grep -aiE 'shizuku|BinderSender|sendBinder|manager package|" +
                        "app_process|E AndroidRuntime|FATAL EXCEPTION|avc: denied' | " +
                        "tail -300"
                )
                collect(
                    "processes",
                    "shell:ps -A -o USER,PID,PPID,NAME,ARGS | " +
                        "grep -E 'shizuku_server|ShizukuService|app_process' | grep -v grep || true"
                )
                // Decisive for the binder-timeout case: is the detached server
                // process alive at all? Alive = server is running but the binder
                // push failed; dead = the server crashed during boot.
                collect(
                    "server-process",
                    "shell:toybox pidof shizuku_server 2>&1; " +
                        "ps -A -o USER,PID,PPID,NAME,ARGS 2>/dev/null | " +
                        "grep -w shizuku_server | grep -v grep || true"
                )
                // The pushed server publishes its binder into THIS app's
                // <package>.shizuku provider; verify the provider is actually
                // registered in the running APK (resolve-content-provider does
                // not exist on this Samsung ROM, hence dumpsys instead).
                collect(
                    "provider-package",
                    "shell:dumpsys package '${BuildConfig.APPLICATION_ID}' 2>/dev/null | " +
                        "grep -aiE 'shizuku' | head -30"
                )
                collect(
                    "providers-registry",
                    "shell:dumpsys activity providers 2>/dev/null | " +
                        "grep -aiE 'shizuku' | head -40"
                )
                collect(
                    "selinux",
                    "shell:getenforce 2>&1; dmesg 2>/dev/null | grep -i avc | tail -40 || true"
                )
            }
        } catch (e: Throwable) {
            output.append("\n[diagnostic reconnect failed: ${e.javaClass.simpleName}: ${e.message}]")
        }
        return output.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun isPairingInvalid(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val text = "${current.javaClass.simpleName} ${current.message.orEmpty()}".lowercase()
            if (
                "unauthorized" in text ||
                "unauthenticated" in text ||
                "authentication failed" in text ||
                "pairing failed" in text ||
                "invalid key" in text ||
                "tls alert" in text
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Pushes the pinned fork APK into $REMOTE_DIR when its remote SHA-256 differs
     * from the pinned asset hash.
     */
    private fun ensureRemotePayloadChecked(
        client: AdbClient,
        context: Context,
        log: ((String) -> Unit)?
    ): String {
        client.command("shell:mkdir -p '$REMOTE_DIR'")
        val expectedSha = BuildConfig.SHIZUKU_APK_SHA256
        val remoteSha = remoteSha256(client, REMOTE_APK_PATH)

        if (remoteSha.equals(expectedSha, ignoreCase = true)) {
            log?.invoke("Serveur déjà à jour sur l'appareil.")
            return ensureRemoteStarter(client, context, log)
        }

        log?.invoke("Transfert du serveur embarqué (~3,6 Mo)…")
        context.assets.open(BuildConfig.SHIZUKU_ASSET_PATH).use { input ->
            // adb sync service push (the SEND/DATA/DONE exchange `adb push`
            // uses). shell:cat was torn down by adbd mid-write on this device,
            // truncating the file; the sync service keeps no shell in the path
            // and reports a real OKAY/FAIL status.
            client.syncSend("$REMOTE_APK_PATH.tmp", 0x1A4 /* 0644 */, input)
        }
        client.command("shell:mv '$REMOTE_APK_PATH.tmp' '$REMOTE_APK_PATH' && chmod 644 '$REMOTE_APK_PATH'")

        // A corrupt transfer makes the server die silently at dex-load time
        // (the fork's starter redirects the child stderr to /dev/null), so the
        // payload must be verified BEFORE the first launch, not just skipped.
        val transferredSha = remoteSha256(client, REMOTE_APK_PATH)
        if (!transferredSha.equals(expectedSha, ignoreCase = true)) {
            val details = "attendu=${expectedSha.take(12)}… " +
                "obtenu=${transferredSha?.take(12) ?: "indisponible"}…"
            NtfyReporter.publish("runtime", "apk transfer hash mismatch: $details", "high")
            throw AdbException("Transfert du serveur corrompu : $details")
        }
        log?.invoke("Transfert vérifié (SHA-256).")

        // /data/app/.../libshizuku.so is readable by the app but not reliably
        // executable by the shell UID. Copy the starter beside the server APK.
        return ensureRemoteStarter(client, context, log)
    }

    /** Reads the first 64-hex SHA-256 from `sha256sum` output, or null. */
    private fun remoteSha256(client: AdbClient, path: String): String? {
        return runCatching {
            val out = StringBuilder()
            client.command("shell:sha256sum '$path' 2>/dev/null") { bytes ->
                out.append(String(bytes))
            }
            out.toString().trim().split(" ", "\n").firstOrNull { it.length == 64 }
        }.onFailure {
            Log.d(TAG, "remote hash probe failed for $path: ${it.message}")
        }.getOrNull()
    }

    private fun ensureRemoteStarter(
        client: AdbClient,
        context: Context,
        log: ((String) -> Unit)?
    ): String {
        // 1. Try the standard nativeLibraryDir path (works when AGP packages the .so correctly).
        var localStarter = File(context.applicationInfo.nativeLibraryDir, "libshizuku.so")
        // 2. Fallback: extract libshizuku.so from the embedded server APK asset.
        //    This handles builds where AGP doesn't pick up the generated jniLibs.
        if (!localStarter.isFile) {
            log?.invoke("Starter absent du nativeLib → extraction depuis l'asset embarqué…")
            localStarter = extractStarterFromServerAsset(context)
        }
        check(localStarter.isFile) { "Embedded Shizuku starter is missing: not in nativeLibraryDir and could not extract from server asset" }
        val expectedSha = sha256(localStarter)
        val shaPrefix = expectedSha.take(12)
        /*
         * Stop the per-launch delete-and-repush of the starter.
         *
         * This ROM's adbd tears bulk write-streams down mid-transfer
         * (observed as "remote closed during payload write (8192 bytes sent)"
         * on shell:cat and "remote closed during sync payload write (0 bytes
         * sent)" on the sync service), so re-crossing that channel on EVERY
         * startup made most launches die before the server was even started.
         * The APK already gets a SHA check before any upload; give the starter
         * the same treatment: keep the verified file on the device and reuse it.
         */
        runCatching {
            // Kill stale starter processes (which would hold an executing file
            // open and cause ETXTBSY on the next exec), but DO NOT remove the
            // installed files here.
            client.command(
                "shell:pkill -f '[s]hizuku-starter-[0-9a-f]+-' || true; sleep 1"
            )
        }
        findMatchingRemoteStarter(client, shaPrefix, expectedSha)?.let { existing ->
            // Cheap sanity pass on the reused file: mode may have been lost
            // (adb push defaults, fs quirks); re-chmod is idempotent and safe.
            val ready = runCatching {
                val out = StringBuilder()
                client.command(
                    "shell:toybox chmod 0755 '$existing' 2>/dev/null; " +
                        "if toybox test -x '$existing'; then echo EVER_STARTER_REUSE_OK; fi"
                ) { bytes -> out.append(String(bytes)) }
                out.toString().contains("EVER_STARTER_REUSE_OK")
            }.getOrDefault(false)
            if (ready) {
                log?.invoke("Starter déjà installé et vérifié : réutilisation.")
                NtfyReporter.publish("runtime", "reusing verified on-device starter (no transfer)")
                return existing
            }
            log?.invoke("Starter présent mais non exécutable : réinstallation…")
        }
        /*
         * Install path: every install gets its own remote filename.
         * Android can report ETXTBSY ("Text file busy") when a second startup
         * races with an upload or an old detached starter. A hash alone is not
         * enough because a failed hash probe can cause the same file to be
         * overwritten, so every launch gets its own remote filename.
         */
        runCatching {
            client.command(
                "shell:rm -f '$REMOTE_DIR'/shizuku-starter-* " +
                    "'$REMOTE_DIR'/shizuku-starter-*.tmp 2>/dev/null || true"
            )
        }
        val launchId = System.nanoTime().toString(16)
        val remoteStarterPath = "$REMOTE_DIR/shizuku-starter-${expectedSha.take(12)}-$launchId"
        val remoteStarterTempPath = "$remoteStarterPath.tmp"

        log?.invoke("Transfert du starter Shizuku…")
        localStarter.inputStream().use { input ->
            // adb sync service push: adbd itself creates the temp file, so the
            // old shell:cat stdin pipe (which adbd tore down mid-write on this
            // device) is gone. 0644 is enough for the later `cp`, which creates
            // a fresh executable inode and avoids ETXTBSY.
            client.syncSend(remoteStarterTempPath, 0x1A4 /* 0644 */, input)
        }

        fun remoteOutput(command: String): String {
            val output = StringBuilder()
            client.command(command) { bytes -> output.append(String(bytes)) }
            return output.toString().trim()
        }

        val expectedSize = localStarter.length()

        // ls -l also prints the link count, so the byte count is read separately
        // with `wc -c < file` (bare number on stdin, no filename noise).
        fun remoteByteCount(path: String): Long? =
            remoteOutput("shell:toybox wc -c < '$path' 2>&1")
                .trim()
                .split(" ", "\n")
                .firstOrNull { it.toLongOrNull() != null }
                ?.toLong()

        val tempCheck = remoteOutput("shell:toybox ls -l '$remoteStarterTempPath' 2>&1")
        if (!tempCheck.contains(remoteStarterTempPath)) {
            val details = tempCheck.ifBlank { "fichier temporaire absent" }.takeLast(700)
            NtfyReporter.publish("runtime", "starter temp check failed: $details", "high")
            throw AdbException("Transfert du starter incomplet : $details")
        }
        if (remoteByteCount(remoteStarterTempPath) != expectedSize) {
            val details = "taille attendue=$expectedSize, obtenue=${remoteByteCount(remoteStarterTempPath) ?: "indisponible"}"
            NtfyReporter.publish("runtime", "starter temp size mismatch: $details", "high")
            throw AdbException("Transfert du starter incomplet : $details")
        }

        val copyCheck = remoteOutput(
            "shell:toybox cp '$remoteStarterTempPath' '$remoteStarterPath' 2>&1; " +
                "sync; sleep 1; toybox ls -l '$remoteStarterPath' 2>&1"
        )
        if (!copyCheck.contains(remoteStarterPath)) {
            val details = copyCheck.ifBlank { "copie distante absente" }.takeLast(700)
            NtfyReporter.publish("runtime", "starter copy failed: $details", "high")
            throw AdbException("Copie du starter échouée : $details")
        }
        if (remoteByteCount(remoteStarterPath) != expectedSize) {
            val details = "taille attendue=$expectedSize, obtenue=${remoteByteCount(remoteStarterPath) ?: "indisponible"}"
            NtfyReporter.publish("runtime", "starter copy size mismatch: $details", "high")
            throw AdbException("Copie du starter échouée : $details")
        }

        val modeCheck = remoteOutput(
            "shell:toybox chmod 0755 '$remoteStarterPath' 2>&1; " +
                "sync; toybox stat -c '%A %a %U:%G %n' '$remoteStarterPath' 2>&1; " +
                "if toybox test -x '$remoteStarterPath'; then " +
                "echo EVER_STARTER_INSTALL_OK; else " +
                "echo EVER_STARTER_INSTALL_FAILED; fi"
        )
        if (!modeCheck.contains("EVER_STARTER_INSTALL_OK")) {
            val details = modeCheck.ifBlank { "permission exécutable absente" }.takeLast(700)
            NtfyReporter.publish("runtime", "starter chmod failed: $details", "high")
            throw AdbException("Permission du starter échouée : $details")
        }
        remoteOutput("shell:toybox rm -f '$remoteStarterTempPath' 2>&1")
        NtfyReporter.publish("runtime", "starter install: ${modeCheck.take(300)}")
        log?.invoke("Starter transféré.")
        return remoteStarterPath
    }

    /**
     * Returns the path of an already-pushed starter whose SHA-256 matches the
     * current build, or null when none exists. Hash-versioned filenames are
     * shizuku-starter-<sha12>-<launchId>, so a glob on the prefix finds every
     * candidate and `sha256sum` (multi-file) filters by real content.
     */
    private fun findMatchingRemoteStarter(
        client: AdbClient,
        shaPrefix: String,
        expectedSha: String
    ): String? {
        val output = StringBuilder()
        runCatching {
            client.command(
                "shell:toybox sha256sum '$REMOTE_DIR'/shizuku-starter-$shaPrefix-* 2>/dev/null || true"
            ) { bytes -> output.append(String(bytes)) }
        }
        for (line in output.toString().lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.length < 66) continue
            val hash = trimmed.take(64)
            if (!hash.equals(expectedSha, ignoreCase = true)) continue
            val path = trimmed.substring(64).trim()
            if (path.startsWith("$REMOTE_DIR/shizuku-starter-$shaPrefix-")) {
                return path
            }
        }
        return null
    }

    /**
     * Extracts libshizuku.so from the embedded Shizuku server APK asset.
     * The server APK (pushed to the device) contains the same native starter.
     * We extract it to a cache file so we can push it via ADB.
     */
    private fun extractStarterFromServerAsset(context: Context): File {
        val cacheFile = File(context.cacheDir, "shizuku-starter-extracted.so")
        if (cacheFile.isFile && cacheFile.length() > 0) return cacheFile
        try {
            context.assets.open(BuildConfig.SHIZUKU_ASSET_PATH).use { apkStream ->
                ZipInputStream(apkStream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory &&
                            entry.name.matches(Regex("lib/.*/libshizuku\\.so"))
                        ) {
                            Log.i(TAG, "Extracting ${entry.name} from server asset")
                            cacheFile.outputStream().use { out -> zip.copyTo(out) }
                            cacheFile.setExecutable(true)
                            return cacheFile
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract starter from server asset", e)
        }
        return cacheFile
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            var read = input.read(buffer)
            while (read != -1) {
                if (read > 0) digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun waitForBinder(timeoutMillis: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMillis) {
            if (ShizukuConnectionManager.isAvailable()) return true
            delay(500)
        }
        return false
    }
}
