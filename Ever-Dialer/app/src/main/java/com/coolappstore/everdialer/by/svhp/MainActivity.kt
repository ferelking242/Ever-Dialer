package com.coolappstore.everdialer.by.svhp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.coolappstore.evercallrecorder.by.svhp.ui.screens.SettingsScreen
import com.coolappstore.evercallrecorder.by.svhp.data.AppPreferences
import com.coolappstore.evercallrecorder.by.svhp.ui.viewmodels.AppNavigationViewModel
import com.coolappstore.evercallrecorder.by.svhp.ui.viewmodels.AppLockViewModel
import com.coolappstore.evercallrecorder.by.svhp.ui.viewmodels.RecordingItem
import com.coolappstore.evercallrecorder.by.svhp.ui.viewmodels.SettingsViewModel
import com.coolappstore.everdialer.by.svhp.sync.SyncManager
import com.coolappstore.everdialer.by.svhp.sync.SyncRole
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.lifecycle.viewmodel.compose.viewModel

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
    var showSyncPage by remember { mutableStateOf(false) }
    var openPlayback by remember { mutableStateOf<RecordingItem?>(null) }

    // When P2P sync page is open, show it full-screen (NOT inside a LazyColumn)
    if (showSyncPage) {
        P2pSyncPage(onBack = { showSyncPage = false })
        return
    }

    AnimatedContent(
        targetState = showSettings,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen"
    ) { isSettings ->
        if (isSettings) {
            // Real recorder SettingsScreen with P2P section injected at bottom
            val settingsViewModel: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { showSettings = false },
                extraContent = { P2PSection(onOpenSync = { showSyncPage = true }) }
            )
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

// ─── Recordings Page (uses real HomeScreen from recorder module) ────────────

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
                    onEverDialerSettingsClick = onSettingsClick
                )
            }
        }
    }
}

// ─── P2P Section (injected into real SettingsScreen) ───────────────────────

@Composable
private fun P2PSection(onOpenSync: () -> Unit = {}) {
    val context = LocalContext.current
    val state by SyncManager.state.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(8.dp))

        // Synchronisation P2P header
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Sync, null, tint = MaterialTheme.colorScheme.primary)
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
                OutlinedButton(
                    onClick = onOpenSync,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Configurer la synchronisation")
                }
            }
        }
    }
}

// ─── P2P Sync Page (full-screen, accessed from settings) ───────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun P2pSyncPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by SyncManager.state.collectAsState()
    val logs by SyncManager.logs.collectAsState()
    var pairingInput by remember { mutableStateOf("") }
    var emitterPairingCode by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE) }

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
                            "Dernier envoi : ${dateFormat.format(java.util.Date(state.lastSyncAt))}" +
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
                                Icon(Icons.Outlined.ContentCopy, "Copier")
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
