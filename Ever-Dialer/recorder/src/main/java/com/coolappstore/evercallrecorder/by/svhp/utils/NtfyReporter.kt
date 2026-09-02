/*
 * Ever Call — opt-in-free diagnostic reporter for the embedded pairing flow.
 *
 * Messages are deliberately limited to technical lifecycle information,
 * redacted before leaving the device, and posted asynchronously so a network
 * failure can never block pairing or recording.
 */
package com.coolappstore.evercallrecorder.by.svhp.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

object NtfyReporter {
    private const val TAG = "NtfyReporter"
    private const val ENDPOINT = "https://ntfy.sh/ever-call"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_MESSAGE_LENGTH = 1_500
    private const val DEDUPE_WINDOW_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentMessages = LinkedHashMap<String, Long>()

    fun publish(event: String, detail: String, priority: String = "default") {
        val message = sanitize("$event: $detail")
        val dedupeKey = "$priority|$message"
        synchronized(recentMessages) {
            val now = System.currentTimeMillis()
            val previous = recentMessages[dedupeKey]
            if (previous != null && now - previous < DEDUPE_WINDOW_MS) return
            recentMessages[dedupeKey] = now
            if (recentMessages.size > 64) {
                recentMessages.entries.iterator().let { iterator ->
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        Log.i(TAG, "Queue diagnostic: priority=$priority message=$message")
        scope.launch {
            runCatching { post(message, priority) }
                .onFailure { Log.e(TAG, "Diagnostic upload crashed: ${it.message}", it) }
        }
    }

    private fun post(message: String, priority: String) {
        Log.i(TAG, "POST diagnostic to ntfy.sh/ever-call")
        val connection = runCatching {
            (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("User-Agent", "EverDialer-EmbeddedRuntime/1")
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setRequestProperty("Title", "Ever Call diagnostic")
                setRequestProperty("Priority", priority)
                setRequestProperty("Tags", "ever-call,android")
            }
        }.getOrElse {
            Log.w(TAG, "Could not create ntfy connection: ${it.message}")
            return
        }

        try {
            connection.outputStream.use { output ->
                output.write(message.toByteArray(StandardCharsets.UTF_8))
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Log.w(TAG, "ntfy rejected diagnostic: HTTP $responseCode")
            } else {
                Log.i(TAG, "ntfy diagnostic delivered: HTTP $responseCode")
            }
        } catch (error: Exception) {
            // Diagnostics must never turn a pairing/network issue into another failure.
            Log.w(TAG, "ntfy unavailable: ${error.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun sanitize(raw: String): String {
        return raw
            .replace(Regex("(?<!\\d)\\d{6}(?!\\d)"), "[PAIRING_CODE]")
            .replace(Regex("(?i)(password|secret|token|auth(?:entication)?)[=: ]+\\S+")) { match ->
                "${match.groupValues[1]}=[REDACTED]"
            }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_MESSAGE_LENGTH)
    }
}