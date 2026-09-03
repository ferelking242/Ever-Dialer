/*
 * Vendored from thedjchi/Shizuku (fork of RikkaApps/Shizuku), manager module.
 * License: GPL-3.0 — same license as Ever Dialer. Provenance: master branch, 2026-08.
 *
 * Local adaptations:
 *  - moe.shizuku.manager.ktx.logd replaced with android.util.Log
 *  - rikka.core.util.BuildUtils replaced with Build.VERSION check
 *  - added syncSend() to push a payload onto the device through the adb
 *    `sync:` service (the same SEND/DATA/DONE/QUIT protocol as `adb push`).
 *    It replaces the earlier commandWithStdin() which streamed bytes into a
 *    `shell:cat > file` pipeline: adbd tore that stdin pipe down mid-write on
 *    some ROMs (observed A_CLSE after ~8 KiB on Samsung), truncating the file
 *    or failing the push silently. The sync service is the transport facility
 *    adbd itself provides for file transfer, so no shell sits in the path and
 *    the device answers with a real OKAY/FAIL status.
 */
package moe.shizuku.manager.adb

import android.os.Build
import android.util.Log
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CLSE
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_OKAY
import moe.shizuku.manager.adb.AdbProtocol.A_OPEN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"

class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {
    companion object {
        private const val PAYLOAD_READ_TIMEOUT_MS = 30_000

        /*
         * adb sync messages ("SEND", "DATA", "DONE", "QUIT", "OKAY", "FAIL")
         * are carried inside WRTE packets. Keep each WRTE well under the size
         * modern adbd advertises (256 KiB) while bounding the buffering on a
         * wireless link; DATA payloads are SYNC_WRTE_MAX - 8 bytes.
         */
        private const val SYNC_WRTE_MAX = 16 * 1024

        private fun syncId(text: String): Int {
            require(text.length == 4)
            return text[0].code or
                (text[1].code shl 8) or
                (text[2].code shl 16) or
                (text[3].code shl 24)
        }
    }

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false
    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect() {
        socket = Socket()
        val address = InetSocketAddress(host, port)
        socket.connect(address, 5000)
        socket.soTimeout = 20_000
        socket.tcpNoDelay = true

        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()

        if (message.command == A_STLS) {
            if (Build.VERSION.SDK_INT < 29) {
                error("Connect to adb with TLS is not supported before Android 9")
            }

            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext

            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)

            useTls = true

            message = read()
        } else if (message.command == A_AUTH) {
            if (message.command != A_AUTH && message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")

            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)

                message = read()
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN")
    }

    fun command(cmd: String, listener: ((ByteArray) -> Unit)? = null) {
        val localId = newLocalId()
        write(A_OPEN, localId, 0, cmd)

        val first = read()
        when (first.command) {
            A_OKAY -> drainStream(localId, first.arg0, listener, cmd)
            A_CLSE -> {
                // adbd refused the stream outright (e.g. shell service unavailable).
                write(A_CLSE, localId, first.arg0)
                throw AdbException("stream refused by adbd for: $cmd")
            }
            else -> throw AdbException("expected A_OKAY after A_OPEN for: $cmd, got ${first.toStringShort()}")
        }
    }

    /**
     * Pushes the whole [payload] stream to [remotePath] on the device using the
     * adb `sync:` service — the exact SEND/DATA/DONE/QUIT exchange `adb push`
     * performs. adbd applies [mode] (octal, e.g. 0x1ED -> 0755) when it creates
     * the remote file and answers with a real sync status: "OKAY" on success or
     * "FAIL<reason>" on error.
     *
     * Every WRTE waits for its transport OKAY before the next one, so a stalled
     * wireless link is detected instead of overflowing the device.
     */
    fun syncSend(remotePath: String, mode: Int, payload: InputStream, listener: ((ByteArray) -> Unit)? = null) {
        val localId = newLocalId()
        write(A_OPEN, localId, 0, "sync:")

        val first = read()
        when (first.command) {
            A_CLSE -> {
                write(A_CLSE, localId, first.arg0)
                throw AdbException("sync service refused by adbd")
            }
            A_OKAY -> Unit
            else -> throw AdbException("expected A_OKAY after A_OPEN sync:, got ${first.toStringShort()}")
        }
        val remoteId = first.arg0

        val previousTimeout = socket.soTimeout
        try {
            socket.soTimeout = PAYLOAD_READ_TIMEOUT_MS

            // SEND <length> <remotePath>,<octal mode> — adbd opens the file here
            // and may answer FAIL immediately if the directory is missing.
            val modeText = mode.toString(8).padStart(4, '0')
            writeSyncPacket(localId, remoteId, syncMessage("SEND", "$remotePath,$modeText"))

            // DATA <length> <bytes>…
            val data = ByteArray(SYNC_WRTE_MAX - 8)
            while (true) {
                val n = payload.read(data)
                if (n <= 0) break
                writeSyncPacket(localId, remoteId, syncMessage("DATA", data.copyOfRange(0, n)))
            }

            // DONE <mtime u32=0> — after this adbd flushes the file and replies
            // with a sync status WRTE ("OKAY" or "FAIL<reason>").
            writeSyncPacket(localId, remoteId, syncMessage("DONE", byteArrayOf(0, 0, 0, 0)))

            // Read the sync status. Transport OKAY acks may interleave, and a
            // FAIL reason can span more than one WRTE, so collect until the
            // reply starts with a full OKAY/FAIL marker.
            val status = StringBuilder()
            var remoteClosed = false
            while (true) {
                val current = status.toString()
                if (current.startsWith("OKAY") || current.startsWith("FAIL")) break
                if (status.length > 4096) break
                val message = read()
                when (message.command) {
                    A_OKAY -> Unit // transport ack of our own WRTE — nothing to do
                    A_WRTE -> {
                        val chunk = message.data
                        if (chunk != null && chunk.isNotEmpty()) {
                            status.append(String(chunk))
                            write(A_OKAY, localId, remoteId)
                        }
                    }
                    A_CLSE -> {
                        write(A_CLSE, localId, message.arg0)
                        remoteClosed = true
                        break
                    }
                    else -> throw AdbException(
                        "protocol error while reading sync status for $remotePath: got ${message.toStringShort()}"
                    )
                }
            }

            // QUIT ends the sync session; adbd then closes the stream. Best
            // effort: if adbd already closed after a FAIL, the write fails and
            // the drain below must be skipped.
            var quitAcked = false
            if (!remoteClosed) {
                runCatching {
                    writeSyncPacket(localId, remoteId, syncMessage("QUIT"))
                    quitAcked = true
                }
            }

            if (!remoteClosed) {
                if (quitAcked) {
                    // Consume the remaining output (any trailing FAIL bytes) and
                    // the final CLSE so the connection stays clean for the next
                    // command.
                    drainStream(localId, remoteId, listener, "sync:$remotePath")
                } else {
                    // QUIT could not be acknowledged (stream already gone) —
                    // nothing left to drain.
                    remoteClosed = true
                }
            }

            val reply = status.toString()
            if (!reply.startsWith("OKAY")) {
                throw AdbException(
                    "adb push to $remotePath failed: " +
                        reply.removePrefix("FAIL").trim().take(300)
                )
            }
        } finally {
            socket.soTimeout = previousTimeout
        }
    }

