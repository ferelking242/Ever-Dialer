/*
 * Ever Dialer+ — Pairing screen for the embedded Shizuku server.
 *
 * Minimalist flow like the real Shizuku manager:
 *   1. Badge shows current status (Not paired / Paired / Running / Failed)
 *   2. Code input — the 6-digit code from the system wireless-debugging notification
 *   3. "Pair" button — performs SPAKE2p handshake
 *   4. Green badge when paired & running
 *
 * The "Manage pairing" button in Settings opens Developer Options directly.
 * This screen is launched by the PairingNotification or when the user re-pairs.
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

    // Auto-detect pairing port via mDNS
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
            PrivilegedRuntime.ensureServerStarted(context) { }.onFailure { /* silent */ }
            busy = false
        }
    }

    val isPaired = runtimeState == PrivilegedRuntime.State.RUNNING ||
        runtimeState == PrivilegedRuntime.State.PAIRED_IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Status badge ────────────────────────────────
        StatusBadge(runtimeState)

        Spacer(Modifier.height(4.dp))

        // ── Main content ────────────────────────────────
        if (isPaired) {
            // Already paired — show success card
            SuccessCard(runtimeState, context)
        } else {
            // Not paired — show pairing card
            PairingCard(
                port = pairingPort,
                code = pairingCode,
                onCodeChange = { pairingCode = it.filter { c -> c.isDigit() }.take(6) },
                busy = busy,
                errorMsg = errorMsg,
                onPair = {
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
                onOpenDevSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        )
                    }.onFailure {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun PairingCard(
    port: String,
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    errorMsg: String?,
    onPair: () -> Unit,
    onOpenDevSettings: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Info text
        Text(
            "Active le débogage sans fil dans les options développeur, " +
            " puis tape « Associer avec un code ».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Open Dev Settings button
        OutlinedButton(
            onClick = onOpenDevSettings,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Ouvrir les options développeur")
        }

        // mDNS waiting indicator
        AnimatedVisibility(
            visible = port.isBlank(),
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

        // Code input + pair button (visible once port detected)
        AnimatedVisibility(
            visible = port.isNotBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Port detected indicator
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
                            "Port détecté : $port",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // 6-digit code field
                    OutlinedTextField(
                        value = code,
                        onValueChange = onCodeChange,
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

                    // Pair button
                    Button(
                        onClick = onPair,
                        enabled = !busy && code.length == 6,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = ButtonDefaults.buttonColors().contentColor
                            )
                        } else {
                            Text("Associer")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessCard(state: PrivilegedRuntime.State, context: android.content.Context) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2E7D32).copy(alpha = 0.12f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "✔ Appairé et actif",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D32)
            )
            Text(
                if (state == PrivilegedRuntime.State.RUNNING)
                    "Le moteur privilégié est actif. L'enregistrement des appels est prêt."
                else
                    "Appareil apparié. Démarrage du moteur…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = { (context as? ComponentActivity)?.finish() },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Fermer")
            }
        }
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
