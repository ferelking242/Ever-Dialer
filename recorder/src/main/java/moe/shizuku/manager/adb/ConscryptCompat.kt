/*
 * Ever Dialer+ — privileged runtime (Phase 2).
 * Accesses the platform Conscrypt provider via reflection so we don't need a
 * compile-time dependency on com.android.org.conscrypt.Conscrypt (hidden API).
 */
package moe.shizuku.manager.adb

import android.util.Log
import javax.net.ssl.SSLSocket

internal object ConscryptCompat {
    private const val TAG = "ConscryptCompat"

    private val exportMethod by lazy {
        try {
            Class.forName("com.android.org.conscrypt.Conscrypt").getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (e: Throwable) {
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
        val result = exportMethod.invoke(null, socket, label, context, length)
        Log.d(TAG, "exported $length bytes of keying material")
        return result as ByteArray
    }
}