    /**
     * Builds one adb sync message: 4-byte id (ASCII), little-endian payload
     * length, payload. The whole thing is sent inside a single WRTE.
     */
    private fun syncMessage(id: String, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(syncId(id))
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Sends one WRTE (up to [SYNC_WRTE_MAX] bytes) and waits for its transport
     * OKAY. A CLSE here means adbd tore the stream down mid-push. An early
     * WRTE is a sync FAIL reply that raced our acks (e.g. SEND could not open
     * the remote file); the device's reason is surfaced in the exception.
     */
    private fun writeSyncPacket(
        localId: Int,
        remoteId: Int,
        packet: ByteArray
    ) {
        var off = 0
        while (off < packet.size) {
            val len = minOf(SYNC_WRTE_MAX, packet.size - off)
            write(A_WRTE, localId, remoteId, packet.copyOfRange(off, off + len))

            val ack = read()
            when (ack.command) {
                A_OKAY -> off += len
                A_WRTE -> {
                    // The transport ack is sent before the sync layer processes
                    // the message, so a WRTE here is a sync FAIL reply racing
                    // our acks (e.g. SEND could not open the remote file).
                    // Stop the push and surface the device's own reason.
                    val chunk = ack.data
                    val reason = if (chunk != null) String(chunk) else ""
                    write(A_OKAY, localId, remoteId)
                    throw AdbException(
                        "adb push failed: ${reason.removePrefix("FAIL").trim().take(300)}"
                    )
                }
                A_CLSE -> {
                    write(A_CLSE, localId, ack.arg0)
                    throw AdbException("remote closed during sync payload write ($off bytes sent)")
                }
                else -> throw AdbException(
                    "expected A_OKAY after A_WRTE, got ${ack.toStringShort()}"
                )
            }
        }
    }

    /**
     * Reads the shell stream until the remote closes it, invoking [listener]
     * for every payload chunk. Every A_WRTE is acknowledged with A_OKAY; the
     * final A_CLSE is answered with A_CLSE. Stray A_OKAY packets are tolerated
     * (some adbd builds acknowledge the local close before the remote close)
     * instead of being misread as protocol corruption.
     */
    private fun drainStream(
        localId: Int,
        remoteId: Int,
        listener: ((ByteArray) -> Unit)?,
        cmd: String
    ) {
        while (true) {
            val message = read()
            when (message.command) {
                A_WRTE -> {
                    if (message.data_length > 0) {
                        listener?.invoke(message.data!!)
                    }
                    write(A_OKAY, localId, remoteId)
                }
                A_CLSE -> {
                    write(A_CLSE, localId, remoteId)
                    return
                }
                A_OKAY -> {
                    // Acknowledge of our own close, or of a WRTE that raced
                    // with the remote close. Nothing to do.
                }
                else -> throw AdbException(
                    "protocol error on stream '$cmd': got ${message.toStringShort()}, " +
                        "expected A_WRTE/A_CLSE"
                )
            }
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    /**
     * Stream ids must be unique per transport connection: adbd routes packets
     * by (local id, remote id) and reusing an id whose previous stream is only
     * half-closed on the remote side makes replies land on the wrong stream.
     */
    private var nextLocalId = 0

    private fun newLocalId(): Int {
        nextLocalId = nextLocalId + 1
        return nextLocalId
    }

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int

        val data: ByteArray?
        if (dataLength >= 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = null
        }

        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")

        return message
    }

    override fun close() {
        try {
            plainInputStream.close()
        } catch (e: Throwable) {
        }
        try {
            plainOutputStream.close()
        } catch (e: Throwable) {
        }
        try {
            socket.close()
        } catch (e: Exception) {
        }

        if (useTls) {
            try {
                tlsInputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsOutputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsSocket.close()
            } catch (e: Exception) {
            }
        }
    }
}
