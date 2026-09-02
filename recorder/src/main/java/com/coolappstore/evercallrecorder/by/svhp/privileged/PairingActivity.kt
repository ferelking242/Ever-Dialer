/*
 * Ever Dialer+ — Pairing screen for the embedded Shizuku server.
 *
 * Minimalist flow like the real Shizuku manager:
 *   1. Badge shows current status (Not paired / Paired / Running / Failed)
 *   2. Button opens Developer Settings directly
 *   3. Port auto-detected via mDNS, but always editable manually
 *   4. Code input always visible — enter the 6-digit code from the system dialog
 *   5. "Pair" button performs SPAKE2p handshake
 *   6. Green badge when paired & running
 */
package com.coolappstore.evercallrecorder.by.svhp.privileged

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

private const val TAG = "PairingActivity"

class PairingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore PAIRED_IDLE after a process restart. The embedded runtime
        // does not require an external Shizuku app or notification permission.
        PrivilegedRuntime.refreshState(this)
        startPairedRuntimeIfNeeded()

        setContent { MaterialTheme { PairingScreen() } }
    }

    /**
     * Start from the Activity lifecycle rather than a Compose LaunchedEffect.
     * A state change from PAIRED_IDLE to STARTING replaces the content branch;
     * tying this long operation to composition can cancel it with
     * LeftCompositionCancellationException on some Compose versions.
     */
    private fun startPairedRuntimeIfNeeded() {
        if (!PrivilegedRuntime.isPaired(this) || PrivilegedRuntime.isConnected()) return
        lifecycleScope.launch {
            PrivilegedRuntime.ensureServerStarted(applicationContext).onFailure {
                Log.e(TAG, "Automatic embedded-server startup failed", it)
            }
        }
    }
}

