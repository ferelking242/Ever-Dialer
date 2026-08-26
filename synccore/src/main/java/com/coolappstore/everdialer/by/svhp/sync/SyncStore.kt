/*
 * Ever Dialer+ — persistent state of the sync module (identity, peer, toggles).
 */
package com.coolappstore.everdialer.by.svhp.sync

import android.content.Context
import android.os.Build
import java.util.UUID

enum class SyncRole { UNPAIRED, SENDER, RECEIVER }

data class SyncUiState(
    val enabled: Boolean = false,
    val role: SyncRole = SyncRole.UNPAIRED,
    val myId: String = "",
    val myName: String = "",
    val peerId: String = "",
    val peerName: String = "",
    val lastSyncAt: Long = 0,
    val lastStatus: String = "",
    val serverPort: Int = 0
)

object SyncStore {

    private const val FILE = "everdial_sync"
    private const val K_ENABLED = "enabled"
    private const val K_ROLE = "role"
    private const val K_MY_ID = "my_id"
    private const val K_MY_NAME = "my_name"
    private const val K_PEER_ID = "peer_id"
    private const val K_PEER_NAME = "peer_name"
    private const val K_PEER_SECRET = "peer_secret_enc"
    private const val K_LAST_HOST = "last_host"
    private const val K_LAST_PORT = "last_port"
    private const val K_LAST_SYNC = "last_sync_at"
    private const val K_LAST_STATUS = "last_status"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun identity(context: Context): Pair<String, String> {
        val p = prefs(context)
        var id = p.getString(K_MY_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").take(16)
            p.edit().putString(K_MY_ID, id).apply()
        }
        var name = p.getString(K_MY_NAME, null)
        if (name.isNullOrBlank()) {
            name = Build.MODEL?.take(24) ?: "Android"
            p.edit().putString(K_MY_NAME, name).apply()
        }
        return id to name
    }

    fun isEnabled(context: Context) = prefs(context).getBoolean(K_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(K_ENABLED, value).apply()

    fun role(context: Context): SyncRole =
        prefs(context).getString(K_ROLE, null)?.let { runCatching { SyncRole.valueOf(it) }.getOrNull() }
            ?: SyncRole.UNPAIRED

    fun setRole(context: Context, value: SyncRole) =
        prefs(context).edit().putString(K_ROLE, value.name).apply()

    /**
     * Called on the RECEIVER when it generates its own pairing code.
     * Stores nothing about the peer yet (learned on first successful session).
     */
    fun ownPairingSecret(context: Context, secretB64: String) =
        prefs(context).edit()
            .putString(K_PEER_SECRET, SyncSecrets.protect(secretB64.toByteArray(Charsets.UTF_8)))
            .apply()

    /** Own secret (receiver) or the peer secret imported from a pairing code (sender), UTF-8 base64. */
    fun pairingSecret(context: Context): String? =
        prefs(context).getString(K_PEER_SECRET, null)
            ?.let { SyncSecrets.unprotect(it) }
            ?.let { String(it, Charsets.UTF_8) }

    /** Called on the SENDER after importing the receiver's pairing payload. */
    fun importPeer(context: Context, payload: PairingPayload) {
        prefs(context).edit()
            .putString(K_PEER_ID, payload.id)
            .putString(K_PEER_NAME, payload.name)
            .putString(K_PEER_SECRET, SyncSecrets.protect(payload.secret.toByteArray(Charsets.UTF_8)))
            .putString(K_ROLE, SyncRole.SENDER.name)
            .apply()
    }

    /** Learned/refreshed on the RECEIVER after the first authenticated session. */
    fun rememberPeer(context: Context, id: String, name: String) =
        prefs(context).edit().putString(K_PEER_ID, id).putString(K_PEER_NAME, name).apply()

    fun peerId(context: Context) = prefs(context).getString(K_PEER_ID, null).orEmpty()
    fun peerName(context: Context) = prefs(context).getString(K_PEER_NAME, null).orEmpty()

    fun rememberEndpoint(context: Context, host: String, port: Int) =
        prefs(context).edit().putString(K_LAST_HOST, host).putInt(K_LAST_PORT, port).apply()

    fun lastEndpoint(context: Context): Pair<String, Int>? {
        val host = prefs(context).getString(K_LAST_HOST, null) ?: return null
        val port = prefs(context).getInt(K_LAST_PORT, -1)
        return if (port > 0) host to port else null
    }

    fun markSynced(context: Context, status: String) =
        prefs(context).edit().putLong(K_LAST_SYNC, System.currentTimeMillis())
            .putString(K_LAST_STATUS, status.take(200)).apply()

    fun markFailed(context: Context, status: String) =
        prefs(context).edit().putString(K_LAST_STATUS, status.take(200)).apply()

    fun lastSyncAt(context: Context) = prefs(context).getLong(K_LAST_SYNC, 0)
    fun lastStatus(context: Context) = prefs(context).getString(K_LAST_STATUS, "").orEmpty()
}
