/*
 * Ever Dialer+ — crypto helpers for the P2P sync module.
 *
 * - [SyncSecrets]: wraps sensitive values (peer pairing secret) with an
 *   AES-GCM key that never leaves AndroidKeyStore.
 * - [SessionCrypto]: HKDF-SHA256 derivation of per-direction session keys
 *   from the pairing secret + fresh handshaking nonces, and authenticated
 *   AES-GCM framing for every post-handshake byte on the wire.
 */
package com.coolappstore.everdialer.by.svhp.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SyncSecrets {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val MASTER_ALIAS = "everdial_sync_master_key"
    private const val GCM_TAG_BITS = 128

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Returns iv || ciphertext||tag, base64-encoded for storage. */
    fun protect(plain: ByteArray): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    fun unprotect(blob: String): ByteArray? = runCatching {
        val data = Base64.getDecoder().decode(blob)
        require(data.size > 12 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, data, 0, 12))
        cipher.doFinal(data, 12, data.size - 12)
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
