/*
 * Ever Dialer+ — P2P sync protocol models and JSON codec.
 */
package com.coolappstore.everdialer.by.svhp.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Out-of-band pairing blob shown on the RECEIVER device and pasted on the SENDER. */
@Serializable
data class PairingPayload(
    val v: Int = 1,
    val id: String,
    val name: String,
    /** Device that generated this payload acts as the sync RECEIVER (phone B). */
    val role: String,
    /** 32 random bytes, base64. Shared channel secret for the AES-GCM session. */
    val secret: String
)

/** All the information kept about one phone call. */
@Serializable
data class CallMeta(
    val number: String = "",
    val contactName: String? = null,
    val direction: String,
    val date: Long,
    val durationSec: Long = 0,
    val simSlot: Int = -1,
    /** Recording file name inside the transfer, when one is attached. */
    val recording: String? = null
)

/** One audio file offered for transfer through the MANIFEST message. */
@Serializable
data class FileEntry(
    val name: String,
    val size: Long,
    val sha256: String,
    val meta: CallMeta? = null
)

// ─── Wire messages (discriminated by their "type" field) ─────────────────────

@Serializable data class MsgHello(val type: String = "hello", val id: String, val name: String, val nonce: String)
@Serializable data class MsgHelloAck(val type: String = "hello_ack", val id: String, val name: String, val nonce: String)
@Serializable data class MsgManifest(val type: String = "manifest", val entries: List<FileEntry>)
@Serializable data class MsgCalls(val type: String = "calls", val entries: List<CallMeta>)
@Serializable data class MsgWant(val type: String = "want", val names: List<String>)
@Serializable data class MsgFileStart(val type: String = "file_start", val name: String, val size: Long)
@Serializable data class MsgFileEnd(val type: String = "file_end", val name: String, val sha256: String)
@Serializable data class MsgAck(val type: String = "ack", val name: String, val ok: Boolean = true, val error: String? = null)
@Serializable data class MsgError(val type: String = "error", val message: String)
@Serializable data class MsgBye(val type: String = "bye")

val SyncJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

inline fun <reified T> decodeOrThrow(json: String): T =
    SyncJson.decodeFromString<T>(json)

/** Decodes an inbound plaintext control frame into the right message type. */
fun parseControlMessage(payload: ByteArray): Any {
    val element = SyncJson.parseToJsonElement(String(payload, Charsets.UTF_8))
    val obj = element as? JsonObject
    val type = obj?.get("type")?.let { (it as? JsonPrimitive)?.content }
    return when (type) {
        "hello" -> SyncJson.decodeFromJsonElement(MsgHello.serializer(), element)
        "hello_ack" -> SyncJson.decodeFromJsonElement(MsgHelloAck.serializer(), element)
        "manifest" -> SyncJson.decodeFromJsonElement(MsgManifest.serializer(), element)
        "calls" -> SyncJson.decodeFromJsonElement(MsgCalls.serializer(), element)
        "want" -> SyncJson.decodeFromJsonElement(MsgWant.serializer(), element)
        "file_start" -> SyncJson.decodeFromJsonElement(MsgFileStart.serializer(), element)
        "file_end" -> SyncJson.decodeFromJsonElement(MsgFileEnd.serializer(), element)
        "ack" -> SyncJson.decodeFromJsonElement(MsgAck.serializer(), element)
        "error" -> SyncJson.decodeFromJsonElement(MsgError.serializer(), element)
        "bye" -> SyncJson.decodeFromJsonElement(MsgBye.serializer(), element)
        else -> MsgError(message = "unknown message type")
    }
}
