/*
 * Ever Dialer+ — privileged runtime (Phase 2).
 * Compatibility receiver: the existing recorder pipeline asks for the Shizuku
 * server via the standard "moe.shizuku.privileged.api.START" broadcast aimed at
 * the package declaring moe.shizuku.manager.permission.API_V23 — which is now
 * THIS app. We intercept it and run our own embedded startup instead of
 * delegating to an external Shizuku manager.
 */
package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PrivilegedBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_START_SERVER -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        PrivilegedRuntime.ensureServerStarted(appContext)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_STOP_SERVER -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        PrivilegedRuntime.stopServer(appContext)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_START_SERVER = "moe.shizuku.privileged.api.START"
        const val ACTION_STOP_SERVER = "moe.shizuku.privileged.api.STOP"
    }
}
