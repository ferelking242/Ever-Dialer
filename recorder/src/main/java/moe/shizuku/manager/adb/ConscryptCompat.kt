/*
 * Ever Dialer+ — TLS compatibility for wireless ADB pairing.
 *
 * The Android platform Conscrypt implementation is hidden or incomplete on
 * some devices. The pairing protocol needs TLS exporter support, so use the
 * bundled public Conscrypt provider when available and retain platform
 * fallbacks for older installations.
 */
package moe.shizuku.manager.adb

import android.util.Log
import org.conscrypt.Conscrypt
import java.lang.reflect.Method
import java.security.Provider
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

internal object ConscryptCompat {
    private const val TAG = "ConscryptCompat"

    private val methodParameterTypes = arrayOf<Class<*>>(
        String::class.java,
        ByteArray::class.java,
        Int::class.javaPrimitiveType!!
    )

    private val bundledProvider: Provider? by lazy {
        runCatching {
            val provider = Conscrypt.newProvider()
            if (Security.getProvider(provider.name) == null) {
                Security.insertProviderAt(provider, 1)
            }
            Log.i(TAG, "Bundled Conscrypt provider ready: ${provider.name}")
            provider
        }.onFailure {
            Log.e(TAG, "Bundled Conscrypt provider unavailable: ${it.message}", it)
        }.getOrNull()
    }

    private val publicSessionMethod: Method? by lazy {
        runCatching {
            SSLSession::class.java.getMethod(
                "exportKeyingMaterial",
                *methodParameterTypes
            )
        }.onSuccess {
            Log.i(TAG, "Using public SSLSession.exportKeyingMaterial API")
        }.onFailure {
            Log.d(TAG, "Public SSLSession keying-material API unavailable: ${it.message}")
        }.getOrNull()
    }

    private val platformConscryptMethod: Method? by lazy {
        runCatching {
            val clazz = Class.forName("com.android.org.conscrypt.Conscrypt")
            Log.d(TAG, "Platform Conscrypt class loaded: ${clazz.classLoader}")
            clazz.getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                *methodParameterTypes
            )
        }.onSuccess {
            Log.i(TAG, "Using platform Conscrypt.exportKeyingMaterial fallback")
        }.onFailure {
            Log.d(TAG, "Platform Conscrypt fallback unavailable: ${it.message}")
        }.getOrNull()
    }

    /**
     * Creates the TLS context used by both ADB connections and the pairing
     * connection. Explicit provider selection ensures the bundled Conscrypt
     * socket is the one passed to Conscrypt.exportKeyingMaterial below.
     */
    fun newSslContext(protocol: String): SSLContext {
        val provider = bundledProvider
        return if (provider != null) {
            SSLContext.getInstance(protocol, provider)
        } else {
            SSLContext.getInstance(protocol)
        }
    }

    /**
     * Mirrors Conscrypt.exportKeyingMaterial(SSLSocket, String, byte[], int).
     * Bundled Conscrypt is preferred because hidden-API reflection can fail
     * even after the platform TLS handshake succeeds.
     */
    fun exportKeyingMaterial(
        socket: SSLSocket,
        label: String,
        context: ByteArray?,
        length: Int
    ): ByteArray {
        Log.d(TAG, "Exporting TLS keying material: label='${label.replace("\u0000", "\\0")}', length=$length")

        if (bundledProvider != null) {
            try {
                return requireByteArray(
                    Conscrypt.exportKeyingMaterial(socket, label, context, length),
                    "bundled Conscrypt.exportKeyingMaterial"
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Bundled Conscrypt export failed: ${e.message}")
            }
        }

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

        platformConscryptMethod?.let { method ->
            try {
                return requireByteArray(
                    method.invoke(null, socket, label, context, length),
                    "Conscrypt.exportKeyingMaterial"
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Platform Conscrypt export failed: ${e.message}")
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