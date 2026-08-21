package com.coolappstore.everdialer.by.svhp.view.screen.settings

import android.app.DownloadManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coolappstore.everdialer.by.svhp.APP_VERSION
import com.coolappstore.everdialer.by.svhp.GITHUB_API_RELEASES
import com.coolappstore.everdialer.by.svhp.GITHUB_API_RELEASES_LIST
import com.coolappstore.everdialer.by.svhp.controller.util.PreferenceManager
import com.coolappstore.everdialer.by.svhp.controller.util.ReleaseInfo
import com.coolappstore.everdialer.by.svhp.controller.util.enqueueApkDownload
import com.coolappstore.everdialer.by.svhp.controller.util.fetchLatestRelease
import com.coolappstore.everdialer.by.svhp.controller.util.fetchReleaseForVersion
import com.coolappstore.everdialer.by.svhp.controller.util.getApkDestinationFile
import com.coolappstore.everdialer.by.svhp.controller.util.installApkAndScheduleDelete
import com.coolappstore.everdialer.by.svhp.controller.util.isNewerVersion
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val ColorGreen = Color(0xFF4CAF50)
private val ColorAmber = Color(0xFFFFA000)
private val ColorRed = Color(0xFFF44336)

// ─── State machines ────────────────────────────────────────────────────────

private sealed class CheckState {
    object Idle : CheckState()
    object Checking : CheckState()
    data class Done(val latest: ReleaseInfo?, val isNewer: Boolean) : CheckState()
    object Failed : CheckState()
}

private sealed class DownloadState {
    object Idle : DownloadState()
    data class Confirm(val release: ReleaseInfo, val readyToInstall: Boolean) : DownloadState()
    data class Downloading(val release: ReleaseInfo, val downloadId: Long, val progress: Float) : DownloadState()
    object Failed : DownloadState()
}

