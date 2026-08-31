/*
 * Ever Dialer+ — privileged runtime (Phase 2).
 *
 * Embeds the Shizuku server startup inside this single app, using a local
 * wireless-debugging connection (Android 11+ pairing, LADB/Shizuku-manager style):
 *   1. SPAKE2p pairing against adbd on localhost (vendored moe.shizuku.manager.adb stack)
 *   2. Push of the pinned thedjchi/Shizuku fork APK into /data/local/tmp/.everdialer/
 *   3. Launch of the vendored libshizuku.so starter:
 *        <nativeLibraryDir>/libshizuku.so --apk=/data/local/tmp/.everdialer/shizuku-server.apk
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private const val KEY_LAST_PORT = "last_connect_port"
    private const val KEY_WATCHDOG_ENABLED = "watchdog_enabled"

    /** Remote working directory; writable AND executable by the shell uid. */
    private const val REMOTE_DIR = "/data/local/tmp/.everdialer"
    private const val REMOTE_APK_PATH = "$REMOTE_DIR/shizuku-server.apk"
    private const val REMOTE_STARTER_PATH = "$REMOTE_DIR/shizuku-starter"

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state

    private var startingJobCount = 0

    private fun initialState(): State =
        if (ShizukuConnectionManager.isAvailable()) State.RUNNING else State.NOT_PAIRED

    fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPaired(context: Context): Boolean =
        runCatching {
            PreferenceAdbKeyStore(prefs(context)).get() != null
        }.getOrDefault(false)

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
     * Opens the same wireless-debugging flow as the Shizuku manager.
     *
     * @return false when the embedded Shizuku binder is already active.
     */
    fun openManagement(context: Context): Boolean {
        if (isConnected()) return false

        val appContext = context.applicationContext
        // Launch our embedded PairingActivity — it auto-starts the server
        // after successful pairing and guides the user through Dev Settings.
        runCatching {
            appContext.startActivity(
                Intent(appContext, PairingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        }
        // Fallback: open Dev Settings directly if PairingActivity can't launch.
        runCatching { PairingNotifier.showWaitingNotification(appContext) }
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            appContext.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return true
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
                require(code.isNotBlank()) { "empty pairing code" }
                NtfyReporter.publish("pairing", "starting host=${host.ifBlank { "127.0.0.1" }} port=$port")
                val key = adbKey(context)
                val client = AdbPairingClient(host.ifBlank { "127.0.0.1" }, port, code.trim(), key)
                val ok = client.use { it.start() }
                if (ok) {
                    Log.i(TAG, "Pairing succeeded")
                    NtfyReporter.publish("pairing", "SPAKE2+ handshake succeeded")
                    _state.value = State.PAIRED_IDLE
                    PairingNotifier.onPairingSucceeded(context)
                    Result.success(Unit)
                } else {
                    Log.w(TAG, "Pairing failed (wrong code or stale port)")
                    NtfyReporter.publish("pairing", "handshake rejected: invalid code or expired port", "high")
                    Result.failure(AdbException("Code invalide ou port expiré"))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Pairing error", e)
                NtfyReporter.publish("pairing", "error ${e.javaClass.simpleName}: ${e.message ?: "unknown"}", "high")
                Result.failure(e)
            }
        }

    /**
     * Starts NSD discovery of the one-time `_adb-tls-pairing._tcp` service so the UI
     * can auto-fill the pairing port while the user opens the system pairing dialog.
     * Returns the AdbMdns instance; caller must call .stop() (see DisposableEffect).
     */
    fun observePairingPort(context: Context, onPort: (Int) -> Unit): AdbMdns? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val mdns = AdbMdns(context.applicationContext, AdbMdns.TLS_PAIRING) { (_, port) ->
                if (port > 0) onPort(port)
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        startingJobCount++
        try {
            val appContext = context.applicationContext
            if (ShizukuConnectionManager.isAvailable()) {
                log?.invoke("Serveur déjà actif ✔")
                _state.value = State.RUNNING
                return@withContext Result.success(Unit)
            }
            if (!isPaired(appContext)) {
                _state.value = State.NOT_PAIRED
                return@withContext Result.failure(AdbException("Appareil non apparié"))
            }

            _state.value = State.STARTING
            log?.invoke("Recherche du port du débogage sans fil…")
            NtfyReporter.publish("runtime", "starting embedded Shizuku server")

            val port = resolveConnectPort(appContext, log)
                ?: run {
                    _state.value = State.FAILED
                    val msg = "Port introuvable. Active « Débogage sans fil » puis réessaie."
                    log?.invoke(msg)
                    return@withContext Result.failure(AdbException(msg))
                }
            log?.invoke("Connexion à adbd sur 127.0.0.1:$port…")

            val key = adbKey(appContext)
            var connected = false
            for (attempt in 1..3) {
                try {
                    AdbClient("127.0.0.1", port, key).use { client ->
                        client.connect()
                        connected = true
                        prefs(appContext).edit().putInt(KEY_LAST_PORT, port).apply()

                        ensureRemotePayloadChecked(client, appContext, log)

                        log?.invoke("Lancement du serveur Shizuku embarqué…")
                        val launchCmd = "shell:mkdir -p $REMOTE_DIR && nohup setsid " +
                            "'$REMOTE_STARTER_PATH' --apk='$REMOTE_APK_PATH'" +
                            " </dev/null >/dev/null 2>&1 & echo ever-started"
                        client.command(launchCmd)
                    }
                    break
                } catch (e: Throwable) {
                    if (attempt == 3 || e is AdbException) {
                        throw e
                    }
                    log?.invoke("Tentative $attempt échouée, nouvel essai… (${e.message})")
                    delay(1200L)
                }
            }
            check(connected) { "connexion impossible" }

            log?.invoke("En attente du binder Shizuku (≤60 s)…")
            val up = waitForBinder(timeoutMillis = 60_000)
            if (!up) {
                _state.value = State.FAILED
                val msg = "Le serveur ne répond pas. Re-appaire l'appareil ou vérifie le débogage sans fil."
                log?.invoke(msg)
                return@withContext Result.failure(AdbException(msg))
            }

            _state.value = State.RUNNING
            log?.invoke("Privilèges système actifs ✔")
            NtfyReporter.publish("runtime", "embedded Shizuku binder is running")

            // Start the embedded watchdog so the server survives longer.
            EmbeddedShizukuService.start(appContext)
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.e(TAG, "ensureServerStarted failed", e)
            NtfyReporter.publish("runtime", "startup error ${e.javaClass.simpleName}: ${e.message ?: "unknown"}", "high")
            _state.value = State.FAILED
            Result.failure(e)
        } finally {
            startingJobCount--
        }
    }

    suspend fun stopServer(context: Context) = withContext(Dispatchers.IO) {
        EmbeddedShizukuService.stop(context.applicationContext)
        try {
            val appContext = context.applicationContext
            val key = adbKey(appContext)
            val port = prefs(appContext).getInt(KEY_LAST_PORT, -1)
            if (port > 0) {
                AdbClient("127.0.0.1", port, key).use { client ->
                    client.connect()
                    // Only matches our own starter/server process names.
                    client.command("shell:pkill -f shizuku-starter || true")
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

    private suspend fun resolveConnectPort(context: Context, log: ((String) -> Unit)?): Int? {
        // 1. Try the remembered port with a cheap probe.
        val last = prefs(context).getInt(KEY_LAST_PORT, -1)
        if (last > 0 && probeAdbd(last)) {
            log?.invoke("Port mémorisé $last opérationnel.")
            return last
        }
        // 2. mDNS discovery (_adb-tls-connect._tcp), bounded to ~15 s.
        log?.invoke("Découverte mDNS en cours…")
        val discovered = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val mdns = AdbMdns(context.applicationContext, AdbMdns.TLS_CONNECT) { (_, port) ->
                    if (port > 0 && cont.isActive) cont.resume(port)
                }
                cont.invokeOnCancellation { runCatching { mdns.stop() } }
                mdns.start()
            }
        }
        return discovered
    }

    private fun probeAdbd(port: Int): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1200)
            true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Pushes the pinned fork APK into $REMOTE_DIR when its remote SHA-256 differs
     * from the pinned asset hash.
     */
    private fun ensureRemotePayloadChecked(client: AdbClient, context: Context, log: ((String) -> Unit)?) {
        client.command("shell:mkdir -p '$REMOTE_DIR'")
        val expectedSha = BuildConfig.SHIZUKU_APK_SHA256
        var remoteSha: String? = null
        runCatching {
            var out = StringBuilder()
            client.command("shell:sha256sum '$REMOTE_APK_PATH' 2>/dev/null") { bytes ->
                out.append(String(bytes))
            }
            remoteSha = out.toString().trim().split(" ", "\n").firstOrNull { it.length == 64 }
        }.onFailure { Log.d(TAG, "remote hash probe failed (probably missing file)") }

        if (remoteSha.equals(expectedSha, ignoreCase = true)) {
            log?.invoke("Serveur déjà à jour sur l'appareil.")
            ensureRemoteStarter(client, context, log)
            return
        }

        log?.invoke("Transfert du serveur embarqué (~3,6 Mo)…")
        context.assets.open(BuildConfig.SHIZUKU_ASSET_PATH).use { input ->
            client.commandWithStdin("shell:cat > '$REMOTE_APK_PATH.tmp'", input)
        }
        client.command("shell:mv '$REMOTE_APK_PATH.tmp' '$REMOTE_APK_PATH' && chmod 644 '$REMOTE_APK_PATH'")
        log?.invoke("Transfert terminé.")

        // /data/app/.../libshizuku.so is readable by the app but not reliably
        // executable by the shell UID. Copy the starter beside the server APK.
        ensureRemoteStarter(client, context, log)
    }

    private fun ensureRemoteStarter(client: AdbClient, context: Context, log: ((String) -> Unit)?) {
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
        var remoteSha: String? = null
        runCatching {
            val out = StringBuilder()
            client.command("shell:sha256sum '$REMOTE_STARTER_PATH' 2>/dev/null") { bytes ->
                out.append(String(bytes))
            }
            remoteSha = out.toString().trim().split(" ", "\n").firstOrNull { it.length == 64 }
        }
        if (remoteSha.equals(expectedSha, ignoreCase = true)) {
            log?.invoke("Starter Shizuku déjà à jour.")
            return
        }

        log?.invoke("Transfert du starter Shizuku…")
        localStarter.inputStream().use { input ->
            client.commandWithStdin("shell:cat > '$REMOTE_STARTER_PATH.tmp'", input)
        }
        client.command("shell:mv '$REMOTE_STARTER_PATH.tmp' '$REMOTE_STARTER_PATH' && chmod 755 '$REMOTE_STARTER_PATH'")
        log?.invoke("Starter transféré.")
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
