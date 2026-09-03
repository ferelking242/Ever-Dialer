/*
 * Vendored from thedjchi/Shizuku (fork of RikkaApps/Shizuku), manager module.
 * License: GPL-3.0 — same license as Ever Dialer. Provenance: master branch, 2026-08.
 *
 * Local adaptations:
 *  - moe.shizuku.manager.ktx.logd replaced with android.util.Log
 *  - rikka.core.util.BuildUtils replaced with Build.VERSION check
 *  - added commandWithStdin() to stream a payload into `shell:cat`-style
 *    commands (used to push the embedded Shizuku server APK through the
 *    paired wireless-debugging connection).
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
     * Streams [payload] into the stdin of the shell command [cmd] (e.g.
     * `sh -c 'cat > /data/local/tmp/file'`), then closes the local stream so
     * the remote command sees EOF. Output produced by the command is drained
     * via [listener] until the remote closes the stream.
     *
     * Chunks are capped at A_MAXDATA (4096), the window we advertise in our
     * CNXN packet, and every WRTE waits for its OKAY before the next one.
     */
    fun commandWithStdin(cmd: String, payload: InputStream, listener: ((ByteArray) -> Unit)? = null) {
        val localId = newLocalId()
        write(A_OPEN, localId, 0, cmd)

        val first = read()
        when (first.command) {
            A_CLSE -> {
                write(A_CLSE, localId, first.arg0)
                throw AdbException("stream refused by adbd for: $cmd")
            }
            A_OKAY -> Unit
            else -> throw AdbException("expected A_OKAY after A_OPEN for: $cmd, got ${first.toStringShort()}")
        }
        val remoteId = first.arg0

        /*
         * A multi-megabyte push (the embedded server APK) takes many round
         * trips over wireless TLS; a stalled WiFi link can easily exceed the
         * default read timeout. Raise it for the duration of the payload so a
         * slow-but-alive link is not mistaken for a dead one.
         */
        val previousTimeout = socket.soTimeout
        try {
            socket.soTimeout = PAYLOAD_READ_TIMEOUT_MS

            // Stream the payload in <=4096-byte chunks with per-message OKAY handshake.
            val buffer = ByteArray(4096)
            var sent = 0L
            while (true) {
                val n = payload.read(buffer)
                if (n <= 0) break
                var off = 0
                while (off < n) {
                    val len = minOf(4096, n - off)
                    write(A_WRTE, localId, remoteId, buffer.copyOfRange(off, off + len))
                    sent += len

                    val ack = read()
                    if (ack.command == A_OKAY) {
                        off += len
                        continue
                    }
                    if (ack.command == A_CLSE) {
                        write(A_CLSE, localId, ack.arg0)
                        throw AdbException("remote closed during payload write ($sent bytes sent): $cmd")
                    }
                    throw AdbException("expected A_OKAY after A_WRTE for: $cmd, got ${ack.toStringShort()}")
                }
            }
        } finally {
            socket.soTimeout = previousTimeout
        }

        // Close our side: adbd forwards EOF to the shell command's stdin.
        write(A_CLSE, localId, remoteId)

        // Drain the remote side before returning. Without this handshake,
        // subsequent commands can consume the old A_CLSE and fail randomly.
        drainStream(localId, remoteId, listener, cmd)
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