@Destination<RootGraph>
@Composable
fun UpdatesScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val prefs = koinInject<PreferenceManager>()
    val scope = rememberCoroutineScope()

    var checkState by remember { mutableStateOf<CheckState>(CheckState.Idle) }
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var installedNotes by remember { mutableStateOf<String?>(null) }
    var installedNotesLoaded by remember { mutableStateOf(false) }
    var showCompareSheet by remember { mutableStateOf(false) }

    fun runCheck() {
        scope.launch {
            checkState = CheckState.Checking
            val release = fetchLatestRelease(GITHUB_API_RELEASES)
            checkState = when {
                release == null -> CheckState.Failed
                isNewerVersion(release.tagName, APP_VERSION) -> CheckState.Done(release, true)
                else -> CheckState.Done(release, false)
            }
        }
    }

    // Auto-check the moment the page opens, and silently fetch the installed
    // version's own release notes in the background so they're ready the
    // instant the user wants to compare the two.
    LaunchedEffect(Unit) {
        runCheck()
    }
    LaunchedEffect(Unit) {
        installedNotes = fetchReleaseForVersion(GITHUB_API_RELEASES_LIST, APP_VERSION)?.releaseNotes
        installedNotesLoaded = true
    }

    var screenVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { screenVisible = true }

    // ── Download confirmation + progress flow ──────────────────────────────
    when (val ds = downloadState) {
        is DownloadState.Confirm -> {
            PermissionToDownloadDialog(
                currentVersion = APP_VERSION,
                latestVersion = ds.release.tagName,
                readyToInstall = ds.readyToInstall,
                onConfirm = {
                    if (ds.readyToInstall) {
                        downloadState = DownloadState.Idle
                        installApkAndScheduleDelete(context, getApkDestinationFile())
                    } else {
                        val url = ds.release.apkUrl
                        if (url != null) {
                            val id = enqueueApkDownload(context, url)
                            downloadState = if (id != null) DownloadState.Downloading(ds.release, id, 0f) else DownloadState.Failed
                        } else {
                            downloadState = DownloadState.Failed
                        }
                    }
                },
                onDismiss = { downloadState = DownloadState.Idle }
            )
        }
        is DownloadState.Downloading -> {
            LaunchedEffect(ds.downloadId) {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                while (true) {
                    delay(300)
                    val query = DownloadManager.Query().setFilterById(ds.downloadId)
                    val cursor = dm.query(query)
                    if (!cursor.moveToFirst()) { cursor.close(); break }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    cursor.close()
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            prefs.setString(PreferenceManager.KEY_DOWNLOADED_UPDATE_VERSION, ds.release.tagName)
                            downloadState = DownloadState.Idle
                            installApkAndScheduleDelete(context, getApkDestinationFile())
                            break
                        }
                        DownloadManager.STATUS_FAILED -> {
                            downloadState = DownloadState.Failed
                            break
                        }
                        else -> {
                            val p = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                            downloadState = ds.copy(progress = p)
                        }
                    }
                }
            }
            DownloadingDialog(latestVersion = ds.release.tagName, progress = ds.progress)
        }
        is DownloadState.Failed -> {
            AlertDialog(
                onDismissRequest = { downloadState = DownloadState.Idle },
                shape = RoundedCornerShape(28.dp),
                icon = { Icon(Icons.Default.ErrorOutline, null, tint = ColorRed) },
                title = { Text("Download failed") },
                text = { Text("Something went wrong while downloading the update. Please try again.") },
                confirmButton = {
                    TextButton(onClick = { downloadState = DownloadState.Idle }) { Text("OK") }
                }
            )
        }
        else -> {}
    }

    if (showCompareSheet) {
        val latestForCompare = (checkState as? CheckState.Done)?.latest
        CompareReleaseNotesSheet(
            installedVersion = APP_VERSION,
            installedNotes = installedNotes,
            latestVersion = latestForCompare?.tagName,
            latestNotes = latestForCompare?.releaseNotes,
            onDismiss = { showCompareSheet = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Updates", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { runCheck() },
                        enabled = checkState !is CheckState.Checking
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Check again")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .graphicsLayer {
                    alpha = if (screenVisible) 1f else 0f
                    translationY = if (screenVisible) 0f else 24.dp.toPx()
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UpdateHeroBlob(checkState = checkState)

                Spacer(Modifier.height(20.dp))

                VersionHeaderRow(checkState = checkState)

                Spacer(Modifier.height(20.dp))

                ReleaseNotesBox(checkState = checkState)

                Spacer(Modifier.height(20.dp))

                UpdateActionArea(
                    checkState = checkState,
                    onCheckAgain = { runCheck() },
                    onUpdateClick = { latest ->
                        val apkFile = getApkDestinationFile()
                        val downloadedVersion = prefs.getString(PreferenceManager.KEY_DOWNLOADED_UPDATE_VERSION, null)
                        val readyToInstall = apkFile.exists() && apkFile.length() > 0L && downloadedVersion == latest.tagName
                        downloadState = DownloadState.Confirm(latest, readyToInstall)
                    }
                )

                Spacer(Modifier.height(16.dp))

                TextButton(
                    onClick = { showCompareSheet = true },
                    enabled = installedNotesLoaded
                ) {
                    Icon(Icons.Outlined.Difference, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Compare release notes")
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ─── Decorative animated gradient blob behind the header ──────────────────
@Composable
private fun UpdateHeroBlob(checkState: CheckState) {
    val infinite = rememberInfiniteTransition(label = "heroBlob")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "blobRotation"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "blobPulse"
    )

    val accent = when (checkState) {
        is CheckState.Done -> if (checkState.isNewer) MaterialTheme.colorScheme.primary else ColorGreen
        is CheckState.Failed -> ColorRed
        else -> MaterialTheme.colorScheme.primary
    }
    val accentAnimated by animateColorAsState(targetValue = accent, animationSpec = tween(500), label = "blobColor")

    Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .rotate(rotation)
                .scale(pulse)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            accentAnimated.copy(alpha = 0.35f),
                            accentAnimated.copy(alpha = 0.05f),
                            accentAnimated.copy(alpha = 0.35f)
                        )
                    ),
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = checkState,
                transitionSpec = {
                    (fadeIn(tween(250)) + scaleIn(initialScale = 0.7f)) togetherWith
                        (fadeOut(tween(150)) + scaleOut(targetScale = 0.7f))
                },
                label = "heroIcon"
            ) { state ->
                when (state) {
                    is CheckState.Checking -> {
                        val spin by infinite.animateFloat(
                            initialValue = 0f, targetValue = 360f,
                            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
                            label = "spin"
                        )
                        Icon(
                            Icons.Default.SystemUpdate, null,
                            tint = accentAnimated,
                            modifier = Modifier.size(38.dp).rotate(spin)
                        )
                    }
                    is CheckState.Done -> {
                        if (state.isNewer) {
                            Icon(Icons.Default.NewReleases, null, tint = accentAnimated, modifier = Modifier.size(38.dp))
                        } else {
                            Icon(Icons.Default.CheckCircle, null, tint = accentAnimated, modifier = Modifier.size(38.dp))
                        }
                    }
                    is CheckState.Failed -> Icon(Icons.Default.ErrorOutline, null, tint = accentAnimated, modifier = Modifier.size(38.dp))
                    else -> Icon(Icons.Default.SystemUpdate, null, tint = accentAnimated, modifier = Modifier.size(38.dp))
                }
            }
        }
    }
}

