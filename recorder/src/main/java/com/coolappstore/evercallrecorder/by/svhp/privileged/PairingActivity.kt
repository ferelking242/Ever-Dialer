/*
 * Ever Dialer+ — privileged runtime (Phase 2).
 * Clean pairing screen, Shizuku-manager style:
 *   1. Single screen with status + one button to open Developer Options
 *   2. mDNS auto-detects the pairing port
 *   3. User enters the 6-digit code shown in the system notification
 *   4. App pairs and starts the embedded server automatically
 */
package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Auto-fill the pairing port via mDNS — once the user opens Developer
    // Options → Wireless Debugging → Pair with pairing code, Android starts
    // broadcasting _adb-tls-pairing._tcp and the port appears here.
    DisposableEffect(Unit) {
        val mdns = PrivilegedRuntime.observePairingPort(context) { port ->
            if (pairingPort.isBlank()) pairingPort = port.toString()
        }
        onDispose { mdns?.stop() }
    }

    // Auto-start server after successful pairing
    LaunchedEffect(runtimeState) {
        if (runtimeState == PrivilegedRuntime.State.PAIRED_IDLE) {
            busy = true
            PrivilegedRuntime.ensureServerStarted(context) { }
                .onFailure { /* silent */ }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Header ──────────────────────────────────────────
        Text(
            "Pairing privilégié",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Le moteur d'enregistrement a besoin de se connecter au débogage\n" +
            "sans fil de l'appareil (Android 11+).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Status badge ────────────────────────────────────
        StatusBadge(runtimeState)

        // ── Main card ───────────────────────────────────────
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // The big button: opens Developer Options
                Text(
                    "Étape 1 — Active le débogage sans fil",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Ouvre les options développeur, puis active « Débogage sans fil » " +
                    "et touche « Associer l'appareil avec un code d'appairage ».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Ouvrir les options développeur")
                }

                // ── Code input (visible once mDNS finds the port) ─────
                AnimatedVisibility(
                    visible = pairingPort.isNotBlank(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDividerThin()

                        Text(
                            "Étape 2 — Saisis le code à 6 chiffres",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Quand tu appuies sur « Associer l'appareil avec un code », " +
                            "une notification système apparaît avec un code à 6 chiffres. " +
                            "Note-le et colle-le ici.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Port auto-detected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF2E7D32), CircleShape)
                            )
                            Text(
                                "Port détecté : $pairingPort",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // 6-digit code field
                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = {
                                pairingCode = it.filter { c -> c.isDigit() }.take(6)
                                errorMsg = null
                            },
                            label = { Text("Code à 6 chiffres") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword
                            ),
                            textStyle = MaterialTheme.typography.headlineMedium.copy(
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = MaterialTheme.typography.headlineMedium.letterSpacing
                            ),
                            isError = errorMsg != null,
                            supportingText = errorMsg?.let {
                                { Text(it, color = MaterialTheme.colorScheme.error) }
                            }
                        )

                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    errorMsg = null
                                    PrivilegedRuntime.pairWithCode(
                                        context,
                                        "127.0.0.1",
                                        pairingPort.toIntOrNull() ?: 0,
                                        pairingCode
                                    ).onSuccess {
                                        // LaunchedEffect will auto-start server
                                    }.onFailure { e ->
                                        errorMsg = e.message ?: "Échec de l'appairage"
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && pairingCode.length == 6,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Associer")
                            }
                        }
                    }
                }

                // ── Waiting hint while no port detected ──────
                AnimatedVisibility(
                    visible = pairingPort.isBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "En attente du débogage sans fil…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Done badge ──────────────────────────────────────
        AnimatedVisibility(
            visible = runtimeState == PrivilegedRuntime.State.RUNNING,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2E7D32).copy(alpha = 0.12f)
                )
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "✔ Appairé et actif",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    Text(
                        "Le moteur privilégié est en cours d'exécution. " +
                        "L'enregistrement des appels est prêt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Text("Fermer")
                    }
                }
            }
        }

        // ── Help text ───────────────────────────────────────
        Text(
            "Après un redémarrage, si l'enregistrement ne fonctionne plus, " +
            "reviens ici — la reconnexion est automatique si le débogage sans fil est encore activé.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusBadge(state: PrivilegedRuntime.State) {
    val (label, color) = when (state) {
        PrivilegedRuntime.State.RUNNING -> "Enregistrement prêt" to Color(0xFF2E7D32)
        PrivilegedRuntime.State.STARTING -> "Démarrage…" to Color(0xFFF9A825)
        PrivilegedRuntime.State.PAIRED_IDLE -> "Apparié" to Color(0xFF1565C0)
        PrivilegedRuntime.State.FAILED -> "Échec" to Color(0xFFC62828)
        PrivilegedRuntime.State.NOT_PAIRED -> "Non apparié" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun HorizontalDividerThin() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
