/*
 * Ever Call Recording (phone B) — the whole app lives on ONE screen:
 *   • everything received from phone A (recordings + call log),
 *   • a single ⚙ gear that opens the P2P configuration (pairing + status).
 */
package com.coolappstore.everdialer.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallMissed
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.coolappstore.everdialer.by.svhp.sync.CallMeta
import com.coolappstore.everdialer.by.svhp.sync.SyncLibrary
import com.coolappstore.everdialer.by.svhp.sync.SyncManager
import com.coolappstore.everdialer.by.svhp.sync.SyncRole
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        setContent { EverReceiverApp() }
    }

    override fun onResume() {
        super.onResume()
        // Keep the listener reachable whenever the app comes to the foreground.
        if (com.coolappstore.everdialer.by.svhp.sync.SyncStore.isEnabled(this)) {
            ReceiveService.start(this)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EverReceiverApp() {
    val context = LocalContext.current
    val syncState by SyncManager.state.collectAsState()
    val logs by SyncManager.logs.collectAsState()

    var showP2p by remember { mutableStateOf(false) }
    var recordings by remember { mutableStateOf<List<File>>(emptyList()) }
    var calls by remember { mutableStateOf<List<CallMeta>>(emptyList()) }
    var reloadTick by remember { mutableStateOf(0) }

    fun reload() {
        recordings = loadRecordings(context)
        calls = SyncLibrary.query(context)
    }

    LaunchedEffect(Unit) { reload() }
    // New data just landed from phone A → refresh the lists.
    LaunchedEffect(syncState.lastSyncAt) { if (syncState.lastSyncAt > 0) reload() }
    LaunchedEffect(reloadTick) { reload() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Ever Call Recording", fontWeight = FontWeight.SemiBold) },
                actions = {
                    // THE single settings control of this app: P2P only.
                    IconButton(onClick = { showP2p = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Configuration P2P")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    pairedPeer = syncState.peerName,
                    enabled = syncState.enabled && syncState.role == SyncRole.RECEIVER,
                    lastStatus = syncState.lastStatus
                )
            }

            item { SectionTitle("Journal d'appels du téléphone A") }
            if (calls.isEmpty()) {
                item { EmptyHint("Aucun appel reçu pour l'instant.\nAppaire le téléphone A ci-dessus ⚙ puis laisse les deux téléphones sur le même WiFi.") }
            } else {
                items(calls.size) { i ->
                    val call = calls[i]
                    CallRow(
                        call = call,
                        hasAudio = call.recording != null && File(context.filesDir, "EverSync/recordings/${call.recording}").exists(),
                        onClick = {
                            call.recording?.let { name ->
                                openRecording(context, File(context.filesDir, "EverSync/recordings/$name"))
                            } ?: Toast.makeText(context, "Pas d'enregistrement pour cet appel", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            item { SectionTitle("Enregistrements reçus (${recordings.size})") }
            if (recordings.isEmpty()) {
                item { EmptyHint("Les enregistrements arrivent ici automatiquement dès que le téléphone A est en ligne.") }
            } else {
                items(recordings.size) { i ->
                    val file = recordings[i]
                    RecordingRow(file = file, onClick = { openRecording(context, file) })
                }
            }
        }
    }

    if (showP2p) {
        ModalBottomSheet(onDismissRequest = { showP2p = false }) {
            P2pPanel(
                enabled = syncState.enabled && syncState.role == SyncRole.RECEIVER,
                peerName = syncState.peerName,
                myName = syncState.myName,
                lastStatus = syncState.lastStatus,
                logs = logs.take(8),
                onToggle = { enable ->
                    SyncManager.setEnabled(context, enable)
                    if (enable) ReceiveService.start(context) else ReceiveService.stop(context)
                },
                onRefreshLists = { reloadTick++ }
            )
        }
    }
}

/* ── P2P panel (the only settings this app has) ─────────────────────────── */

@Composable
private fun P2pPanel(
    enabled: Boolean,
    peerName: String,
    myName: String,
    lastStatus: String,
    logs: List<String>,
    onToggle: (Boolean) -> Unit,
    onRefreshLists: () -> Unit
) {
    val context = LocalContext.current
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var importField by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {

        Text("Synchronisation P2P", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Cet appareil (B) reçoit automatiquement appels + enregistrements du téléphone A dès que les deux sont en ligne sur le même WiFi. Aucun serveur.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Réception automatique", fontWeight = FontWeight.Medium)
                Text(
                    if (enabled) "Activée${if (peerName.isNotBlank()) " · jumelé avec $peerName" else ""}" else "Désactivée",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        Spacer(Modifier.height(16.dp))

        if (!enabled) {
            Button(
                onClick = { pairingCode = SyncManager.generateReceiverPairingCode(context); ReceiveService.start(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Générer le code de jumelage")
            }
        }

        pairingCode?.let { code ->
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Étape 1 — Sur CE téléphone c'est fait ✔", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("ever-pairing", code))
                            Toast.makeText(context, "Code copié", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copier le code") }
                    }
                    Text("Étape 2 — Sur le TÉLÉPHONE A : Ever Dialer+ → Réglages → Synchronisation P2P → colle ce code.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        code,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (!peerName.isBlank()) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = importField,
                onValueChange = { importField = it },
                label = { Text("Coller un nouveau code de jumelage") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                // Receiver side never imports: codes flow B → A. Keep a hint instead.
                Toast.makeText(context, "Le code se colle sur le téléphone A, pas ici.", Toast.LENGTH_LONG).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("Aide") }
        }

        if (lastStatus.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("Dernier événement : $lastStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedButton(onClick = onRefreshLists, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Rafraîchir la liste")
        }

        if (logs.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Journal", style = MaterialTheme.typography.labelLarge)
            logs.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/* ── List pieces ────────────────────────────────────────────────────────── */

@Composable
private fun StatusCard(pairedPeer: String, enabled: Boolean, lastStatus: String) {
    Card(colors = CardDefaults.cardColors(containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PhoneInTalk, null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    if (enabled) "Réception active" else "Non jumelé — ouvre ⚙",
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (pairedPeer.isNotBlank()) {
                Text("Téléphone A : $pairedPeer", style = MaterialTheme.typography.bodySmall)
            }
            if (lastStatus.isNotBlank()) {
                Text(lastStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CallRow(call: CallMeta, hasAudio: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(directionIcon(call.direction), null, Modifier.size(22.dp), tint = directionColor(call.direction))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    call.contactName?.takeIf { it.isNotBlank() } ?: call.number.ifBlank { "Numéro inconnu" },
                    fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatDate(call.date)} · ${directionLabel(call.direction)} · ${formatDuration(call.durationSec)}" +
                        (call.contactName?.takeIf { it.isNotBlank() }?.let { " · ${call.number}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (hasAudio) Icon(Icons.Outlined.GraphicEq, contentDescription = "Audio disponible", Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RecordingRow(file: File, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GraphicEq, null, Modifier.size(22.dp))
            Spacer(Modifier.size(12.dp))
            Column {
                Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatDate(file.lastModified())} · ${formatBytes(file.length())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/* ── Helpers ────────────────────────────────────────────────────────────── */

private fun loadRecordings(context: android.content.Context): List<File> =
    runCatching {
        File(context.filesDir, "EverSync/recordings")
            .listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
    }.getOrNull().orEmpty()

private fun openRecording(context: android.content.Context, file: File) {
    if (!file.exists()) {
        Toast.makeText(context, "Fichier introuvable", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "audio/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "Aucun lecteur audio trouvé", Toast.LENGTH_SHORT).show()
    }
}

private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
private fun formatDate(ts: Long) = dateFmt.format(Date(ts))

private fun formatDuration(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f Mo".format(bytes.toFloat() / (1 shl 20))
    bytes >= 1 shl 10 -> "%.0f Ko".format(bytes.toFloat() / (1 shl 10))
    else -> "$bytes o"
}

private fun directionLabel(direction: String) = when (direction) {
    "INCOMING" -> "Entrant"; "OUTGOING" -> "Sortant"; "MISSED" -> "Manqué"
    "REJECTED" -> "Refusé"; "BLOCKED" -> "Bloqué"; "ANSWERED_EXTERNALLY" -> "Décroché ailleurs"
    else -> "Autre"
}

@Composable
private fun directionIcon(direction: String) = when (direction) {
    "INCOMING" -> Icons.Outlined.CallReceived
    "OUTGOING" -> Icons.Outlined.CallMade
    else -> Icons.Outlined.CallMissed
}

@Composable
private fun directionColor(direction: String) = when (direction) {
    "INCOMING" -> MaterialTheme.colorScheme.primary
    "OUTGOING" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}