// ─── Version + status badge row ────────────────────────────────────────────
@Composable
private fun VersionHeaderRow(checkState: CheckState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Ever Dialer",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "v$APP_VERSION",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold
            )

            AnimatedContent(
                targetState = checkState,
                transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 }) },
                label = "statusBadge"
            ) { state ->
                when (state) {
                    is CheckState.Checking -> StatusChip("Checking…", MaterialTheme.colorScheme.primary, animated = true)
                    is CheckState.Done -> if (state.isNewer) {
                        StatusChip("Update required", ColorAmber)
                    } else {
                        StatusChip("Latest", ColorGreen)
                    }
                    is CheckState.Failed -> StatusChip("Check failed", ColorRed)
                    else -> Spacer(Modifier.width(1.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color, animated: Boolean = false) {
    val infinite = rememberInfiniteTransition(label = "chipPulse")
    val alphaAnim by infinite.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "chipAlpha"
    )
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        modifier = Modifier.alpha(if (animated) alphaAnim else 1f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

// ─── Release notes box ──────────────────────────────────────────────────────
@Composable
private fun ReleaseNotesBox(checkState: CheckState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = 320.dp)
                .padding(20.dp)
        ) {
            AnimatedContent(
                targetState = checkState,
                transitionSpec = { (fadeIn(tween(280)) togetherWith fadeOut(tween(140))) },
                label = "notesContent"
            ) { state ->
                when (state) {
                    CheckState.Idle, is CheckState.Checking -> NotesShimmerPlaceholder()

                    is CheckState.Done -> if (state.isNewer && state.latest != null) {
                        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    "What's new in v${state.latest.tagName}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(10.dp))
                                ReleaseNotesText(state.latest.releaseNotes)
                            }
                        }
                    } else {
                        NoUpdatesNotice()
                    }

                    is CheckState.Failed -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Couldn't reach the update server",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoUpdatesNotice() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }
        val scale by animateFloatAsState(
            targetValue = if (appeared) 1f else 0.6f,
            animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "noUpdateScale"
        )
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = ColorGreen,
            modifier = Modifier.size(40.dp).scale(scale)
        )
        Spacer(Modifier.height(10.dp))
        Text("No new updates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "You're already running the latest version of Ever Dialer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NotesShimmerPlaceholder() {
    val infinite = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by infinite.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "shimmerX"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val highlight = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(1f, 0.85f, 0.92f, 0.6f).forEach { widthFraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(base, highlight, base),
                            start = Offset(shimmerX * 300f, 0f),
                            end = Offset(shimmerX * 300f + 300f, 0f)
                        )
                    )
            )
        }
    }
}

