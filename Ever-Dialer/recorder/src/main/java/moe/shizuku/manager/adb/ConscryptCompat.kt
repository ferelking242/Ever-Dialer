/*
 * Ever Dialer+ — TLS compatibility for wireless ADB pairing.
 *
 * Upstream Shizuku uses `com.android.org.conscrypt.Conscrypt` (the platform
 * class) directly for both SSLContext creation and keying-material export.
 * On some devices the bundled Conscrypt library's native components fail to
 * load, so we try the platform Conscrypt first (always available on API 30+)
 * and fall back to the bundled library and the public SSLSession API.
 */
package moe.shizuku.manager.adb

import android.util.Log
import java.lang.reflect.Method
import java.security.Provider
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

internal object ConscryptCompat {
    private const val TAG = "ConscryptCompat"

    private val exportMethodParams = arrayOf<Class<*>>(
        String::class.java,
        ByteArray::class.java,
        Int::class.javaPrimitiveType!!
    )

    /** Platform Conscrypt class — `com.android.org.conscrypt.Conscrypt`. */
    private val platformConscryptClass: Class<*>? by lazy {
        runCatching {
            Class.forName("com.android.org.conscrypt.Conscrypt").also {
                Log.i(TAG, "Platform Conscrypt class available: $it")
            }
        }.onFailure {
            Log.w(TAG, "Platform Conscrypt class unavailable: ${it.message}")
        }.getOrNull()
    }

    /**
     * Platform Conscrypt.exportKeyingMaterial — mirrors upstream Shizuku which
     * calls `com.android.org.conscrypt.Conscrypt.exportKeyingMaterial(socket, label, ctx, len)`
     * directly as a static method.
     */
    private val platformExportMethod: Method? by lazy {
        platformConscryptClass?.let { clazz ->
            runCatching {
                clazz.getMethod(
                    "exportKeyingMaterial",
                    SSLSocket::class.java,
                    *exportMethodParams
                )
            }.onSuccess {
                Log.i(TAG, "Platform Conscrypt.exportKeyingMaterial available")
            }.onFailure {
                Log.w(TAG, "Platform exportKeyingMaterial method not found: ${it.message}")
            }.getOrNull()
        }
    }

    /** Platform Conscrypt.newProvider — used to create SSLContext with platform Conscrypt. */
    private val platformNewProviderMethod: Method? by lazy {
        platformConscryptClass?.let { clazz ->
            runCatching {
                clazz.getMethod("newProvider")
            }.onSuccess {
                Log.i(TAG, "Platform Conscrypt.newProvider available")
            }.onFailure {
                Log.w(TAG, "Platform Conscrypt.newProvider not found: ${it.message}")
            }.getOrNull()
        }
    }

    /**
     * Bundled Conscrypt provider from `org.conscrypt:conscrypt-android`.
     * Only used as fallback when the platform Conscrypt is unavailable.
     */
    private val bundledProvider: Provider? by lazy {
        runCatching {
            val clazz = Class.forName("org.conscrypt.Conscrypt")
            val newProviderMethod = clazz.getMethod("newProvider")
            val provider = newProviderMethod.invoke(null) as Provider
            if (Security.getProvider(provider.name) == null) {
                Security.insertProviderAt(provider, 1)
            }
            Log.i(TAG, "Bundled Conscrypt provider ready: ${provider.name}")
            provider
        }.onFailure {
            Log.e(TAG, "Bundled Conscrypt provider unavailable: ${it.message}")
        }.getOrNull()
    }

    /** Public SSLSession.exportKeyingMaterial — API 33+. */
    private val publicSessionMethod: Method? by lazy {
        runCatching {
            SSLSession::class.java.getMethod(
                "exportKeyingMaterial",
                *exportMethodParams
            )
        }.onSuccess {
            Log.i(TAG, "Public SSLSession.exportKeyingMaterial API available")
        }.onFailure {
            Log.d(TAG, "Public SSLSession keying-material API unavailable: ${it.message}")
        }.getOrNull()
    }

    /**
     * Creates the TLS context. Mirrors upstream Shizuku: tries the platform
     * Conscrypt provider first, then the bundled library, then the default.
     */
    fun newSslContext(protocol: String): SSLContext {
        // 1. Platform Conscrypt — always available on API 30+, matches upstream Shizuku
        platformNewProviderMethod?.let { method ->
            try {
                val provider = method.invoke(null) as Provider
                if (Security.getProvider(provider.name) == null) {
                    Security.insertProviderAt(provider, 1)
                }
                val ctx = SSLContext.getInstance(protocol, provider)
                Log.i(TAG, "SSLContext created with platform Conscrypt ($protocol)")
                return ctx
            } catch (e: Throwable) {
                Log.w(TAG, "Platform Conscrypt SSLContext failed: ${e.message}")
            }
        }

        // 2. Bundled Conscrypt
        bundledProvider?.let { provider ->
            try {
                val ctx = SSLContext.getInstance(protocol, provider)
                Log.i(TAG, "SSLContext created with bundled Conscrypt ($protocol)")
                return ctx
            } catch (e: Throwable) {
                Log.w(TAG, "Bundled Conscrypt SSLContext failed: ${e.message}")
            }
        }

        // 3. Default
        Log.w(TAG, "Using default SSLContext ($protocol)")
        return SSLContext.getInstance(protocol)
    }

    /**
     * Exports TLS keying material, mirroring upstream Shizuku's approach:
     * `com.android.org.conscrypt.Conscrypt.exportKeyingMaterial(socket, label, ctx, len)`
     *
     * Fallback order matches Shizuku's compile-time call pattern:
     * 1. Platform Conscrypt (always works on API 30+ when socket is platform TLS)
     * 2. Bundled Conscrypt (when socket was created with bundled provider)
     * 3. Public SSLSession API (API 33+)
     */
    fun exportKeyingMaterial(
        socket: SSLSocket,
        label: String,
        context: ByteArray?,
        length: Int
    ): ByteArray {
        Log.d(TAG, "Exporting TLS keying material: label='${label.replace("\u0000", "\\0")}', length=$length")

        // 1. Platform Conscrypt — upstream Shizuku's direct call path
        platformExportMethod?.let { method ->
            try {
                return requireByteArray(
                    method.invoke(null, socket, label, context, length),
                    "platform Conscrypt.exportKeyingMaterial"
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Platform Conscrypt export failed: ${e.message}")
            }
        }

        // 2. Bundled Conscrypt — may work if socket was created with bundled provider
        try {
            val clazz = Class.forName("org.conscrypt.Conscrypt")
            val method = clazz.getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                *exportMethodParams
            )
            return requireByteArray(
                method.invoke(null, socket, label, context, length),
                "bundled Conscrypt.exportKeyingMaterial"
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Bundled Conscrypt export failed: ${e.message}")
        }

        // 3. Public SSLSession API (API 33+)
        publicSessionMethod?.let { method ->
            try {
                return requireByteArray(
                    method.invoke(socket.session, label, context, length),
                    "SSLSession.exportKeyingMaterial"
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Public SSLSession export failed: ${e.message}")
            }
        }

        throw IllegalStateException(
            "No TLS keying-material export API is available on this Android build"
        )
    }

    private fun requireByteArray(value: Any?, source: String): ByteArray {
        return value as? ByteArray
            ?: throw IllegalStateException(
                "$source returned ${value?.javaClass?.name ?: "null"} instead of ByteArray"
            )
    }
}
