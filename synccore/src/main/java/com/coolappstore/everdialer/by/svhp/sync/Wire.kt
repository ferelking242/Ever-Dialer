/*
 * Ever Dialer+ — low-level wire format for the sync protocol.
 *
 * Every message is length-prefixed. The 3-message handshake is cleartext
 * (identity + fresh nonces); everything after it is AES-GCM sealed with keys
 * derived from the pairing secret, so an attacker without the secret cannot
 * read or inject a single frame. Possession of the secret IS the auth.
 */
package com.coolappstore.everdialer.by.svhp.sync

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import javax.crypto.spec.SecretKeySpec

internal const val KIND_CONTROL: Byte = 0
internal const val KIND_DATA: Byte = 1

internal class Wire(private val input: DataInputStream, private val output: DataOutputStream) : Closeable {

    fun sendRaw(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun receiveRaw(): String {
        val len = input.readInt()
        require(len in 1..(1 shl 20)) { "bad raw frame length $len" }
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun sendFrame(frame: ByteArray) {
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
    }

    fun receiveFrame(maxSize: Int = 16 * 1024 * 1024): ByteArray {
        val len = input.readInt()
        require(len in 1..maxSize) { "bad frame length $len" }
        val frame = ByteArray(len)
        input.readFully(frame)
        return frame
    }

    override fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
    }
}

internal class ChannelClosed(message: String) : Exception(message)

internal class EncryptedChannel(
    private val wire: Wire,
    private val sendKey: SecretKeySpec,
    private val recvKey: SecretKeySpec
) : Closeable {

    fun sendControl(json: String) = send(KIND_CONTROL, json.toByteArray(Charsets.UTF_8))

    /** Returns (kind, payload); kind ∈ {KIND_CONTROL, KIND_DATA}. Blocks. */
    fun receiveMessage(): Pair<Byte, ByteArray> {
        val plain = try {
            SessionCrypto.open(recvKey, wire.receiveFrame())
        } catch (e: Exception) {
            throw ChannelClosed(e.message ?: "channel broken")
        }
        return plain[0] to plain.copyOfRange(1, plain.size)
    }

    fun sendData(chunk: ByteArray) = send(KIND_DATA, chunk)

    private fun send(kind: Byte, plain: ByteArray) {
        val inner = byteArrayOf(kind) + plain
        wire.sendFrame(SessionCrypto.seal(sendKey, inner))
    }

    override fun close() = wire.close()
}

internal data class Handshake(
    val channel: EncryptedChannel,
    val peerId: String,
    val peerName: String
)

internal object Handshakes {

    /** Client side: opens with hello, consumes hello_ack. */
    fun asClient(socket: Socket, myId: String, myName: String, secretB64: String, expectedPeerId: String?): Handshake {
        val wire = Wire(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()))
        val clientNonce = SessionCrypto.freshNonceB64()
        wire.sendRaw(SyncJson.encodeToString(MsgHello(id = myId, name = myName, nonce = clientNonce)))

        val ack = SyncJson.decodeFromString<MsgHelloAck>(wire.receiveRaw())
        require(ack.type == "hello_ack") { "unexpected handshake reply" }
        if (!expectedPeerId.isNullOrEmpty() && ack.id != expectedPeerId) {
            throw SecurityException("paired device mismatch (${ack.id})")
        }

        val (c2s, s2c) = SessionCrypto.deriveKeys(secretB64, clientNonce, ack.nonce)
        return Handshake(EncryptedChannel(wire, c2s, s2c), ack.id, ack.name)
    }

    /** Server side: consumes hello, replies hello_ack. */
    fun asServer(socket: Socket, myId: String, myName: String, secretB64: String): Handshake {
        val wire = Wire(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()))
        val hello = SyncJson.decodeFromString<MsgHello>(wire.receiveRaw())
        require(hello.type == "hello") { "unexpected handshake opener" }

        val serverNonce = SessionCrypto.freshNonceB64()
        wire.sendRaw(SyncJson.encodeToString(MsgHelloAck(id = myId, name = myName, nonce = serverNonce)))

        // The client proves knowledge of the secret with its very next frame;
        // garbage would fail AES-GCM authentication and close the socket.
        val (c2s, s2c) = SessionCrypto.deriveKeys(secretB64, hello.nonce, serverNonce)
        return Handshake(EncryptedChannel(wire, s2c, c2s), hello.id, hello.name)
    }
}
