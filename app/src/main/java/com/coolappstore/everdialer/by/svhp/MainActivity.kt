package com.coolappstore.everdialer.by.svhp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.coolappstore.evercallrecorder.by.svhp.ui.screens.DisclaimerScreen
import com.coolappstore.evercallrecorder.by.svhp.ui.screens.HomeScreen
import com.coolappstore.evercallrecorder.by.svhp.ui.screens.PermissionsScreen
import com.coolappstore.evercallrecorder.by.svhp.ui.screens.PlaybackScreen
import com.coolappstore.evercallrecorder.by.svhp.ui.screens.AppLockScreen
import com.coolappstore.evercallrecorder.by.svhp.data.AppPreferences
import com.coolappstore.evercallrecorder.by.svhp.ui.viewmodels.AppNavigationViewModel
import com.coolappstore.evercallrecorder.by.svhp.ui.viewmodels.AppLockViewModel
import com.coolappstore.evercallrecorder.by.svhp.ui.viewmodels.HomeViewModel
import com.coolappstore.evercallrecorder.by.svhp.ui.viewmodels.RecordingItem
import com.coolappstore.everdialer.by.svhp.sync.SyncManager
import com.coolappstore.everdialer.by.svhp.sync.SyncRole
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestRequiredPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EverEmetteurApp()
            }
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }
}

// ─── Main App Composable ────────────────────────────────────────────────────

@Composable
private fun EverEmetteurApp() {
    var showSettings by remember { mutableStateOf(false) }
    var openPlayback by remember { mutableStateOf<RecordingItem?>(null) }

    AnimatedContent(
        targetState = showSettings,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen"
    ) { isSettings ->
        if (isSettings) {
            EmitterSettingsPage(onBack = { showSettings = false })
        } else {
            if (openPlayback != null) {
                PlaybackScreen(
                    recording = openPlayback!!,
                    onBack = { openPlayback = null },
                    highlightQuery = ""
                )
            } else {
                EmitterRecordingsPage(
                    onSettingsClick = { showSettings = true },
                    onRecordingClick = { item, _ -> openPlayback = item }
                )
            }
        }
    }
}

// ─── Recordings Page (main screen with gear icon top-right) ─────────────────

@Composable
private fun EmitterRecordingsPage(
    onSettingsClick: () -> Unit,
    onRecordingClick: (RecordingItem, String) -> Unit
) {
    val context = LocalContext.current
    val appNavViewModel: AppNavigationViewModel = viewModel()
    val onboardingStatus by appNavViewModel.onboardingStatus.collectAsState()
    val appLockViewModel: AppLockViewModel = viewModel()
    val isAppLockUnlocked by appLockViewModel.isUnlocked.collectAsState()
    val preferences = remember { AppPreferences(context) }
    val recordingsVM: HomeViewModel = viewModel()

    val isAppLocked = onboardingStatus.disclaimerAccepted && onboardingStatus.isComplete() &&
        preferences.isAppLockEnabled() && !isAppLockUnlocked

    LaunchedEffect(Unit) { appNavViewModel.refresh() }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            !onboardingStatus.disclaimerAccepted -> {
                DisclaimerScreen(onContinue = {
                    AppPreferences(context).setDisclaimerAccepted(true)
                    appNavViewModel.refresh()
                })
            }
            !onboardingStatus.isComplete() -> {
                PermissionsScreen(
                    status = onboardingStatus,
                    onPermissionGranted = { appNavViewModel.refresh() },
                    onSkip = { appNavViewModel.skipOnboarding() }
                )
            }
            isAppLocked -> {
                AppLockScreen(
                    method = preferences.getAppLockMethod(),
                    onVerifySecret = { secret -> preferences.verifyAppLockSecret(secret) },
                    onUnlocked = { appLockViewModel.unlock() }
                )
            }
            else -> {
                val appVersion = remember {
                    try {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                    } catch (_: Exception) { "" }
                }
                HomeScreen(
                    appVersion = appVersion,
                    onSettingsClick = onSettingsClick,
                    onRecordingClick = onRecordingClick,
                    onSelectionModeChanged = {},
                    onGlobalSearchClick = {},
                    onEverDialerSettingsClick = onSettingsClick
                )
            }
        }
    }
}

// ─── Settings Page (full-screen, accessed from gear icon) ───────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmitterSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by SyncManager.state.collectAsState()
    val preferences = remember { AppPreferences(context) }
    var showSyncSettings by remember { mutableStateOf(false) }

    if (showSyncSettings) {
        SyncSettingsPage(onBack = { showSyncSettings = false })
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Réglages", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            // ── Shizuku Embarqué ─────────────────────────────────────────
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Shizuku Embarqué", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Le serveur Shizuku est intégré directement. " +
                        "Pour activer l'enregistrement d'appels sans bruit, activez le Débogage sans fil dans " +
                        "Paramètres → Système → Options pour développeurs, puis appairer ici.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Ouvrir les Options Développeur")
                    }
                }
            }

            // ── Permissions ──────────────────────────────────────────────
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Permissions", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))

                    val perms = listOf(
                        "État du téléphone" to (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED),
                        "Journal d'appels" to (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED),
                        "Notifications" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
                        "Microphone" to (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
                    )
                    perms.forEach { (name, granted) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (granted) Icons.Default.CheckCircle else Icons.Outlined.Cancel,
                                null,
                                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                if (granted) "Accordé" else "Manquant",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Gérer les permissions")
                    }
                }
            }

            // ── Format Audio ─────────────────────────────────────────────
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AudioFile, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(10.dp))
                        Text("Format d'enregistrement", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))

                    val currentCodecKey = remember { preferences.getAudioCodec() }
                    val currentSourceKey = remember { preferences.getAudioSource() }
                    val codecLabel = when (currentCodecKey) {
                        "opus" -> "Opus (recommandé, ~16 kbps)"
                        "aac" -> "AAC (~64 kbps)"
                        else -> "Opus (recommandé, ~16 kbps)"
                    }
                    val sourceLabel = when (currentSourceKey) {
                        "voice-call" -> "Voice Call (toute la ligne)"
                        "voice-call-uplink" -> "Uplink (ma voix)"
                        "voice-call-downlink" -> "Downlink (l'autre personne)"
                        else -> "Voice Call (toute la ligne)"
                    }

                    Text(
                        "Codec : $codecLabel",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Source : $sourceLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Configurer le format")
                    }
                }
            }

            // ── Synchronisation P2P ──────────────────────────────────────
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Sync, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(10.dp))
                        Text("Synchronisation P2P", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Envoyer les enregistrements et l'historique d'appels au téléphone B (Ever Client) " +
                        "via le réseau local, sans serveur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            state.role != SyncRole.SENDER -> "Non jumelé"
                            !state.enabled -> "Désactivé"
                            else -> "Jumelé · ${state.peerName.ifBlank { "Téléphone B" }}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (state.enabled && state.role == SyncRole.SENDER)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showSyncSettings = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Configurer la synchronisation")
                    }
                }
            }

            // ── Version ──────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            Text(
                "Ever Émetteur v${try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "?" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

// ─── Sync Settings (full-screen) ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by SyncManager.state.collectAsState()
    val logs by SyncManager.logs.collectAsState()
    var pairingInput by remember { mutableStateOf("") }
    var emitterPairingCode by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Synchronisation P2P", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                            maxLines = 3
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

            // Paste receiver code
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

            // Generate emitter code
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
                                    as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("ever-pairing", code))
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
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
