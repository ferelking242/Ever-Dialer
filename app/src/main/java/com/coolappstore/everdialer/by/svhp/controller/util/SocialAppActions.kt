package com.coolappstore.everdialer.by.svhp.controller.util

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat

// ── WhatsApp / Telegram / Google Meet "contact through" quick actions ──────
// Shared between the Contact Info "Social" card and the Dialpad's long-press menu, so both
// surfaces use the exact same install-detection, icon-loading, and launch behavior.

val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
const val OFFICIAL_TELEGRAM_PACKAGE = "org.telegram.messenger"
const val GOOGLE_MEET_PACKAGE = "com.google.android.apps.tachyon"

fun isAnyPackageInstalled(context: Context, packages: Set<String>): Boolean =
    packages.any { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) { false }
    }

private fun isPackageInstalled(context: Context, pkg: String): Boolean =
    try { context.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

fun drawableToImageBitmap(drawable: android.graphics.drawable.Drawable): ImageBitmap {
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}

/** Loads the real launcher icon of whichever WhatsApp variant is installed (WhatsApp or WhatsApp
 *  Business), so quick-action UI can show the actual app icon instead of a generic chat glyph. */
fun getWhatsAppIcon(context: Context): ImageBitmap? {
    val pkg = WHATSAPP_PACKAGES.firstOrNull { isPackageInstalled(context, it) } ?: return null
    return try { drawableToImageBitmap(context.packageManager.getApplicationIcon(pkg)) } catch (_: Exception) { null }
}

/** Telegram has many third-party clients/forks. Rather than guessing a package name, this
 *  resolves the exact same "tg://resolve" intent used to actually open the chat, and loads the
 *  icon of whichever app is registered to handle it — so the icon shown always matches an app
 *  that will really open. If the official Telegram app is one of the installed handlers, its icon
 *  is used; otherwise the first genuine handler is used. Android's own "Open with…" chooser
 *  (which has its own generic share icon and isn't a real Telegram client) is always excluded. */
fun getTelegramIcon(context: Context): ImageBitmap? {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=0"))
    val packageManager = context.packageManager
    val handlers = try {
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    } catch (_: Exception) { emptyList() }
        .mapNotNull { it.activityInfo?.packageName }
        .filterNot { it == "android" || it.startsWith("com.android.internal") || it == context.packageName }
        .distinct()

    val chosenPackage = handlers.firstOrNull { it == OFFICIAL_TELEGRAM_PACKAGE } ?: handlers.firstOrNull() ?: return null
    return try { drawableToImageBitmap(packageManager.getApplicationIcon(chosenPackage)) } catch (_: Exception) { null }
}

/** True if at least one Telegram-capable app (official app or a fork) is installed, using the
 *  same tg:// resolution Telegram chat/call actions rely on. */
fun isTelegramInstalled(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=0"))
    val handlers = try {
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    } catch (_: Exception) { emptyList() }
        .mapNotNull { it.activityInfo?.packageName }
        .filterNot { it == "android" || it.startsWith("com.android.internal") || it == context.packageName }
    return handlers.isNotEmpty()
}

/** True if Google Meet is installed on the device. */
fun isGoogleMeetInstalled(context: Context): Boolean = isPackageInstalled(context, GOOGLE_MEET_PACKAGE)

/** Loads Google Meet's real launcher icon if it's installed. */
fun getGoogleMeetIcon(context: Context): ImageBitmap? {
    return try {
        context.packageManager.getPackageInfo(GOOGLE_MEET_PACKAGE, 0)
        drawableToImageBitmap(context.packageManager.getApplicationIcon(GOOGLE_MEET_PACKAGE))
    } catch (_: Exception) { null }
}

/** Best-effort device region (e.g. "IN", "US"), preferring the live network over the SIM's home
 *  country and finally the locale, so numbers typed without a country code can still be resolved
 *  to a full international number. */
private fun getDeviceCountryIso(context: Context): String? {
    return try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: context.resources.configuration.locales[0]?.country?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }?.uppercase()
}

