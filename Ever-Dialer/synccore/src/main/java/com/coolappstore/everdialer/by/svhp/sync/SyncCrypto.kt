/*
 * Ever Dialer+ — crypto helpers for the P2P sync module.
 *
 * - [SyncSecrets]: wraps the pairing secret at rest with software AES-256-GCM
 *   stored in MODE_PRIVATE SharedPreferences. We do NOT use Android Keystore
 *   here because many OEM ROMs (HiOS/Tecno, ColorOS, etc.) reject
 *   caller-provided IVs on Android 12+ even when setRandomizedEncryptionRequired
 *   is set to false, causing "Caller-provided IV not permitted" crashes.
 *   The PSK is regenerated on each pairing so the protection level is adequate.
 *
 * - [SessionCrypto]: HKDF-SHA256 derivation of per-direction session keys
 *   from the pairing secret + fresh handshaking nonces, and authenticated
 *   AES-GCM framing for every post-handshake byte on the wire.
 */
package com.coolappstore.everdialer.by.svhp.sync

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SyncSecrets {

    private const val GCM_TAG_BITS = 128
    private const val PREFS_FILE = "everdial_sync_secrets"
    private const val PREFS_KEY = "master_key_b64"
    private const val IV_LEN = 12

    /**
     * Software AES key stored in app-private SharedPreferences.
     * Created once and reused for the lifetime of the app install.
     * Much simpler than Android Keystore and avoids OEM compatibility issues.
     */
    private fun getSoftwareKey(context: Context): SecretKey {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREFS_KEY, null)
        if (existing != null) {
            return SecretKeySpec(Base64.getDecoder().decode(existing), "AES")
        }
        val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(PREFS_KEY, Base64.getEncoder().encodeToString(keyBytes)).apply()
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts [plain] with AES-256-GCM and returns iv(12) || ciphertext || tag, base64-encoded.
     * Requires a non-null [context] for the software key.
     */
    fun protect(plain: ByteArray, context: Context): String {
        val key = getSoftwareKey(context)
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    /** Decrypts a blob produced by [protect]. Returns null on any error. */
    fun unprotect(blob: String, context: Context): ByteArray? = runCatching {
        val data = Base64.getDecoder().decode(blob)
        require(data.size > IV_LEN + GCM_TAG_BITS / 8) { "blob too short" }
        val key = getSoftwareKey(context)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, data, 0, IV_LEN))
        cipher.doFinal(data, IV_LEN, data.size - IV_LEN)
    }.getOrNull()

    fun randomB64(size: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(size).also { SecureRandom().nextBytes(it) })
}

internal object SessionCrypto {

    private const val HMAC = "HmacSHA256"

    fun freshNonceB64(): String = SyncSecrets.randomB64(16)

    /**
     * HKDF-SHA256 over (ikm = pairing secret, salt = clientNonce || serverNonce),
     * producing two independent 256-bit keys. The client always sends with the
     * first key, the server answers with the second one.
     */
    fun deriveKeys(secretB64: String, clientNonceB64: String, serverNonceB64: String): Pair<SecretKeySpec, SecretKeySpec> {
        val secret = Base64.getDecoder().decode(secretB64)
        val salt = Base64.getDecoder().decode(clientNonceB64) +
            Base64.getDecoder().decode(serverNonceB64)

        val extract = Mac.getInstance(HMAC)
        extract.init(SecretKeySpec(salt, HMAC))
        val prk = extract.doFinal(secret)

        fun expand(info: String): SecretKeySpec {
            val m = Mac.getInstance(HMAC)
            m.init(SecretKeySpec(prk, HMAC))
            m.update(info.toByteArray(Charsets.US_ASCII))
            m.update(0x01)
            return SecretKeySpec(m.doFinal(), "AES")
        }
        return expand("everdial-c2s-key") to expand("everdial-s2c-key")
    }

    private fun cipherFor(mode: Int, key: SecretKeySpec, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, iv))
        }

    /** iv(12) || ciphertext||tag */
    fun seal(key: SecretKeySpec, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ct = cipherFor(Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext)
        return iv + ct
    }

    fun open(key: SecretKeySpec, frame: ByteArray): ByteArray {
        require(frame.size > 12 + 16) { "truncated frame" }
        return cipherFor(Cipher.DECRYPT_MODE, key, frame.copyOfRange(0, 12))
            .doFinal(frame, 12, frame.size - 12)
    }
}