@Composable
private fun PairingScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val operationScope = activity?.lifecycleScope ?: scope
    val runtimeState by PrivilegedRuntime.state.collectAsState()

    // Read port/host from intent extras (passed by notification)
    val intentPort = activity?.intent?.getIntExtra(PairingNotifier.EXTRA_PAIRING_PORT, 0) ?: 0
    val intentHost = activity?.intent?.getStringExtra(PairingNotifier.EXTRA_PAIRING_HOST)
        ?.takeIf { it.isNotBlank() }
        ?: PairingNotifier.detectedHost

    var pairingPort by remember { mutableStateOf(if (intentPort > 0) intentPort.toString() else "") }
    var pairingHost by remember { mutableStateOf(intentHost) }
    var pairingCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var portDetected by remember { mutableStateOf(intentPort > 0) }

    // Auto-detect pairing port via mDNS (if not already provided by intent)
    DisposableEffect(Unit) {
        var mdns: moe.shizuku.manager.adb.AdbMdns? = null
        if (intentPort <= 0) {
            Log.d(TAG, "Starting mDNS watcher for pairing port")
            mdns = PrivilegedRuntime.observePairingPort(context) { host, port ->
                Log.d(TAG, "mDNS detected endpoint: $host:$port")
                pairingHost = host
                pairingPort = port.toString()
                portDetected = true
            }
        } else {
            Log.d(TAG, "Port from intent: $intentPort")
        }
        onDispose { mdns?.stop() }
    }

    val isRunning = runtimeState == PrivilegedRuntime.State.RUNNING
    val isStarting = busy ||
        runtimeState == PrivilegedRuntime.State.STARTING ||
        runtimeState == PrivilegedRuntime.State.PAIRED_IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Status badge ────────────────────────────────
        StatusBadge(runtimeState)

        Spacer(Modifier.height(4.dp))

        // ── Main content ────────────────────────────────
        if (isRunning) {
            SuccessCard(runtimeState, context)
        } else if (isStarting) {
            StartingCard()
        } else {
            PairingContent(
                port = pairingPort,
                onPortChange = { pairingPort = it.filter { c -> c.isDigit() }.take(5) },
                code = pairingCode,
                onCodeChange = { pairingCode = it.filter { c -> c.isDigit() }.take(6) },
                portDetected = portDetected,
                busy = busy,
                errorMsg = errorMsg,
                onPair = {
                    val port = pairingPort.toIntOrNull() ?: 0
                    if (port <= 0) {
                        errorMsg = "Port invalide — active le débogage sans fil d'abord"
                        return@PairingContent
                    }
                    if (pairingCode.length != 6) {
                        errorMsg = "Le code doit faire 6 chiffres"
                        return@PairingContent
                    }
                    operationScope.launch {
                        busy = true
                        errorMsg = null
                        PrivilegedRuntime.pairAndStart(
                            context,
                            pairingHost,
                            port,
                            pairingCode
                        ).onSuccess {
                            Log.d(TAG, "Pairing succeeded!")
                            PairingNotifier.onPairingSucceeded(context)
                            busy = false
                        }.onFailure { e ->
                            Log.e(TAG, "Pairing failed", e)
                            val friendly = friendlyErrorMessage(e)
                            errorMsg = friendly
                            PairingNotifier.onPairingFailed(context, friendly)
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

/** Convert technical exceptions to user-friendly French messages. */
private fun friendlyErrorMessage(e: Throwable): String {
    return when {
        e is TimeoutException -> "Délai dépassé — vérifie que le débogage sans fil est actif"
        e is SocketTimeoutException -> "Connexion expirée — réessaie avec un nouveau code"
        e is ConnectException -> "Impossible de se connecter au port — active le débogage sans fil"
        e.message?.contains("wrong code", true) == true ||
        e.message?.contains("Code invalide", true) == true ->
            "Code incorrect — vérifie le code à 6 chiffres affiché par Android"
        e.message?.contains("PairingContext", true) == true ->
            "Erreur interne du moteur SPAKE2 — réessaie"
        e.message?.contains("exportKeyingMaterial", true) == true ->
            "Erreur TLS — le périphérique ne supporte pas le pairing"
        e.message?.contains("Conscrypt", true) == true ->
            "Erreur crypto — mets à jour Android et réessaie"
        e.message?.contains("socket", true) == true ->
            "Erreur de connexion — vérifie le débogage sans fil"
        e.message?.contains("Pairing failed", true) == true ->
            "Échec du pairing — vérifie le code et le port"
        else -> "Le moteur intégré n'a pas pu démarrer — réessaie après avoir activé le débogage sans fil"
    }
}

@Composable
private fun PairingContent(
    port: String,
    onPortChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    portDetected: Boolean,
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
        // Step 1: Open Dev Settings
        Text(
            "1. Active le débogage sans fil puis tape « Associer avec un code »",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        OutlinedButton(
            onClick = onOpenDevSettings,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Ouvrir les options développeur")
        }

        // Step 2: Port (auto-detected OR manual)
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
                // Port status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (portDetected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                                CircleShape
                            )
                    )
                    Text(
                        if (portDetected) "Port détecté automatiquement"
                        else "Port de pairing",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (portDetected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Port input (always visible, auto-filled by mDNS)
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text("Port (auto-détecté ou manuel)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        textAlign = TextAlign.Center
                    ),
                    isError = errorMsg != null && port.isBlank(),
                    placeholder = { Text("ex: 37329", textAlign = TextAlign.Center) }
                )

                // Step 2 info
                Text(
                    "2. Quand le code à 6 chiffres apparaît, entre-le ci-dessous",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // 6-digit code field (ALWAYS visible)
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text("Code à 6 chiffres") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword
                    ),
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = MaterialTheme.typography.headlineMedium.letterSpacing
                    ),
                    isError = errorMsg != null && code.length != 6,
                    supportingText = errorMsg?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                    placeholder = { Text("000000", textAlign = TextAlign.Center) }
                )

                // Pair button
                Button(
                    onClick = onPair,
                    enabled = !busy && code.length == 6 && port.isNotBlank(),
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
private fun StartingCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Text(
                "Activation du moteur intégré…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                "Le serveur embarqué démarre. Ne ferme pas l'application.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
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