/** Turns a locally-typed number (no leading "+") into a full E.164 international number using
 *  the device's region, e.g. "9807654321" -> "+919807654321" for a device in India. This must
 *  run through Android's own formatter rather than any manual digit-stripping: naive "drop a
 *  leading 0" heuristics corrupt numbers where a 0 legitimately appears elsewhere (e.g.
 *  "9807654321"), and WhatsApp's own web-link handler rejects/mangles numbers that aren't
 *  already in full international form. Falls back to the plain digits (old behavior) if E.164
 *  conversion isn't possible, so a number is still passed through rather than blocked entirely. */
private fun toInternationalNumber(context: Context, phoneNumber: String): String {
    val trimmed = phoneNumber.trim()
    if (trimmed.startsWith("+")) return trimmed.filter { it.isDigit() || it == '+' }

    val digitsOnly = trimmed.filter { it.isDigit() }
    if (digitsOnly.isEmpty()) return digitsOnly

    val countryIso = getDeviceCountryIso(context)
    if (countryIso != null) {
        val e164 = try { PhoneNumberUtils.formatNumberToE164(digitsOnly, countryIso) } catch (_: Exception) { null }
        if (!e164.isNullOrBlank()) return e164
    }
    return digitsOnly
}

/** Opens a WhatsApp chat with [phoneNumber]. Returns false (does nothing) if WhatsApp isn't installed. */
fun openWhatsAppChat(context: Context, phoneNumber: String): Boolean {
    if (!isAnyPackageInstalled(context, WHATSAPP_PACKAGES)) return false
    val clean = toInternationalNumber(context, phoneNumber)
    if (clean.isEmpty()) return false
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$clean")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) { false }
}

/** Opens a Telegram chat with [phoneNumber] via Android's own app chooser, so the user can pick
 *  whichever Telegram client/fork they have installed rather than the request being forced into
 *  one specific app. Returns false (does nothing) if no Telegram-capable app is installed. */
fun openTelegramChat(context: Context, phoneNumber: String): Boolean {
    val clean = phoneNumber.filter { it.isDigit() || it == '+' }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=$clean"))
    val handlers = try { context.packageManager.queryIntentActivities(intent, 0) } catch (_: Exception) { emptyList() }
    if (handlers.isEmpty()) return false
    return try {
        context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }
}

/** Opens the Google Meet app without targeting any particular contact. Returns false if it isn't installed. */
fun openGoogleMeetApp(context: Context): Boolean {
    val launchIntent = try { context.packageManager.getLaunchIntentForPackage(GOOGLE_MEET_PACKAGE) } catch (_: Exception) { null }
        ?: return false
    return try {
        context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }
}

/**
 * Actually places a Google Meet video call to [phoneNumber] (Meet, formerly Duo, natively
 * supports calling a phone number the same way a regular VoIP call app does). Falls back to just
 * opening the Meet app if the CALL_PHONE permission hasn't been granted or the direct-call intent
 * can't be resolved, so the user still lands somewhere useful instead of nothing happening.
 * Returns false only if Google Meet isn't installed at all.
 */
fun startGoogleMeetCall(context: Context, phoneNumber: String): Boolean {
    if (!isPackageInstalled(context, GOOGLE_MEET_PACKAGE)) return false
    val clean = phoneNumber.filter { it.isDigit() || it == '+' }
    if (clean.isEmpty()) return openGoogleMeetApp(context)

    val hasCallPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    return try {
        val intent = Intent(if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_VIEW, Uri.parse("tel:$clean")).apply {
            setPackage(GOOGLE_MEET_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        openGoogleMeetApp(context)
    }
}

private const val MIME_MEET_VIDEO_CALL = "vnd.android.cursor.item/com.google.android.apps.tachyon.phone"
private const val MIME_MEET_AUDIO_CALL = "vnd.android.cursor.item/com.google.android.apps.tachyon.phone.audio"

/**
 * Looks up the ContactsContract.Data row Google Meet itself registers for a synced contact —
 * the same mechanism the stock Contacts app uses to show "Voice call" / "Video call" through Meet
 * as native quick actions, so firing ACTION_VIEW on it starts a real Meet call directly instead of
 * only opening the app. Mirrors [findWhatsAppCallDataUri].
 */
private fun findGoogleMeetCallDataUri(context: Context, phoneNumber: String, mimeType: String): Uri? {
    if (!isGoogleMeetInstalled(context)) return null
    if (!hasReadContactsPermission(context)) return null
    val digits = phoneNumber.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    return try {
        val cr = context.contentResolver
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(mimeType),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val dataId = cursor.getLong(0)
                val contactId = cursor.getLong(1)
                if (contactHasMatchingNumber(cr, contactId, digits)) {
                    return ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId)
                }
            }
            null
        }
    } catch (_: Exception) { null }
}

