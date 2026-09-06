package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME

/**
 * Small embedded replacement for Shizuku Manager's permission confirmation screen.
 *
 * The embedded server derives its manager package from the directory containing the
 * pushed server APK, so it sends this activity action to this application package.
 * Without this activity, Shizuku.requestPermission() succeeds only as a request
 * submission: no UI is shown and the client remains unauthorized forever.
 */
class ShizukuPermissionActivity : Activity() {

    private var resultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestUid = intent.getIntExtra(EXTRA_UID, -1)
        val requestPid = intent.getIntExtra(EXTRA_PID, -1)
        val requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)
        @Suppress("DEPRECATION")
        val applicationInfo = intent.getParcelableExtra<ApplicationInfo>(EXTRA_APPLICATION_INFO)

        if (requestUid < 0 || requestPid < 0 || requestCode < 0 || applicationInfo == null) {
            finish()
            return
        }

        val label = runCatching {
            applicationInfo.loadLabel(packageManager).toString()
        }.getOrDefault(applicationInfo.packageName)

        AlertDialog.Builder(this)
            .setTitle("Autoriser le moteur système")
            .setMessage(
                "$label demande l'autorisation d'utiliser le moteur privilégié " +
                    "embarqué pour détecter et enregistrer les appels."
            )
            .setCancelable(false)
            .setNegativeButton("Refuser") { _, _ ->
                dispatchResult(requestUid, requestPid, requestCode, allowed = false, oneTime = true)
            }
            .setPositiveButton("Autoriser") { _, _ ->
                dispatchResult(requestUid, requestPid, requestCode, allowed = true, oneTime = false)
            }
            .setOnCancelListener {
                dispatchResult(requestUid, requestPid, requestCode, allowed = false, oneTime = true)
            }
            .show()
    }

    private fun dispatchResult(uid: Int, pid: Int, requestCode: Int, allowed: Boolean, oneTime: Boolean) {
        if (resultSent) return
        resultSent = true
        runCatching {
            Bundle().apply {
                putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
                putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, oneTime)
            }.also { data ->
                Shizuku.dispatchPermissionConfirmationResult(uid, pid, requestCode, data)
            }
        }.onFailure {
            Log.e(TAG, "Unable to dispatch embedded Shizuku permission result", it)
        }
        finish()
    }

    private companion object {
        const val TAG = "ShizukuPermissionActivity"
        const val EXTRA_UID = "uid"
        const val EXTRA_PID = "pid"
        const val EXTRA_REQUEST_CODE = "requestCode"
        const val EXTRA_APPLICATION_INFO = "applicationInfo"
    }
}