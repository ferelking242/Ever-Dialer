/*
 * Ever Dialer+ — privileged runtime (Phase 2).
 * Dedicated one-shot setup page (Shizuku-manager style):
 *   → opens Developer options directly,
 *   → guides through wireless debugging pairing,
 *   → auto-fills the pairing port discovered over mDNS,
 *   → accepts the 6-digit code and pairs the app with ITSELF,
 *   → then starts + watches the embedded Shizuku server.
 */
package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PairingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrivilegedRuntime.refreshState()
        setContent { MaterialTheme { PairingScreen() } }
    }
}

@Composable
private fun PairingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val runtimeState by PrivilegedRuntime.state.collectAsState()
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var watchdogEnabled by remember { mutableStateOf(PrivilegedRuntime.isWatchdogEnabled(context)) }
    val logLines = remember { mutableStateListOf<String>() }

    fun log(message: String) {
        logLines.add("${System.currentTimeMillis() % 100000}: $message")
        while (logLines.size > 8) logLines.removeAt(0)
    }

    // Auto-fill the one-time pairing port while the user opens the system dialog.
    DisposableEffect(Unit) {
        val mdns = PrivilegedRuntime.observePairingPort(context) { port ->
            if (pairingPort.isBlank()) pairingPort = port.toString()
        }
        onDispose { mdns?.stop() }
    }

    // After a successful pairing, immediately bring the server up.
    LaunchedEffect(runtimeState) {
        if (runtimeState == PrivilegedRuntime.State.PAIRED_IDLE && !busy) {
            busy = true
            PrivilegedRuntime.ensureServerStarted(context) { log(it) }
                .onFailure { log("Échec : ${it.message}") }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Privilèges système intégrés",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Une seule app, zéro installation externe : Ever Dialer se connecte à son propre moteur privilégié via le débogage sans fil (Android 11+). Configuration unique ≈ 30 secondes.",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant
        )

        StatusCard(runtimeState)

        // ── Step 1 ────────────────────────────────────────────────
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1 · Options développeur", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Ouvre les réglages développeur, puis active « Débogage sans fil ».",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }.onFailure {
                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    }
                }) { Text("Ouvrir les options développeur") }
            }
        }

        // ── Step 2 ────────────────────────────────────────────────
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2 · Associer l'appareil", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Dans « Débogage sans fil », touche « Associer l'appareil avec un code d'appairage ». " +
                        "Le PORT ci-dessous se remplit automatiquement ; recopie juste le CODE à 6 chiffres affiché.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pairingPort,
                        onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("Code") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1.4f)
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            log("Appairage en cours…")
                            PrivilegedRuntime.pairWithCode(context, "127.0.0.1", pairingPort.toIntOrNull() ?: 0, pairingCode)
                                .onSuccess { log("Appairé ✔") }
                                .onFailure { log("Échec appairage : ${it.message}"); delay(50); busy = false }
                            if (PrivilegedRuntime.isPaired(context)) busy = true // LaunchedEffect takes over
                            else busy = false
                        }
                    },
                    enabled = !busy && pairingCode.length == 6 && pairingPort.isNotBlank(),
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Associer et activer")
                }
            }
        }

        // ── Server control ────────────────────────────────────────
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Serveur embarqué", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Surveillance automatique", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = watchdogEnabled,
                        onCheckedChange = {
                            watchdogEnabled = it
                            PrivilegedRuntime.setWatchdogEnabled(context, it)
                            if (it) EmbeddedShizukuService.start(context) else EmbeddedShizukuService.stop(context)
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                PrivilegedRuntime.ensureServerStarted(context) { log(it) }
                                    .onFailure { log("Échec : ${it.message}") }
                                busy = false
                            }
                        },
                        enabled = !busy && PrivilegedRuntime.isPaired(context),
                        modifier = Modifier.weight(1f)
                    ) { Text("Démarrer") }
                    OutlinedButton(
                        onClick = { scope.launch { PrivilegedRuntime.stopServer(context) } },
                        modifier = Modifier.weight(1f)
                    ) { Text("Arrêter") }
                }
            }
        }

        // ── Console ───────────────────────────────────────────────
        if (logLines.isNotEmpty()) {
            Surface(color = colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    logLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Text(
            "Note : après un redémarrage, si l'enregistrement ne fonctionne plus, ouvre cette page — la reconnexion est automatique quand le débogage sans fil est encore activé.",
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(state: PrivilegedRuntime.State) {
    val (label, color) = when (state) {
        PrivilegedRuntime.State.RUNNING -> "Actif — enregistrement prêt" to Color(0xFF2E7D32)
        PrivilegedRuntime.State.STARTING -> "Démarrage…" to Color(0xFFF9A825)
        PrivilegedRuntime.State.PAIRED_IDLE -> "Apparié — serveur arrêté" to Color(0xFF1565C0)
        PrivilegedRuntime.State.FAILED -> "Échec — voir console" to Color(0xFFC62828)
        PrivilegedRuntime.State.NOT_PAIRED -> "Non apparié" to colorScheme.onSurfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(12.dp),
            ) {
                Surface(color = color, shape = CircleShape, modifier = Modifier.fillMaxSize()) {}
            }
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}