/** Starts a real Google Meet voice call to [phoneNumber] if this contact is Meet-synced on the
 *  device. Falls back to [startGoogleMeetCall] (which still places a real Meet call, just not
 *  necessarily voice-only) when no direct-call shortcut is registered for this contact. Returns
 *  false only if Google Meet isn't installed at all. */
fun startGoogleMeetVoiceCall(context: Context, phoneNumber: String): Boolean {
    if (!isGoogleMeetInstalled(context)) return false
    val uri = findGoogleMeetCallDataUri(context, phoneNumber, MIME_MEET_AUDIO_CALL)
    if (uri != null) {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { startGoogleMeetCall(context, phoneNumber) }
    }
    return startGoogleMeetCall(context, phoneNumber)
}

/** Starts a real Google Meet video call to [phoneNumber] if this contact is Meet-synced on the
 *  device. Falls back to [startGoogleMeetCall] when no direct-call shortcut is registered for this
 *  contact. Returns false only if Google Meet isn't installed at all. */
fun startGoogleMeetVideoCall(context: Context, phoneNumber: String): Boolean {
    if (!isGoogleMeetInstalled(context)) return false
    val uri = findGoogleMeetCallDataUri(context, phoneNumber, MIME_MEET_VIDEO_CALL)
    if (uri != null) {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { startGoogleMeetCall(context, phoneNumber) }
    }
    return startGoogleMeetCall(context, phoneNumber)
}

private fun hasReadContactsPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/** Returns true if the raw contact behind Android Contacts row [contactId] has a saved phone
 *  number matching [digits] (via the same lenient suffix-matching rule used elsewhere in the app). */
private fun contactHasMatchingNumber(cr: ContentResolver, contactId: Long, digits: String): Boolean {
    return try {
        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val num = c.getString(0) ?: continue
                if (numbersLikelyMatch(num, digits)) return true
            }
            false
        } ?: false
    } catch (_: Exception) { false }
}

/**
 * Looks up the ContactsContract.Data row WhatsApp itself registers for a synced contact — this is
 * the same mechanism the stock Android Contacts/Dialer apps use to show "WhatsApp Voice call" /
 * "WhatsApp Video call" as native quick actions, so firing ACTION_VIEW on it starts a real
 * WhatsApp call directly instead of only opening the chat.
 */
private fun findWhatsAppCallDataUri(context: Context, phoneNumber: String, mimeType: String): Uri? {
    if (!isAnyPackageInstalled(context, WHATSAPP_PACKAGES)) return null
    if (!hasReadContactsPermission(context)) return null
    val digits = phoneNumber.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    return try {
        val cr = context.contentResolver
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(mimeType),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val dataId = cursor.getLong(0)
                val contactId = cursor.getLong(1)
                if (contactHasMatchingNumber(cr, contactId, digits)) {
                    return ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId)
                }
            }
            null
        }
    } catch (_: Exception) { null }
}

private const val MIME_WHATSAPP_VOICE_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
private const val MIME_WHATSAPP_VIDEO_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"

/** Starts a real WhatsApp voice call to [phoneNumber] if this contact is WhatsApp-synced on the
 *  device. Falls back to opening the WhatsApp chat (so the user can tap Call themselves) when the
 *  direct-call shortcut isn't available. Returns false only if WhatsApp isn't installed at all. */
