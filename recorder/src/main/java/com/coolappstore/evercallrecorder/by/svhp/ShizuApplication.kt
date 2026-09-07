/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp

import android.app.Application
import com.coolappstore.evercallrecorder.by.svhp.onboarding.OnboardingSession
import com.coolappstore.evercallrecorder.by.svhp.privileged.PrivilegedRuntime
import com.coolappstore.evercallrecorder.by.svhp.utils.AppLogger

/**
 * ShizuApplication is run when the app process is created. Can be seen as the very first entry point of the app.
 */
open class ShizuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        // A previous APK may have stored an ADB key accepted by an older
        // runtime build. Mark the remote runtime stale before the UI or
        // watchdog can reuse its binder. The key remains available so the
        // next startup can clean and replace the old runtime.
        runCatching { PrivilegedRuntime.invalidateStalePairing(applicationContext) }
        runCatching { PrivilegedRuntime.refreshState(applicationContext) }
        // Fresh process => forget any previous "Skip" tap on the permissions screen.
        OnboardingSession.skipped = false
        // Make sure the telephony receiver / notification listener are enabled or disabled to
        // match the universal call recording switch, in case they drifted (e.g. after an update).
        com.coolappstore.evercallrecorder.by.svhp.services.call.CallRecordingComponentGuard.sync(applicationContext)
    }
}