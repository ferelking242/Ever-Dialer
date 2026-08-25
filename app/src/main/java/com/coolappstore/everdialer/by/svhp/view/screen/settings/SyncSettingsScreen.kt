/*
 * Ever Dialer+ — « Synchronisation téléphone ↔ téléphone » settings screen.
 *
 * Phone A (sender, ex. Tecno) pastes the pairing code shown by phone B
 * (receiver, ex. Samsung S21). Afterwards every recording + call-log entry
 * flows directly over WiFi, no server involved.
 */
package com.coolappstore.everdialer.by.svhp.view.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.PhoneForwarded
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import com.coolappstore.everdialer.by.svhp.sync.PairingPayload
import com.coolappstore.everdialer.by.svhp.sync.SyncJson
import com.coolappstore.everdialer.by.svhp.sync.SyncManager
import com.coolappstore.everdialer.by.svhp.sync.SyncRole
import com.coolappstore.everdialer.by.svhp.sync.SyncStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.encodeToString

private val AccentColor = Color(0xFF00897B)

private fun qrBitmap(content: String, size: Int = 640): Bitmap {
    val matrix = QRCodeWriter()
        .encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = createBitmap(size, size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.Black else Color.White)
        }
    }
    return bitmap
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copié ✔", Toast.LENGTH_SHORT).show()
}

private fun formatTime(millis: Long): String =
    if (millis <= 0) "—" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(millis))

@Destination<RootGraph>
@Composable
fun SyncSettingsScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val state by SyncManager.state.collectAsState()
    val recentLogs by SyncManager.logs.collectAsState()

    var receiverCode by remember { mutableStateOf<String?>(null) }
    var pasteValue by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ─── Header ───
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navigator.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Synchronisation", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Téléphone A → Téléphone B, direct par WiFi",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ─── Master switch ───
        Card(shape = RoundedCornerShape(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Sync, tint = AccentColor)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Activer la synchronisation", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.enabled) "Activée · ${state.role.name.lowercase()}" else "Désactivée",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { SyncManager.setEnabled(context, it) }
                )
            }
        }

        // ─── Role selection while unpaired ───
        if (state.role == SyncRole.UNPAIRED) {
            Text("Choisis le rôle de ce téléphone :", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    receiverCode = receiverCode
                        ?: SyncManager.generateReceiverPairingCode(context)
                },
                enabled = true
            ) {
                Icon(Icons.Outlined.PhoneForwarded, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ce téléphone reçoit (B)")
            }

            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhoneInTalk, tint = AccentColor)
                        Spacer(Modifier.width(12.dp))
                        Text("Ce téléphone envoie (A)", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedTextField(
                        value = pasteValue,
                        onValueChange = { pasteValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Colle ici le code affiché sur le téléphone B") },
                        minLines = 2,
                        fontSize = 12.sp
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = pasteValue.isNotBlank(),
                        onClick = {
                            if (SyncManager.importSenderPairingCode(context, pasteValue)) {
                                pasteValue = ""
                            } else {
                                Toast.makeText(context, "Code invalide", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Send, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Appairer avec le téléphone B")
                    }
                }
            }
        }

        // ─── RECEIVER view ───
        if (state.role == SyncRole.RECEIVER) {
            val shownCode = receiverCode ?: run {
                val secret = SyncStore.pairingSecret(context)
                secret?.let {
                    SyncJson.encodeToString(
                        PairingPayload(id = state.myId, name = state.myName, role = "receiver", secret = it)
                    )
                }
            }

            Card(shape = RoundedCornerShape(18.dp)) {
                Column(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Téléphone récepteur (B)", fontWeight = FontWeight.Bold, color = AccentColor)
                    Text(
                        "Sur le téléphone A : ouvre ce même écran, choisis « Ce téléphone envoie » et colle ce code.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (shownCode != null) {
                        Image(
                            bitmap = remember(shownCode) { qrBitmap(shownCode) }.asImageBitmap(),
                            contentDescription = "QR de jumelage",
                            modifier = Modifier.size(220.dp).background(Color.White)
                        )
                        OutlinedButton(onClick = { copyToClipboard(context, "Ever Dialer pairing", shownCode) }) {
                            Icon(Icons.Default.ContentCopy, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copier le code")
                        }
                    }
                    Text(
                        "En écoute sur le port ${state.serverPort.takeIf { it > 0 } ?: "?"} — même WiFi requis.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ─── Paired summary (both roles) ───
        if (state.role != SyncRole.UNPAIRED) {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("État", fontWeight = FontWeight.SemiBold)
                    InfoRow("Cet appareil", "${state.myName} (${state.role.name.lowercase()})")
                    if (state.peerId.isNotEmpty()) {
                        InfoRow("Appareil jumelé", state.peerName.ifEmpty { state.peerId })
                    }
                    InfoRow("Dernière synchro", formatTime(state.lastSyncAt))
                    if (state.lastStatus.isNotEmpty()) {
                        InfoRow("Statut", state.lastStatus)
                    }
                    if (state.role == SyncRole.SENDER) {
                        Spacer(Modifier.height(4.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { SyncManager.requestSyncNow(context) }
                        ) {
                            Icon(Icons.Default.Send, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Synchroniser maintenant")
                        }
                    }
                }
            }

            if (recentLogs.isNotEmpty()) {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Journal", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        recentLogs.take(5).forEach { line ->
                            Text(
                                line.substringAfter("· "),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
