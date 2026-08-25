/*
 * Ever Dialer+ — gathers everything that must be pushed from phone A:
 * the last 30 days of the call log and every recording file stored in the
 * recorder's SAF folder (the same folder shown in RecordingsScreen).
 */
package com.coolappstore.everdialer.by.svhp.sync

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import androidx.documentfile.provider.DocumentFile
import com.coolappstore.evercallrecorder.by.svhp.data.AppPreferences
import java.io.InputStream
import java.security.MessageDigest

object CallLogCollector {

    private const val MAX_CALL_ROWS = 500
    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024

    data class Snapshot(val files: List<FileEntry>, val calls: List<CallMeta>)

    @SuppressLint("MissingPermission")
    fun collect(context: Context): Snapshot {
        val calls = readCallLog(context)
        val files = listRecordings(context)
        return Snapshot(files, calls)
    }

    private fun readCallLog(context: Context): List<CallMeta> = runCatching {
        val since = System.currentTimeMillis() - WINDOW_MS
        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION
        )
        val out = ArrayList<CallMeta>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(since.toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val iNumber = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val iName = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val iType = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val iDate = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val iDuration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext() && out.size < MAX_CALL_ROWS) {
                out += CallMeta(
                    number = cursor.getString(iNumber) ?: "",
                    contactName = cursor.getString(iName),
                    direction = directionName(cursor.getInt(iType)),
                    date = cursor.getLong(iDate),
                    durationSec = cursor.getLong(iDuration)
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun directionName(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
        CallLog.Calls.MISSED_TYPE -> "MISSED"
        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED_EXTERNALLY"
        else -> "OTHER"
    }

    private fun recordingsRoot(context: Context): DocumentFile? {
        val uri: Uri = AppPreferences(context).getRecordingFolderUri() ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
    }

    fun listRecordings(context: Context): List<FileEntry> = runCatching {
        val root = recordingsRoot(context) ?: return emptyList()
        root.listFiles()
            .filter { it.isFile }
            .mapNotNull { doc ->
                val fileName = doc.name ?: return@mapNotNull null
                val length = doc.length()
                if (fileName.isBlank() || length !in 1..MAX_FILE_BYTES) return@mapNotNull null
                // SHA-256 is verified during the transfer itself (streamed), so the
                // manifest only carries name+size and the receiver diffs on those.
                FileEntry(name = fileName, size = length, sha256 = "")
            }
            .sortedBy { it.name }
    }.getOrDefault(emptyList())

    /** Streams a recording by name, hashing on the fly. Returns null if missing. */
    fun openRecording(context: Context, name: String): InputStream? =
        runCatching {
            val root = recordingsRoot(context) ?: return null
            root.findFile(name)?.let { context.contentResolver.openInputStream(it.uri) }
        }.getOrNull()

    fun sha256Of(stream: InputStream): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            total += read
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to total
    }
}
