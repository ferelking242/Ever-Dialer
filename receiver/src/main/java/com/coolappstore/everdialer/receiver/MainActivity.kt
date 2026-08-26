/*
 * Ever Call Recording (phone B) — minimal companion that ONLY receives what
 * the main Ever Dialer+ app (phone A) pushes over the local network.
 *
 * The UI is intentionally styled to match the Ever Call Recorder's recording
 * list (dark theme, Material3 ListItem rows, filter pills, grouped by date).
 * This app has NO call detection — it is purely a passive receiver.
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EverReceiverApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (com.coolappstore.everdialer.by.svhp.sync.SyncStore.isEnabled(this)) {
            ReceiveService.start(this)
        }
    }
}

/* ── Main screen: recording list (matches Ever Call Recorder HomeScreen) ── */

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
    var searchQuery by remember { mutableStateOf("") }

    fun reload() {
        recordings = loadRecordings(context)
        calls = SyncLibrary.query(context)
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(syncState.lastSyncAt) { if (syncState.lastSyncAt > 0) reload() }
    LaunchedEffect(reloadTick) { reload() }

    val isPaired = syncState.enabled && syncState.role == SyncRole.RECEIVER

    if (showP2p) {
        P2pScreen(
            enabled = syncState.enabled,
            peerName = syncState.peerName,
            myName = syncState.myName,
            lastSyncAt = syncState.lastSyncAt,
            lastStatus = syncState.lastStatus,
            isReceiver = syncState.role == SyncRole.RECEIVER,
            logs = logs,
            onBack = { showP2p = false },
            onToggle = { enable ->
                SyncManager.setEnabled(context, enable)
                if (enable) ReceiveService.start(context) else ReceiveService.stop(context)
            },
            onRefreshLists = { reloadTick++ }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ever Call Recording", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { showP2p = true }, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                // ── Pairing status banner (when not paired) ─────────────────
                if (!isPaired) {
                    item {
                        NotPairedBanner(onClick = { showP2p = true })
                    }
                }

                // ── Search bar ─────────────────────────────────────────────
                item {
                    Surface(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Search, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Rechercher…") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = Color.Transparent,
                                    cursorColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // ── Call log section ───────────────────────────────────────
                item {
                    DateGroupHeader(label = "Journal d'appels du téléphone A")
                }

                val filteredCalls = if (searchQuery.isBlank()) calls else {
                    val q = searchQuery.trim().lowercase()
                    calls.filter {
                        it.number.lowercase().contains(q) ||
                            (it.contactName?.lowercase()?.contains(q) == true)
                    }
                }

                if (filteredCalls.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Aucun appel reçu",
                            body = if (!isPaired)
                                "Configure la synchronisation P2P pour recevoir les appels du téléphone A."
                            else
                                "Les appels arrivent ici automatiquement quand le téléphone A est en ligne."
                        )
                    }
                } else {
                    // Group calls by date
                    val grouped = filteredCalls.groupBy { groupLabel(it.date) }
                    grouped.forEach { (dateLabel, callsInGroup) ->
                        item(key = "header_$dateLabel") {
                            DateGroupHeader(label = dateLabel)
                        }
                        item(key = "group_$dateLabel") {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    callsInGroup.forEachIndexed { index, call ->
                                        val hasAudio = call.recording != null &&
                                            java.io.File(context.filesDir, "EverSync/recordings/${call.recording}").exists()
                                        CallLogRow(
                                            call = call,
                                            hasAudio = hasAudio,
                                            onClick = {
                                                call.recording?.let { name ->
                                                    openRecording(
                                                        context,
                                                        File(context.filesDir, "EverSync/recordings/$name")
                                                    )
                                                } ?: Toast.makeText(
                                                    context,
                                                    "Pas d'enregistrement pour cet appel",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        )
                                        if (index < callsInGroup.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Recordings section ─────────────────────────────────────
                item {
                    DateGroupHeader(
                        label = "Enregistrements reçus (${recordings.size})"
                    )
                }

                val filteredRecordings = if (searchQuery.isBlank()) recordings else {
                    val q = searchQuery.trim().lowercase()
                    recordings.filter { it.name.lowercase().contains(q) }
                }

                if (filteredRecordings.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Aucun enregistrement",
                            body = if (!isPaired)
                                "Configure la synchronisation P2P pour recevoir les enregistrements du téléphone A."
                            else
                                "Les enregistrements arrivent ici automatiquement quand le téléphone A est en ligne."
                        )
                    }
                } else {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                filteredRecordings.forEachIndexed { index, file ->
                                    RecordingRow(
                                        file = file,
                                        onClick = { openRecording(context, file) }
                                    )
                                    if (index < filteredRecordings.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ── Not-paired banner (invites user to configure) ────────────────────── */

@Composable
private fun NotPairedBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFB71C1C),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.ErrorOutline, null,
                tint = Color.White, modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Synchronisation non configurée",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    "Appaire avec le téléphone A pour recevoir appels et enregistrements.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
            Icon(
                Icons.Outlined.ChevronRight, null,
                tint = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

/* ── Full-screen P2P settings ─────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun P2pScreen(
    enabled: Boolean,
    peerName: String,
    myName: String,
    lastSyncAt: Long,
    lastStatus: String,
    isReceiver: Boolean,
    logs: List<String>,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRefreshLists: () -> Unit
) {
    val context = LocalContext.current
    var pairingCode by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Synchronisation P2P",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Status card ──────────────────────────────────────────────
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (enabled && isReceiver)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        when {
                            !isReceiver -> "Non jumelé"
                            !enabled -> "Synchronisation désactivée"
                            else -> "Jumelé avec ${peerName.ifBlank { "Téléphone A" }}"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    if (lastSyncAt > 0) {
                        Text(
                            "Dernière réception : ${dateFormat.format(Date(lastSyncAt))}" +
                                (lastStatus.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis
                        )
                    } else if (lastStatus.isNotBlank()) {
                        Text(
                            lastStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ── Toggle ──────────────────────────────────────────────────
            Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Réception automatique", fontWeight = FontWeight.Medium)
                        Text(
                            if (enabled)
                                "Activée${if (peerName.isNotBlank()) " · jumelé avec $peerName" else ""}"
                            else "Désactivée",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = onToggle)
                }
            }

            // ── Pairing code generator ───────────────────────────────────
            Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Code de jumelage", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Sur le téléphone A, ouvre Ever Dialer+ → Réglages → Synchronisation P2P → génère et copie le code, puis colle-le ici.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            pairingCode = SyncManager.generateReceiverPairingCode(context)
                            ReceiveService.start(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (pairingCode == null) "Générer le code de jumelage" else "Régénérer le code")
                    }
                }
            }

            // ── Display pairing code ─────────────────────────────────────
            pairingCode?.let { code ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Code généré ✔", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("ever-pairing", code))
                                Toast.makeText(context, "Code copié", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Outlined.ContentCopy, "Copier le code")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Copie ce code sur le téléphone A : Ever Dialer+ → Réglages → Synchronisation P2P.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

            // ── Refresh button ───────────────────────────────────────────
            OutlinedButton(
                onClick = onRefreshLists,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Rafraîchir la liste") }

            // ── Logs ─────────────────────────────────────────────────────
            if (logs.isNotEmpty()) {
                Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Journal", style = MaterialTheme.typography.labelLarge)
                        logs.take(12).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ── Call log row (matches Ever Call Recorder RecordingRow) ─────────────── */

@Composable
private fun CallLogRow(call: CallMeta, hasAudio: Boolean, onClick: () -> Unit) {
    val isIncoming = call.direction == "INCOMING"
    val accentColor = if (isIncoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val directionIcon = if (isIncoming) Icons.Rounded.CallReceived else Icons.Rounded.CallMade
    val directionLabel = if (isIncoming) "Entrant" else "Sortant"
    val timeStr = dateFmtShort.format(Date(call.date))

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                val initial = call.contactName?.firstOrNull()?.uppercaseChar()?.toString()
                    ?: call.number.firstOrNull { it.isDigit() }?.toString() ?: "?"
                Text(initial, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = accentColor)
            }
        },
        headlineContent = {
            Text(
                call.contactName?.takeIf { it.isNotBlank() } ?: call.number.ifBlank { "Numéro inconnu" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(directionIcon, null, tint = accentColor, modifier = Modifier.size(11.dp))
                Text(directionLabel, style = MaterialTheme.typography.labelSmall, color = accentColor)
                Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDuration(call.durationSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasAudio) {
                    Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                }
            }
        },
        trailingContent = {
            if (hasAudio) {
                Icon(
                    Icons.Rounded.GraphicEq, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/* ── Recording row ────────────────────────────────────────────────────── */

@Composable
private fun RecordingRow(file: File, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.GraphicEq, null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        headlineContent = {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                "${dateFmtShort.format(Date(file.lastModified()))} · ${formatBytes(file.length())}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/* ── Shared UI pieces ─────────────────────────────────────────────────── */

@Composable
private fun DateGroupHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

@Composable
private fun EmptyState(title: String, body: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.MicNone, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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

private val dateFmtShort = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)

private fun groupLabel(date: Long): String {
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { timeInMillis = date }
    return when {
        isSameDay(now, cal) -> "Aujourd'hui"
        isYesterday(now, cal) -> "Hier"
        isSameWeek(now, cal) -> SimpleDateFormat("EEEE", Locale.FRANCE).format(Date(date))
        isSameYear(now, cal) -> SimpleDateFormat("d MMMM", Locale.FRANCE).format(Date(date))
        else -> SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date(date))
    }
}

private fun isSameDay(a: Calendar, b: Calendar) =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun isYesterday(now: Calendar, b: Calendar): Boolean {
    val y = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return isSameDay(y, b)
}

private fun isSameWeek(now: Calendar, b: Calendar) =
    now.get(Calendar.YEAR) == b.get(Calendar.YEAR) && now.get(Calendar.WEEK_OF_YEAR) == b.get(Calendar.WEEK_OF_YEAR)

private fun isSameYear(now: Calendar, b: Calendar) = now.get(Calendar.YEAR) == b.get(Calendar.YEAR)

private fun formatDuration(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f Mo".format(bytes.toFloat() / (1 shl 20))
    bytes >= 1 shl 10 -> "%.0f Ko".format(bytes.toFloat() / (1 shl 10))
    else -> "$bytes o"
}
