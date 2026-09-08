/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 * Copyright (C) 2026-present kitsumed (Med)
 * This software is licensed under the GNU General Public License v3 or later.
 */

package com.coolappstore.evercallrecorder.by.svhp.services.call

import java.text.Normalizer
import java.util.Locale

/**
 * Platform-independent classification of a notification posted by a supported VoIP app.
 *
 * Android and OEM notification adapters do not expose one stable call marker:
 * [Notification.CATEGORY_CALL], FLAG_ONGOING_EVENT, clearability, CallStyle template,
 * visible text and action buttons vary across WhatsApp/Telegram versions. Keeping the
 * decision here makes those rules auditable and prevents NotificationListenerService
 * lifecycle code from silently becoming a second classifier.
 */
data class AppCallNotificationSnapshot(
    val category: String?,
    val template: String?,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val texts: List<String>,
    val actionTitles: List<String>
)

data class AppCallNotificationDecision(
    val accepted: Boolean,
    val reason: String,
    val evidence: List<String> = emptyList()
)

object AppCallNotificationClassifier {
    private const val CATEGORY_CALL = "call"

    private val callWords = listOf(
        "call", "calling", "voice call", "video call", "incoming call",
        "outgoing call", "call in progress",
        "appel", "appel vocal", "appel video", "appel en cours",
        "llamada", "videollamada",
        "arama", "sesli arama", "goruntulu arama", "gelen arama",
        "cikis aramasi", "arama devam ediyor"
    )

    private val callActionWords = listOf(
        "answer", "accept", "decline", "reject", "hang up", "end call",
        "repondre", "refuser", "raccrocher", "accepter",
        "yanitla", "reddet", "kapat"
    )

    fun classify(snapshot: AppCallNotificationSnapshot): AppCallNotificationDecision {
        val normalizedTexts = snapshot.texts.map(::normalize).filter(String::isNotBlank)
        val normalizedActions = snapshot.actionTitles.map(::normalize).filter(String::isNotBlank)
        val hasCallCategory = snapshot.category.equals(CATEGORY_CALL, ignoreCase = true)
        val hasCallStyle = snapshot.template?.let { normalize(it).contains("callstyle") } == true
        val hasCallText = containsAny(normalizedTexts, callWords)
        val hasCallAction = containsAny(normalizedActions, callActionWords)
        val hasLifecycleSignal = snapshot.isOngoing || !snapshot.isClearable

        val evidence = buildList {
            if (snapshot.isOngoing) add("ongoing")
            if (!snapshot.isClearable) add("not_clearable")
            if (hasCallCategory) add("category_call")
            if (hasCallStyle) add("call_style")
            if (hasCallText) add("call_text")
            if (hasCallAction) add("call_action")
        }

        return when {
            hasLifecycleSignal && hasCallCategory ->
                AppCallNotificationDecision(true, "call_category_with_lifecycle", evidence)

            hasLifecycleSignal && hasCallStyle ->
                AppCallNotificationDecision(true, "call_style_with_lifecycle", evidence)

            hasCallText && (hasLifecycleSignal || hasCallAction) ->
                AppCallNotificationDecision(true, "call_text_with_supporting_signal", evidence)

            hasCallAction && hasLifecycleSignal ->
                AppCallNotificationDecision(true, "call_action_with_lifecycle", evidence)

            !hasLifecycleSignal ->
                AppCallNotificationDecision(false, "no_ongoing_or_persistent_signal", evidence)

            else ->
                AppCallNotificationDecision(false, "no_call_evidence", evidence)
        }
    }

    private fun containsAny(values: List<String>, needles: List<String>): Boolean =
        values.any { value -> needles.any(value::contains) }

    /**
     * Removes accents before matching so French, Spanish and Turkish notifications
     * remain recognizable without duplicating every accented spelling.
     */
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("\\s+".toRegex(), " ")
            .trim()
}