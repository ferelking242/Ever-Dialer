package com.coolappstore.everdialer.by.svhp.view.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.PhoneCallback
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.coolappstore.everdialer.by.svhp.controller.util.WHATSAPP_PACKAGES
import com.coolappstore.everdialer.by.svhp.controller.util.getGoogleMeetIcon
import com.coolappstore.everdialer.by.svhp.controller.util.getTelegramIcon
import com.coolappstore.everdialer.by.svhp.controller.util.getWhatsAppIcon
import com.coolappstore.everdialer.by.svhp.controller.util.isAnyPackageInstalled
import com.coolappstore.everdialer.by.svhp.controller.util.isGoogleMeetInstalled
import com.coolappstore.everdialer.by.svhp.controller.util.isTelegramInstalled
import com.coolappstore.everdialer.by.svhp.controller.util.openTelegramChat
import com.coolappstore.everdialer.by.svhp.controller.util.openWhatsAppChat
import com.coolappstore.everdialer.by.svhp.controller.util.startGoogleMeetVideoCall
import com.coolappstore.everdialer.by.svhp.controller.util.startGoogleMeetVoiceCall
import com.coolappstore.everdialer.by.svhp.controller.util.startTelegramVideoCall
import com.coolappstore.everdialer.by.svhp.controller.util.startTelegramVoiceCall
import com.coolappstore.everdialer.by.svhp.controller.util.startWhatsAppVideoCall
import com.coolappstore.everdialer.by.svhp.controller.util.startWhatsAppVoiceCall

/**
 * Floating popup shown after tapping WhatsApp/Telegram/Google Meet (Contact Info → Social, or the
 * Dialpad's long-press menu), offering the ways to reach the person through that app. [onChat] is
 * null for apps that don't have a chat concept (Google Meet), which hides that row — matching how
 * Google's own Contacts app only offers "Voice call" / "Video call" for Meet.
 */
@Composable
fun AppQuickActionsDialog(
    appName: String,
    onChat: (() -> Unit)? = null,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
                if (onChat != null) {
                    AppQuickActionRow(icon = Icons.AutoMirrored.Filled.Chat, label = "Chat", onClick = onChat)
                }
                AppQuickActionRow(icon = Icons.Default.Call, label = "Voice Call", onClick = onVoiceCall)
                AppQuickActionRow(icon = Icons.Default.Videocam, label = "Video Call", onClick = onVideoCall)
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)
                ) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun AppQuickActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/**
 * Self-contained "Call/Chat Via" flow: an app picker (WhatsApp/Telegram, whichever are
 * installed) followed by that app's Chat/Voice Call/Video Call [AppQuickActionsDialog]. Shared by
 * every long-press context menu that offers "Call/Chat Via" (Favourites, Call Logs, Contacts, and
 * the Dialpad's own long-press menu) plus the Dialpad call button's long-press, so all of them
 * present the exact same picker and popup for a given [phoneNumber].
 *
 * [showPicker] is owned by the caller (typically toggled true from a menu item's onClick, right
 * after that menu closes itself). Once an app is chosen here, the Chat/Voice Call/Video Call
 * dialog is tracked internally and needs no further involvement from the caller.
 *
 * [showGoogleMeet] additionally lists a "Google Meet" entry below Telegram; tapping it opens the
 * same Voice Call / Video Call popup as WhatsApp/Telegram (no Chat row, since Meet has none) and
 * places a real Meet call the same way Google's own Contacts app does. [showFakeCall] additionally
 * lists a "Fake Call" entry below Google Meet, invoking [onFakeCall] on tap — off by default since
 * only the Dialpad's call button long-press opts into it.
 */
@Composable
fun CallChatViaOverlay(
    phoneNumber: String?,
    showPicker: Boolean,
    onPickerDismiss: () -> Unit,
    showGoogleMeet: Boolean = false,
    showFakeCall: Boolean = false,
    onFakeCall: (() -> Unit)? = null
) {
    if (phoneNumber.isNullOrBlank()) return
    val context = LocalContext.current
    var showAppQuickActions by remember { mutableStateOf<String?>(null) }

    if (showPicker) {
        val hasWhatsApp = remember(context) { isAnyPackageInstalled(context, WHATSAPP_PACKAGES) }
        val hasTelegram = remember(context) { isTelegramInstalled(context) }
        val hasGoogleMeet = remember(context, showGoogleMeet) { showGoogleMeet && isGoogleMeetInstalled(context) }
        RivoDropdownMenu(expanded = showPicker, onDismissRequest = onPickerDismiss) {
            if (hasWhatsApp) {
                RivoDropdownMenuItem(
                    text = "WhatsApp",
                    iconBitmap = remember(context) { getWhatsAppIcon(context) },
                    onClick = { onPickerDismiss(); showAppQuickActions = "whatsapp" }
                )
            }
            if (hasTelegram) {
                RivoDropdownMenuItem(
                    text = "Telegram",
                    iconBitmap = remember(context) { getTelegramIcon(context) },
                    onClick = { onPickerDismiss(); showAppQuickActions = "telegram" }
                )
            }
            if (hasGoogleMeet) {
                RivoDropdownMenuItem(
                    text = "Google Meet",
                    icon = Icons.Default.VideoCall,
                    iconBitmap = remember(context) { getGoogleMeetIcon(context) },
                    onClick = { onPickerDismiss(); showAppQuickActions = "googlemeet" }
                )
            }
            if (showFakeCall && onFakeCall != null) {
                RivoDropdownMenuItem(
                    text = "Fake Call",
                    icon = Icons.Outlined.PhoneCallback,
                    onClick = { onPickerDismiss(); onFakeCall() }
                )
            }
            if (!hasWhatsApp && !hasTelegram && !hasGoogleMeet && !(showFakeCall && onFakeCall != null)) {
                RivoDropdownMenuItem(
                    text = "No apps installed",
                    icon = Icons.Default.Info,
                    onClick = onPickerDismiss
                )
            }
        }
    }

    if (showAppQuickActions != null) {
        val app = showAppQuickActions!!
        val appLabel = when (app) {
            "whatsapp" -> "WhatsApp"
            "telegram" -> "Telegram"
            else -> "Google Meet"
        }
        AppQuickActionsDialog(
            appName = appLabel,
            onChat = if (app == "googlemeet") null else {
                {
                    showAppQuickActions = null
                    val opened = if (app == "whatsapp") openWhatsAppChat(context, phoneNumber) else openTelegramChat(context, phoneNumber)
                    if (!opened) android.widget.Toast.makeText(context, "$appLabel isn't installed", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onVoiceCall = {
                showAppQuickActions = null
                val started = when (app) {
                    "whatsapp" -> startWhatsAppVoiceCall(context, phoneNumber)
                    "telegram" -> startTelegramVoiceCall(context, phoneNumber)
                    else -> startGoogleMeetVoiceCall(context, phoneNumber)
                }
                if (!started) android.widget.Toast.makeText(context, "$appLabel isn't installed", android.widget.Toast.LENGTH_SHORT).show()
            },
            onVideoCall = {
                showAppQuickActions = null
                val started = when (app) {
                    "whatsapp" -> startWhatsAppVideoCall(context, phoneNumber)
                    "telegram" -> startTelegramVideoCall(context, phoneNumber)
                    else -> startGoogleMeetVideoCall(context, phoneNumber)
                }
                if (!started) android.widget.Toast.makeText(context, "$appLabel isn't installed", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showAppQuickActions = null }
        )
    }
}
