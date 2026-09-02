package com.coolappstore.everdialer.by.svhp.controller.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.BlockedNumberContract

/**
 * Single source of truth for Ever Dialer's number-blocking feature.
 *
 * The blocked list itself still lives in [PreferenceManager.KEY_BLOCKED_CONTACTS] (a simple
 * comma-separated string) exactly like before — that's what the Settings → Blocked Numbers UI
 * reads/writes, and it's what [com.coolappstore.everdialer.by.svhp.controller.CallService] checks
 * to disconnect a ringing call. Every screen that can block/unblock a number (Settings, the Calls
 * tab context menu, the Contacts tab context menu) now goes through this object instead of poking
 * that preference directly, so all three always agree on what's blocked — thanks to
 * [PreferenceManager.settingsChanged] firing on every write, any screen showing a "Block"/"Unblock"
 * state updates immediately no matter where the change came from.
 *
 * On top of the app-level list, whenever Ever Dialer currently holds the default Phone/Dialer
 * role, every block/unblock is mirrored into Android's system-wide BlockedNumberContract provider.
 * That's what actually wires the feature into the OS: once a number is in that provider, Android's
 * telephony stack itself silently rejects calls from it — and any other app that honours the
 * system block list (e.g. Messages) picks it up too — instead of the number only being hung up
 * after CallService already let it start ringing inside this app.
 */
object BlockedNumbersManager {

    private fun normalize(number: String) = number.replace(" ", "").replace("-", "").trim()

    /** Current blocked list, in the order numbers were added. */
    fun getBlockedList(prefs: PreferenceManager): List<String> =
        prefs.getString(PreferenceManager.KEY_BLOCKED_CONTACTS, "")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    /** Same loose suffix match CallService uses, so UI state always agrees with what actually
     *  gets disconnected regardless of how the number is formatted (spaces, dashes, country code). */
    fun isBlocked(prefs: PreferenceManager, number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        val target = normalize(number)
        if (target.isEmpty()) return false
        return getBlockedList(prefs).any { blocked ->
            val cb = normalize(blocked)
            cb.isNotEmpty() && (target.endsWith(cb) || cb.endsWith(target))
        }
    }

    /** True when the OS will actually let this app read/write the system blocked-number list —
     *  in practice, only while Ever Dialer holds the default Phone/Dialer role. */
    private fun canUseSystemBlockList(context: Context): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            BlockedNumberContract.canCurrentUserBlockNumbers(context)
    } catch (_: Exception) {
        false
    }

    fun block(context: Context, prefs: PreferenceManager, number: String) {
        val trimmed = number.trim()
        if (trimmed.isBlank()) return
        val current = getBlockedList(prefs)
        if (current.none { normalize(it) == normalize(trimmed) }) {
            prefs.setString(PreferenceManager.KEY_BLOCKED_CONTACTS, (current + trimmed).joinToString(","))
        }
        syncToSystem(context, trimmed, block = true)
    }

    fun unblock(context: Context, prefs: PreferenceManager, number: String) {
        val current = getBlockedList(prefs)
        val updated = current.filterNot { normalize(it) == normalize(number) }
        if (updated.size != current.size) {
            prefs.setString(PreferenceManager.KEY_BLOCKED_CONTACTS, updated.joinToString(","))
        }
        syncToSystem(context, number, block = false)
    }

    fun toggle(context: Context, prefs: PreferenceManager, number: String) {
        if (isBlocked(prefs, number)) unblock(context, prefs, number) else block(context, prefs, number)
    }

    private fun syncToSystem(context: Context, number: String, block: Boolean) {
        if (!canUseSystemBlockList(context)) return
        try {
            if (block) {
                val values = ContentValues().apply {
                    put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
                }
                context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
            } else {
                context.contentResolver.delete(
                    BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER} = ?",
                    arrayOf(number)
                )
            }
        } catch (_: Exception) {
            // Best-effort. The app-level list above is still the source of truth CallService
            // checks on every ringing call, so blocking keeps working even if the OS write is
            // denied (e.g. Ever Dialer isn't the default dialer right now).
        }
    }
}
