/*
 * Ever Dialer+ — sender side (phone A). Discovers phone B over NSD (or the
 * remembered endpoint), authenticates, sends manifest + call deltas, then
 * streams every recording B is missing (64 KiB AES-GCM frames).
 */
package com.coolappstore.everdialer.by.svhp.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

object SyncClient {

    private const val TAG = "EverSync/Client"
    private const val CHUNK = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val DISCOVERY_TIMEOUT_MS = 12_000L

    /**
     * One full push cycle. Returns a short human summary on success.
     * Ends normally when the receiver closes the channel after serving us.
     */
    suspend fun runPush(context: Context): kotlin.Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            require(SyncStore.isEnabled(appContext)) { "sync désactivé" }
            require(SyncStore.role(appContext) == SyncRole.SENDER) { "cet appareil n'est pas l'expéditeur" }
            val secret = SyncStore.pairingSecret(appContext) ?: error("aucun code de jumelage importé")

            val endpoint = SyncStore.lastEndpoint(appContext)
                ?: SyncNsd.discoverOnce(appContext, DISCOVERY_TIMEOUT_MS)?.let { (host, port, _) ->
                    SyncStore.rememberEndpoint(appContext, host, port)
                    host to port
                }
                ?: error("Téléphone B introuvable sur le réseau WiFi")

            val (myId, myName) = SyncStore.identity(appContext)
            val summary = Socket().use { socket ->
                socket.soTimeout = 30_000
                socket.connect(InetSocketAddress(endpoint.first, endpoint.second), CONNECT_TIMEOUT_MS)
                val handshake = Handshakes.asClient(
                    socket, myId, myName, secret,
                    SyncStore.peerId(appContext).ifEmpty { null }
                )
                Log.i(TAG, "connecté à ${handshake.peerName}")
                session(appContext, handshake.channel)
            }
            SyncStore.markSynced(appContext, summary)
            summary
        }.onFailure { e ->
            Log.w(TAG, "push failed: ${e.message}")
            SyncStore.markFailed(context.applicationContext, e.message ?: "échec inconnu")
        }
    }

    private fun session(context: Context, channel: EncryptedChannel): String {
        val (files, calls) = SyncSource.collect?.invoke(context)
            ?: error("aucune source de données configurée sur cet appareil")
        channel.sendControl(SyncJson.encodeToString(MsgManifest(entries = files)))
        channel.sendControl(SyncJson.encodeToString(MsgCalls(entries = calls)))

        var sent = 0
        try {
            while (true) {
                val (kind, payload) = channel.receiveMessage()
                if (kind != KIND_CONTROL) continue
                when (val msg = parseControlMessage(payload)) {
                    is MsgWant -> {
                        Log.i(TAG, "${msg.names.size} fichier(s) à envoyer")
                        for (name in msg.names) {
                            pushFile(context, channel, name)
                            sent++
                        }
                    }
                    is MsgBye -> break
                    is MsgError -> throw IOException(msg.message)
                    else -> Unit
                }
            }
        } catch (_: ChannelClosed) {
            // Receiver closed after finishing — that is the normal end of a session.
        } finally {
            channel.close()
        }
        return if (sent == 0) {
            "Déjà à jour (${files.size} enregistrements connus), ${calls.size} appels"
        } else {
            "$sent nouveau(x) enregistrement(s) envoyé(s), ${calls.size} appels"
        }
    }

    private fun pushFile(context: Context, channel: EncryptedChannel, name: String) {
        val input: InputStream = SyncSource.openFile(context, name)
            ?: throw IOException("enregistrement introuvable côté A: $name")
        input.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            channel.sendControl(SyncJson.encodeToString(MsgFileStart(name = name, size = -1)))
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                channel.sendData(buffer.copyOf(read))
            }
            channel.sendControl(
                SyncJson.encodeToString(
                    MsgFileEnd(name = name, sha256 = digest.digest().joinToString("") { "%02x".format(it) })
                )
            )
            val (kind, payload) = channel.receiveMessage()
            if (kind != KIND_CONTROL) throw IOException("réponse inattendue pendant $name")
            when (val ack = parseControlMessage(payload)) {
                is MsgAck -> if (!ack.ok) throw IOException("B a refusé $name: ${ack.error}")
                is MsgError -> throw IOException(ack.message)
                else -> throw IOException("protocole inattendu pendant $name")
            }
        }
    }
}
