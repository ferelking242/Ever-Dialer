/* Ever Dialer+ — privileged runtime (Phase 2).
 * Accesses the platform Conscrypt provider via reflection so we don't need a
 * compile-time dependency on com.android.org.conscrypt.Conscrypt (hidden API).
 *
 * The real Shizuku manager accesses Conscrypt directly (compile-time stubs).
 * We use reflection to avoid hidden-API stub dependency. If the reflection
 * lookup fails, the whole pairing fails — so we log aggressively.
 */package moe.shizuku.manager.adb

import android.util.Log
import javax.net.ssl.SSLSocket

internal object ConscryptCompat {
    private const val TAG = "ConscryptCompat"

    private val exportMethod by lazy {
        try {
            val clazz = Class.forName("com.android.org.conscrypt.Conscrypt")
            Log.d(TAG, "Conscrypt class loaded: ${clazz.classLoader}")
            val method = clazz.getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            Log.d(TAG, "exportKeyingMaterial method found: ${method.returnType}")
            method
        } catch (e: Throwable) {
            Log.e(TAG, "FATAL: Cannot find Conscrypt.exportKeyingMaterial — pairing will fail", e)
            throw IllegalStateException("Platform Conscrypt unavailable; cannot run SPAKE2 pairing", e)
        }
    }

    /**
     * Mirrors Conscrypt.exportKeyingMaterial(SSLSocket, String, byte[], int).
     * Used by the adb wireless-debugging pairing protocol to mix the TLS channel
     * binding into the SPAKE2 password material.
     */
    @Suppress("UNUSED_PARAMETER")
    fun exportKeyingMaterial(socket: SSLSocket, label: String, context: ByteArray?, length: Int): ByteArray {
        Log.d(TAG, "Calling exportKeyingMaterial: label='${label.replace("\u0000", "\\0")}', length=$length")
        Log.d(TAG, "Socket class: ${socket.javaClass.name}, protocol: ${socket.handshakeSession?.protocol}")

        val result = exportMethod.invoke(null, socket, label, context, length)

        Log.d(TAG, "exportKeyingMaterial returned: type=${result?.javaClass?.name}, isByteArray=${result is ByteArray}")
        return result as? ByteArray
            ?: throw IllegalStateException(
                "Conscrypt.exportKeyingMaterial returned ${result?.javaClass?.name} instead of ByteArray"
            )
    }
}