/** Very small markdown-lite renderer for GitHub release note bodies. */
@Composable
private fun ReleaseNotesText(rawNotes: String?) {
    if (rawNotes.isNullOrBlank()) {
        Text(
            "No release notes were provided for this version.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rawNotes.lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                line.startsWith("#") -> Text(
                    line.trimStart('#').trim(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                line.startsWith("- ") || line.startsWith("* ") -> Row(verticalAlignment = Alignment.Top) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        line.removePrefix("- ").removePrefix("* ").replace("**", ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> Text(
                    line.replace("**", ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ─── Action area: big pill button / no-updates state ───────────────────────
@Composable
private fun UpdateActionArea(
    checkState: CheckState,
    onCheckAgain: () -> Unit,
    onUpdateClick: (ReleaseInfo) -> Unit
) {
    AnimatedContent(
        targetState = checkState,
        transitionSpec = { (fadeIn(tween(260)) + slideInVertically { it / 3 }) togetherWith (fadeOut(tween(140))) },
        label = "actionArea"
    ) { state ->
        when {
            state is CheckState.Done && state.isNewer && state.latest != null -> {
                Button(
                    onClick = { onUpdateClick(state.latest) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Update to v${state.latest.tagName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
            state is CheckState.Failed -> {
                OutlinedButton(
                    onClick = onCheckAgain,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Try Again", fontWeight = FontWeight.Bold)
                }
            }
            state is CheckState.Checking || state == CheckState.Idle -> {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(50)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking for updates…")
                }
            }
            else -> {
                // Up to date — no update button, just a subtle re-check affordance.
                OutlinedButton(
                    onClick = onCheckAgain,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Check Again", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ─── Permission-to-download confirmation dialog ────────────────────────────
@Composable
private fun PermissionToDownloadDialog(
    currentVersion: String,
    latestVersion: String,
    readyToInstall: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.85f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "confirmScale"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.scale(scale),
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                if (readyToInstall) Icons.Default.InstallMobile else Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(if (readyToInstall) "Install update?" else "Download update?") },
        text = {
            Text(
                if (readyToInstall)
                    "Ever Dialer v$latestVersion has already been downloaded. Install it now?"
                else
                    "Ever Dialer v$latestVersion is available (you have v$currentVersion). This will download the APK to your Downloads folder — nothing happens until you confirm."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, shape = RoundedCornerShape(50)) {
                Text(if (readyToInstall) "Install Now" else "Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not Now") }
        }
    )
}

@Composable
private fun DownloadingDialog(latestVersion: String, progress: Float) {
    val animatedProgress by animateFloatAsState(targetValue = progress, animationSpec = tween(250), label = "dlProgress")
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(Icons.Default.Downloading, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Downloading v$latestVersion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                )
                Text("${(animatedProgress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = {}
    )
}

// ─── Compare release notes bottom sheet ─────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareReleaseNotesSheet(
    installedVersion: String,
    installedNotes: String?,
    latestVersion: String?,
    latestNotes: String?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Installed, 1 = Latest

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Compare Release Notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            val hasLatest = latestVersion != null
            if (hasLatest) {
                SingleChoiceSegmented(
                    options = listOf("Installed  ·  v$installedVersion", "Latest  ·  v${latestVersion}"),
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it }
                )
                Spacer(Modifier.height(16.dp))
            } else {
                Text(
                    "No newer version to compare — showing your installed release notes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            AnimatedContent(
                targetState = if (hasLatest) selectedTab else 0,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInVertically { it / 4 } + fadeIn()) togetherWith (fadeOut())
                    } else {
                        (slideInVertically { -it / 4 } + fadeIn()) togetherWith (fadeOut())
                    }
                },
                label = "compareContent"
            ) { tab ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.heightIn(min = 160.dp, max = 380.dp).padding(18.dp)) {
                        SelectionContainer {
                            androidx.compose.foundation.lazy.LazyColumn {
                                item {
                                    ReleaseNotesText(if (tab == 0) installedNotes else latestNotes)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(50)
            ) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun SingleChoiceSegmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                animationSpec = tween(220),
                label = "segBg"
            )
            val fg by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(220),
                label = "segFg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .padding(vertical = 10.dp)
                    .then(Modifier.clickableNoRipple { onSelect(index) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}