fun startWhatsAppVoiceCall(context: Context, phoneNumber: String): Boolean {
    if (!isAnyPackageInstalled(context, WHATSAPP_PACKAGES)) return false
    val uri = findWhatsAppCallDataUri(context, phoneNumber, MIME_WHATSAPP_VOICE_CALL)
    if (uri != null) {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { openWhatsAppChat(context, phoneNumber) }
    }
    return openWhatsAppChat(context, phoneNumber)
}

/** Starts a real WhatsApp video call to [phoneNumber] if this contact is WhatsApp-synced on the
 *  device. Falls back to opening the WhatsApp chat (so the user can tap Video Call themselves)
 *  when the direct-call shortcut isn't available. Returns false only if WhatsApp isn't installed. */
fun startWhatsAppVideoCall(context: Context, phoneNumber: String): Boolean {
    if (!isAnyPackageInstalled(context, WHATSAPP_PACKAGES)) return false
    val uri = findWhatsAppCallDataUri(context, phoneNumber, MIME_WHATSAPP_VIDEO_CALL)
    if (uri != null) {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { openWhatsAppChat(context, phoneNumber) }
    }
    return openWhatsAppChat(context, phoneNumber)
}

/**
 * Looks up a ContactsContract.Data row that a Telegram-capable app has registered for a synced
 * contact as a direct call action — the same mechanism findWhatsAppCallDataUri (above) uses for
 * WhatsApp. Unlike WhatsApp, Telegram (and its forks) don't share one single fixed MIME type
 * across versions/builds, so instead of an exact-string match this scans for any Data row whose
 * MIMETYPE merely mentions both "telegram" and "call" — still specific enough to avoid false
 * positives, but resilient to the exact suffix varying. [wantVideo] prefers a row whose MIMETYPE
 * also mentions "video" when one exists, falling back to whatever call-shaped row is found.
 */
private fun findTelegramCallDataUri(context: Context, phoneNumber: String, wantVideo: Boolean): Uri? {
    if (!isTelegramInstalled(context)) return null
    if (!hasReadContactsPermission(context)) return null
    val digits = phoneNumber.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    return try {
        val cr = context.contentResolver
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
            val contactIdIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            var fallbackUri: Uri? = null
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIdx)?.lowercase() ?: continue
                if (!mime.contains("telegram") || !mime.contains("call")) continue
                val contactId = cursor.getLong(contactIdIdx)
                if (!contactHasMatchingNumber(cr, contactId, digits)) continue
                val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, cursor.getLong(idIdx))
                if (mime.contains("video") == wantVideo) return uri
                if (fallbackUri == null) fallbackUri = uri
            }
            fallbackUri
        }
    } catch (_: Exception) { null }
}

/** Starts a real Telegram voice call to [phoneNumber] when this contact is Telegram-synced on the
 *  device with a registered call action (see findTelegramCallDataUri). Falls back to opening the
 *  Telegram chat (so the user can tap Call themselves) when no direct-call action is available —
 *  this is the common case, since most Telegram installs don't register one. Returns false only
 *  if no Telegram-capable app is installed. */
fun startTelegramVoiceCall(context: Context, phoneNumber: String): Boolean {
    if (!isTelegramInstalled(context)) return false
    val uri = findTelegramCallDataUri(context, phoneNumber, wantVideo = false)
    if (uri != null) {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { openTelegramChat(context, phoneNumber) }
    }
    return openTelegramChat(context, phoneNumber)
}

/** Starts a real Telegram video call to [phoneNumber] when this contact is Telegram-synced on the
 *  device with a registered call action (see findTelegramCallDataUri). Falls back to opening the
 *  Telegram chat (so the user can tap Video Call themselves) when no direct-call action is
 *  available. Returns false only if no Telegram-capable app is installed. */
fun startTelegramVideoCall(context: Context, phoneNumber: String): Boolean {
    if (!isTelegramInstalled(context)) return false
    val uri = findTelegramCallDataUri(context, phoneNumber, wantVideo = true)
    if (uri != null) {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { openTelegramChat(context, phoneNumber) }
    }
    return openTelegramChat(context, phoneNumber)
}
