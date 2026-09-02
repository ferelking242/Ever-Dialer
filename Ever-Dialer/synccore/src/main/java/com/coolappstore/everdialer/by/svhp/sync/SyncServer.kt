/*
 * Ever Dialer+ — receiver side (phone B). Listens on a TCP port, advertises
 * itself through NSD, and serves exactly ONE paired sender:
 *   manifest → want-list → streamed files (verified SHA-256) → call deltas.
 */
package com.coolappstore.everdialer.by.svhp.sync

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlinx.serialization.encodeToString

object SyncServer {

    private const val TAG = "EverSync/Server"
    private const val SO_TIMEOUT_MS = 30_000

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var unregisterNsd: (() -> Unit)? = null

    val listening: Boolean get() = serverSocket != null

    @Synchronized
    fun start(context: Context) {
        if (listening) return
        val appContext = context.applicationContext
        Thread({
            runCatching { serveLoop(appContext) }
                .onFailure { Log.e(TAG, "serve loop crashed", it) }
        }, "ever-sync-server").start()
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        unregisterNsd?.invoke()
        unregisterNsd = null
    }

    private fun serveLoop(context: Context) {
        val secret = SyncStore.pairingSecret(context) ?: run {
            Log.w(TAG, "no pairing secret yet; server idle")
            return
        }
        val (myId, myName) = SyncStore.identity(context)
        val server = ServerSocket(0)
        serverSocket = server
        SyncManager.setServerPort(server.localPort)
        unregisterNsd = SyncNsd.register(context, "EverDialer-$myName", server.localPort)
        Log.i(TAG, "listening on ${server.localPort}")

        while (!server.isClosed) {
            val socket: Socket = try {
                server.accept()
            } catch (e: Exception) {
                break // closed by stop()
            }
            runCatching { handleConnection(context, socket, myId, myName, secret) }
                .onFailure { Log.w(TAG, "session failed: ${it.message}") }
            runCatching { socket.close() }
        }
    }

    private fun handleConnection(context: Context, socket: Socket, myId: String, myName: String, secretB64: String) {
        socket.soTimeout = SO_TIMEOUT_MS
        val handshake = Handshakes.asServer(socket, myId, myName, secretB64)
        val channel = handshake.channel
        SyncStore.rememberPeer(context, handshake.peerId, handshake.peerName)
        Log.i(TAG, "authenticated ${handshake.peerName} (${handshake.peerId})")

        val recordingsDir = File(context.filesDir, "EverSync/recordings").apply { mkdirs() }

        var pendingName: String? = null
        var pendingPart: File? = null
        var pendingOut: FileOutputStream? = null
        var pendingDigest: MessageDigest? = null
        var pendingSize = 0L

        fun closePending() {
            runCatching { pendingOut?.close() }
            if (pendingPart != null && pendingName == null) pendingPart?.delete()
            pendingName = null; pendingPart = null; pendingOut = null
            pendingDigest = null; pendingSize = 0
        }

        try {
            while (true) {
                val (kind, payload) = channel.receiveMessage()
                if (kind == KIND_DATA) {
                    val out = pendingOut ?: error("data frame outside file_start")
                    out.write(payload)
                    pendingDigest!!.update(payload)
                    pendingSize += payload.size
                    continue
                }
                when (val msg = parseControlMessage(payload)) {
                    is MsgManifest -> {
                        val have = recordingsDir.listFiles()?.mapTo(HashSet()) { it.name } ?: HashSet()
                        val want = msg.entries.map { it.name }.filter { it !in have }
                        channel.sendControl(SyncJson.encodeToString(MsgWant(names = want)))
                        Log.i(TAG, "manifest: ${msg.entries.size} fichiers, ${want.size} manquants")
                    }
                    is MsgFileStart -> {
                        closePending()
                        val safe = sanitizeFileName(msg.name) ?: error("nom de fichier refusé: ${msg.name}")
                        pendingName = safe
                        pendingPart = File(recordingsDir, "$safe.part")
                        pendingOut = FileOutputStream(pendingPart)
                        pendingDigest = MessageDigest.getInstance("SHA-256")
                        pendingSize = 0
                    }
                    is MsgFileEnd -> {
                        val name = pendingName ?: error("file_end hors transfert")
                        pendingOut?.flush(); runCatching { pendingOut?.fd?.sync() }; pendingOut?.close(); pendingOut = null
                        val actualSha = pendingDigest!!.digest().joinToString("") { "%02x".format(it) }
                        val ok = actualSha.equals(msg.sha256, ignoreCase = true)
                        if (ok) {
                            val target = File(recordingsDir, name)
                            pendingPart!!.renameTo(target)
                            Log.i(TAG, "reçu $name ($pendingSize octets)")
                            channel.sendControl(SyncJson.encodeToString(MsgAck(name = name, ok = true)))
                        } else {
                            pendingPart!!.delete()
                            channel.sendControl(
                                SyncJson.encodeToString(MsgAck(name = name, ok = false, error = "sha256 mismatch"))
                            )
                        }
                        pendingName = null; pendingPart = null; pendingDigest = null; pendingSize = 0
                    }
                    is MsgCalls -> {
                        SyncLibrary.merge(context, msg.entries)
                        Log.i(TAG, "+${msg.entries.size} entrées de journal")
                    }
                    is MsgBye -> break
                    is MsgError -> { Log.w(TAG, "peer error: ${msg.message}"); break }
                    else -> Unit
                }
            }
            SyncStore.markSynced(context, "Données reçues de ${handshake.peerName}")
        } finally {
            closePending()
            channel.close()
        }
    }

    private fun sanitizeFileName(name: String): String? =
        name.takeIf { it.isNotEmpty() && it.length <= 200 && !it.contains('/') && !it.contains('\\') && it != "." && it != ".." }
}
