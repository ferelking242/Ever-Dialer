/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp.services.call

import android.app.Notification
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.coolappstore.evercallrecorder.by.svhp.data.AppPreferences
import com.coolappstore.evercallrecorder.by.svhp.data.recordings.RecordingDirection
import com.coolappstore.evercallrecorder.by.svhp.data.recordings.RecordingMetadata
import com.coolappstore.evercallrecorder.by.svhp.services.recording.RecordingForegroundService
import com.coolappstore.evercallrecorder.by.svhp.utils.AppLogger
import com.coolappstore.evercallrecorder.by.svhp.utils.NtfyReporter
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects ongoing voice/video calls inside WhatsApp and Telegram, and drives [RecordingForegroundService]
 * the same way [PhoneStateReceiver]/[CallSessionManager] do for normal telephony calls.
 *
 * **Why a notification listener?** Unlike telephony calls, VoIP calls placed inside a third-party app never
 * raise [android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED]; Android has no public broadcast for
 * "a call is happening inside app X". The one reliable, system-documented signal every call app is expected
 * to expose is the *ongoing call notification* — the persistent notification shown for the whole duration of
 * the call, marked with [Notification.CATEGORY_CALL] (this is also the category Android 12+'s CallStyle API
 * requires). We watch for that notification appearing/disappearing for the packages in [AppCallTarget].
 *
 * **What gets recorded?** Once a call is detected, this service forwards to [RecordingForegroundService]
 * exactly like a normal call would, except the resulting [RecordingMetadata.sourceApp] is set. That flag makes
 * [com.coolappstore.evercallrecorder.by.svhp.services.recording.AudioRecordingEngine] capture with
 * [com.coolappstore.evercallrecorder.by.svhp.integrations.scrcpy.ScrcpyAudioSource.OUTPUT] instead of the
 * telephony-only VOICE_CALL source, relayed through the same privileged Shizuku/scrcpy-server pipeline. PLAYBACK
 * and MIC-class sources were tried first and both produced silent recordings: PLAYBACK hard-excludes audio
 * tagged `USAGE_VOICE_COMMUNICATION` (how these apps tag call audio); MIC-class sources compete with the app's
 * own concurrent microphone session and get silenced by Android's privacy protections. OUTPUT is a privileged,
 * CAPTURE_AUDIO_OUTPUT-gated system mix tap — the same permission class as VOICE_CALL — so it hits neither limit.
 *
 * **Requires the user to grant "Notification access"** to this app (Settings ➜ Notification access), the same
 * system permission any notification-reading app needs. See "Record calls from apps" in Settings, which links
 * to that screen when needed.
 */
class AppCallNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "SCR:AppCallNotifListener"
        private const val CALL_NOTIFICATION_REPLACEMENT_GRACE_MS = 1_500L
    }

    /**
     * Notification key → [AppCallTarget] for every call we are currently treating as active.
     *
     * Both WhatsApp and Telegram keep updating their ongoing-call notification every second (to tick the call
     * duration shown to the user), which re-triggers [onNotificationPosted] for the *same* notification key
     * many times over a single call. We only want to start a recording session once per call, so we track
     * which keys we've already reacted to here, and only stop when that key is actually removed.
     */
    private val activeCalls = ConcurrentHashMap<String, AppCallTarget>()
    private val lastDiagnosticAt = ConcurrentHashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStop: Runnable? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLogger.d(TAG, "Notification listener connected. Scanning currently active notifications for in-progress calls...")
        NtfyReporter.publish("calls", "WhatsApp/Telegram notification listener connected")
        // Covers the case where the listener (re)binds while a call is already ongoing (e.g. app update,
        // Shizuku/Settings churn, or the system rebinding us), so we don't miss the rest of that call.
        try {
            activeNotifications?.forEach(::handlePosted)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to scan existing notifications on connect: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cancelPendingStop()
        val hadActiveCall = activeCalls.isNotEmpty()
        AppLogger.w(TAG, "Notification listener disconnected.")
        NtfyReporter.publish("calls", "WhatsApp/Telegram notification listener disconnected", "high")
        activeCalls.clear()
        if (hadActiveCall) {
            AppLogger.w(TAG, "Listener disconnected while an app call was tracked; stopping recording defensively.")
            NtfyReporter.publish("calls", "Listener disconnected during an app call; stopping recording", "high")
            sendServiceCommand(RecordingForegroundService.ACTION_STOP_RECORDING)
        }
    }

    override fun onDestroy() {
        cancelPendingStop()
        activeCalls.clear()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handlePosted(sbn)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Unexpected notification processing failure for ${sbn.packageName}", e)
            NtfyReporter.publish(
                "calls",
                "Notification processing failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}",
                "high"
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val target = activeCalls.remove(sbn.key) ?: return
        if (activeCalls.isNotEmpty()) {
            AppLogger.d(TAG, "${target.key} notification removed but another app call notification is still tracked.")
            return
        }
        scheduleStop(target, sbn.key)
    }

    // -- Private helpers

    private fun handlePosted(sbn: StatusBarNotification) {
        val target = AppCallTarget.fromPackageName(sbn.packageName) ?: return
        if (!isTargetEnabled(target)) {
            diagnosticConfiguration(target)
            return
        }
        val decision = classifyNotification(sbn)
        if (!decision.accepted) {
            diagnosticNotification(sbn, decision)
            return
        }
        cancelPendingStop()

        // putIfAbsent: if this key is already tracked, this is just the call-duration ticking the
        // notification text every second, not a new call. Ignore it to avoid sending duplicate START intents.
        if (activeCalls.putIfAbsent(sbn.key, target) != null) return

        val metadata = buildMetadata(target, sbn.notification)
        AppLogger.i(
            TAG,
            "Detected ${target.key} call (key=${sbn.key}, direction=${metadata.direction}, " +
                "reason=${decision.reason}, evidence=${decision.evidence.joinToString("+")}). Starting recording."
        )
        NtfyReporter.publish(
            "calls",
            "Detected ${target.key} call; starting recording " +
                "(${metadata.direction.name.lowercase()}, evidence=${decision.evidence.joinToString("+")})"
        )
        sendServiceCommand(RecordingForegroundService.ACTION_START_RECORDING, metadata)
    }

    private fun isTargetEnabled(target: AppCallTarget): Boolean {
        val preferences = AppPreferences(applicationContext)
        if (!preferences.isCallRecordingEnabled()) return false
        return when (target) {
            AppCallTarget.WHATSAPP -> preferences.isRecordWhatsAppCallsEnabled()
            AppCallTarget.TELEGRAM -> preferences.isRecordTelegramCallsEnabled()
        }
    }

    private fun classifyNotification(sbn: StatusBarNotification): AppCallNotificationDecision {
        val notification = sbn.notification
        return AppCallNotificationClassifier.classify(
            AppCallNotificationSnapshot(
                category = notification.category,
                template = notification.extras?.getString(Notification.EXTRA_TEMPLATE),
                isOngoing = sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
                isClearable = sbn.isClearable,
                texts = listOf(
                    notification.extras?.getCharSequence(Notification.EXTRA_TITLE),
                    notification.extras?.getCharSequence(Notification.EXTRA_TEXT),
                    notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT),
                    notification.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)
                ).filterNotNull().map(CharSequence::toString),
                actionTitles = notification.actions.orEmpty().mapNotNull { it.title?.toString() }
            )
        )
    }

    private fun diagnosticNotification(
        sbn: StatusBarNotification,
        decision: AppCallNotificationDecision
    ) {
        val now = SystemClock.elapsedRealtime()
        val key = "${sbn.packageName}:${decision.reason}"
        val previous = lastDiagnosticAt.putIfAbsent(key, now)
        if (previous != null && now - previous < 15_000L) return
        lastDiagnosticAt[key] = now
        NtfyReporter.publish(
            "calls",
            "Ignored ${sbn.packageName} notification: reason=${decision.reason} " +
                "evidence=${decision.evidence.joinToString("+")}"
        )
    }

    private fun diagnosticConfiguration(target: AppCallTarget) {
        val now = SystemClock.elapsedRealtime()
        val key = "configuration:${target.key}"
        val previous = lastDiagnosticAt.putIfAbsent(key, now)
        if (previous != null && now - previous < 30_000L) return
        lastDiagnosticAt[key] = now
        NtfyReporter.publish(
            "calls",
            "Ignored ${target.key} notification because app-call recording is disabled"
        )
    }

    /**
     * Give a VoIP app a short grace period to replace its notification key.
     * WhatsApp/OEM adapters commonly remove the ringing notification and post
     * the answered-call notification as a new StatusBarNotification. Stopping
     * immediately would split one call into a false end/start pair.
     */
    private fun scheduleStop(target: AppCallTarget, notificationKey: String) {
        cancelPendingStop()
        val stop = Runnable {
            pendingStop = null
            if (activeCalls.isNotEmpty()) return@Runnable
            AppLogger.i(TAG, "${target.key} call notification ended (key=$notificationKey). Stopping recording session.")
            NtfyReporter.publish("calls", "${target.key} call notification ended; stopping recording")
            sendServiceCommand(RecordingForegroundService.ACTION_STOP_RECORDING)
        }
        pendingStop = stop
        mainHandler.postDelayed(stop, CALL_NOTIFICATION_REPLACEMENT_GRACE_MS)
    }

    private fun cancelPendingStop() {
        pendingStop?.let(mainHandler::removeCallbacks)
        pendingStop = null
    }

    /**
     * Builds the [RecordingMetadata] for a newly detected app call.
     *
     * There is no phone number for a VoIP call, so the notification's title (usually the contact's name, as
     * shown by the messaging app itself) is stored in [RecordingMetadata.rawPhoneNumber] purely so it still
     * flows through to filenames/fallbacks. [RecordingMetadata.sourceApp] is what actually drives the special
     * PLAYBACK-capture + filename-prefix handling.
     */
    private fun buildMetadata(target: AppCallTarget, notification: Notification): RecordingMetadata {
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val body = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val callerLabel = title?.takeIf { it.isNotBlank() }

        return RecordingMetadata(
            rawPhoneNumber = callerLabel,
            direction = guessDirection(body),
            isEnriched = true, // Skip phone-number enrichment entirely; callerLabel is not a real number.
            isCrossCountry = false,
            sourceApp = getString(target.displayNameResId)
        )
    }

    /**
     * Best-effort guess at call direction from the notification body text.
     *
     * By the time a VoIP call's *ongoing* notification appears, incoming and outgoing calls usually look
     * identical (Android exposes no reliable API for the original direction of a third-party app's VoIP call),
     * so this only catches the rare case where the notification text still hints at it. It defaults to
     * [RecordingDirection.INCOMING] when ambiguous — this only affects the direction label/icon shown for the
     * recording, never whether the call gets recorded.
     */
    private fun guessDirection(notificationText: String?): RecordingDirection {
        val lower = notificationText?.lowercase().orEmpty()
        return if (lower.contains("outgoing") || lower.contains("calling")) {
            RecordingDirection.OUTGOING
        } else {
            RecordingDirection.INCOMING
        }
    }

    /** Mirrors [CallSessionManager]'s service-command helper so both pipelines drive [RecordingForegroundService] identically. */
    private fun sendServiceCommand(action: String, metadata: RecordingMetadata? = null) {
        val intent = Intent(applicationContext, RecordingForegroundService::class.java).apply {
            this.action = action
            if (metadata != null) {
                putExtra(RecordingMetadata.EXTRA_METADATA, metadata)
            }
        }
        try {
            if (action == RecordingForegroundService.ACTION_STOP_RECORDING) {
                applicationContext.startService(intent)
            } else {
                applicationContext.startForegroundService(intent)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start recording service for app call", e)
            NtfyReporter.publish(
                "calls",
                "Failed to start recording service: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}",
                "high"
            )
        }
    }
}
