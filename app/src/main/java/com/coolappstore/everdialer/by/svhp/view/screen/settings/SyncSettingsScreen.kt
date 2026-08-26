/*
 * Ever Émetteur — Synchronisation P2P (phone A → phone B).
 *
 * Opened from Settings → zone « Call Recording » → « Sync P2P vers Téléphone B ».
 * Phone A acts as the SENDER: either paste here the receiver's code OR generate
 * your own emitter code for the receiver to paste.
 */
package com.coolappstore.everdialer.by.svhp.view.screen.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coolappstore.everdialer.by.svhp.sync.SyncManager
import com.coolappstore.everdialer.by.svhp.sync.SyncRole
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Destination<RootGraph>
@Composable
fun SyncSettingsScreen(
    navigator: DestinationsNavigator
) {
    val context = LocalContext.current
    val state by SyncManager.state.collectAsState()
    val logs by SyncManager.logs.collectAsState()
    var pairingInput by remember { mutableStateOf("") }
    var emitterPairingCode by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Synchronisation P2P", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // Status
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.enabled && state.role == SyncRole.SENDER)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        when {
                            state.role != SyncRole.SENDER -> "Non jumelé"
                            !state.enabled -> "Synchronisation désactivée"
                            else -> "Jumelé avec ${state.peerName.ifBlank { "Téléphone B" }}"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.lastSyncAt > 0) {
                        Text(
                            "Dernier envoi : ${dateFormat.format(Date(state.lastSyncAt))}" +
                                (state.lastStatus.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Toggle
            Card(shape = RoundedCornerShape(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Envoyer vers le téléphone B", fontWeight = FontWeight.Medium)
                        Text(
                            "Appels + enregistrements poussés automatiquement",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { SyncManager.setEnabled(context, it) }
                    )
                }
            }

            // ── Paste receiver code ──────────────────────────────────────────
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Entrer le code du récepteur", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Sur le téléphone B (Ever Client), ouvre l'app → ⚙ → « Générer le code de jumelage », copie-le puis colle-le ici.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pairingInput,
                        onValueChange = { pairingInput = it },
                        label = { Text("Colle le code du téléphone B") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val ok = SyncManager.importSenderPairingCode(context, pairingInput)
                            Toast.makeText(
                                context,
                                if (ok) "Jumelé avec le téléphone B ✔" else "Code invalide",
                                Toast.LENGTH_LONG
                            ).show()
                            if (ok) pairingInput = ""
                        },
                        enabled = pairingInput.isNotBlank()
                    ) { Text("Importer et jumeler") }
                }
            }

            // ── Generate emitter code ────────────────────────────────────────
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Générer un code émetteur", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Génère un code pour CE téléphone (A). Ensuite, colle-le sur le téléphone B : Ever Client → ⚙ → Synchronisation P2P → « Entrer le code de l'émetteur ».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                emitterPairingCode = SyncManager.generateSenderPairingCode(context)
                            }.onFailure { e ->
                                Toast.makeText(context, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (emitterPairingCode == null) "Générer le code émetteur" else "Régénérer le code")
                    }
                }
            }

            // Display emitter code
            emitterPairingCode?.let { code ->
                Card(shape = RoundedCornerShape(16.dp)) {
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
                                Icon(Icons.Outlined.QrCode2, "Copier")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Copie ce code et colle-le sur le téléphone B : Ever Client → ⚙ → Synchronisation P2P.",
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

            // Manual push
            OutlinedButton(
                onClick = { SyncManager.requestSyncNow(context); Toast.makeText(context, "Envoi lancé…", Toast.LENGTH_SHORT).show() },
                enabled = state.enabled && state.role == SyncRole.SENDER,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Synchroniser maintenant") }

            // Logs
            if (logs.isNotEmpty()) {
                Card(shape = RoundedCornerShape(16.dp)) {
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
