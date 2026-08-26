/*
 * Ever Dialer+ — receiver-side local library of synced call metadata
 * (phone B's « 📞 Appels du téléphone A » section).
 */
package com.coolappstore.everdialer.by.svhp.sync

import android.content.Context
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object SyncLibrary {

    @kotlinx.serialization.Serializable
    private data class Index(val entries: List<CallMeta> = emptyList())

    private fun indexFile(context: Context) = File(context.filesDir, "EverSync/index.json")

    fun load(context: Context): List<CallMeta> = runCatching {
        SyncJson.decodeFromString<Index>(indexFile(context).readText()).entries
    }.getOrDefault(emptyList())

    /** Newest-first view, optional case-insensitive filter on number/contact. */
    fun query(context: Context, filter: String? = null): List<CallMeta> {
        val items = load(context).sortedByDescending { it.date }
        if (filter.isNullOrBlank()) return items
        val needle = filter.trim().lowercase()
        return items.filter {
            it.number.lowercase().contains(needle) ||
                (it.contactName?.lowercase()?.contains(needle) == true)
        }
    }

    fun recordingFile(context: Context, name: String?): File? {
        if (name.isNullOrBlank()) return null
        val f = File(context.filesDir, "EverSync/recordings/$name")
        return f.takeIf { it.exists() }
    }

    @Synchronized
    fun merge(context: Context, incoming: List<CallMeta>) {
        if (incoming.isEmpty()) return
        val existing = load(context)
        val keyed = existing.associateBy { "${it.date}|${it.number}|${it.direction}" }.toMutableMap()
        for (meta in incoming) {
            val key = "${meta.date}|${meta.number}|${meta.direction}"
            keyed[key] = meta
        }
        val merged = Index(keyed.values.sortedByDescending { it.date })
        val target = indexFile(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(SyncJson.encodeToString(merged))
        if (!tmp.renameTo(target)) {
            target.writeText(SyncJson.encodeToString(merged))
            tmp.delete()
        }
    }
}
