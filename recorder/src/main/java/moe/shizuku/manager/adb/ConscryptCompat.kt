/*
 * Ever Dialer+ — privileged runtime (Phase 2).
 *
 * Android exposes TLS keying-material export through the public SSLSession API
 * on recent releases. Older Shizuku code called the platform Conscrypt helper
 * directly, so keep that as a fallback for devices where the public method is
 * not available.
 */
package moe.shizuku.manager.adb

import android.util.Log
import java.lang.reflect.Method
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

internal object ConscryptCompat {
    private const val TAG = "ConscryptCompat"

    private val methodParameterTypes = arrayOf<Class<*>>(
        String::class.java,
        ByteArray::class.java,
        Int::class.javaPrimitiveType!!
    )

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
     * Mirrors Conscrypt.exportKeyingMaterial(SSLSocket, String, byte[], int).
     * The public SSLSession API is preferred because hidden-API reflection on
     * com.android.org.conscrypt is blocked on some Android builds.
     */
    fun exportKeyingMaterial(
        socket: SSLSocket,
        label: String,
        context: ByteArray?,
        length: Int
    ): ByteArray {
        Log.d(TAG, "Exporting TLS keying material: label='${label.replace("\u0000", "\\0")}', length=$length")

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