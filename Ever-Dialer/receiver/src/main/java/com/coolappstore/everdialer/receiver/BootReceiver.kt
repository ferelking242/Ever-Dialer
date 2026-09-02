/*
 * Ever Call Recording (phone B) — restarts the reception service after boot
 * when pairing is already active, so nothing is missed while the app is closed.
 */
package com.coolappstore.everdialer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.coolappstore.everdialer.by.svhp.sync.SyncStore

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (SyncStore.isEnabled(context)) ReceiveService.start(context)
    }
}
