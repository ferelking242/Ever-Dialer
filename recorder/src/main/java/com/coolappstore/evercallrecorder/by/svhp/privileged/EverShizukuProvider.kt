/*
 * Ever Dialer+ — privileged runtime (Phase 2).
 *
 * The embedded Shizuku server (rikka.shizuku.server.ShizukuService, launched
 * from /data/local/tmp via app_process) publishes its binder by calling the
 * "sendBinder" method of the <applicationId>.shizuku content provider of every
 * app that requests the Shizuku API permission — this app included.
 *
 * This subclass of rikka.shizuku.ShizukuProvider adds a diagnostic breadcrumb
 * at the exact moment the binder push reaches our process, so a binder-timeout
 * failure can be distinguished from "the server never started" vs "the server
 * started but the push did not arrive". The base class does all the real work.
 */
package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.os.Bundle
import android.util.Log
import com.coolappstore.evercallrecorder.by.svhp.utils.NtfyReporter
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class EverShizukuProvider : ShizukuProvider() {

    override fun onCreate(): Boolean {
        // This app IS the manager for the embedded server: never try to
        // initialize Sui (the fork manager disables it for the same reason).
        disableAutomaticSuiInitialization()
        val ok = super.onCreate()
        NtfyReporter.publish(
            "provider",
            "host process ready; pingBinder=${Shizuku.pingBinder()} " +
                "perm=${runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1)}"
        )
        return ok
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_SEND_BINDER) {
            NtfyReporter.publish("provider", "sendBinder call received from shizuku_server")
            return try {
                val reply = super.call(method, arg, extras)
                if (Shizuku.pingBinder()) {
                    NtfyReporter.publish("provider", "binder stored; pingBinder=true")
                    PrivilegedRuntime.notifyBinderDelivered()
                } else {
                    NtfyReporter.publish(
                        "provider",
                        "sendBinder returned but pingBinder=false",
                        "high"
                    )
                }
                reply
            } catch (t: Throwable) {
                NtfyReporter.publish(
                    "provider",
                    "sendBinder failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}",
                    "high"
                )
                Log.w(TAG, "sendBinder failed", t)
                throw t
            }
        }
        return super.call(method, arg, extras)
    }

    private companion object {
        const val TAG = "EverShizukuProvider"
    }
}
