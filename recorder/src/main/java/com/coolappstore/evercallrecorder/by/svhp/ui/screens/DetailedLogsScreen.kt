package com.coolappstore.evercallrecorder.by.svhp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coolappstore.evercallrecorder.by.svhp.system.copyToClipboard
import com.coolappstore.evercallrecorder.by.svhp.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailedLogsScreen(
    onBack: () -> Unit,
    onExportLogs: (android.net.Uri) -> Unit = {}
) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(AppLogger.readLogs()) }
    val exportLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri -> if (uri != null) onExportLogs(uri) }
    )

    LaunchedEffect(Unit) {
        while (true) {
            logs = withContext(Dispatchers.IO) { AppLogger.readLogs() }
            delay(1_000)
        }
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Detailed logs",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            exportLogLauncher.launch("ever-dialer-runtime-logs.log")
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = "Download .log file"
                        )
                    }
                    IconButton(
                        onClick = {
                            context.copyToClipboard(
                                label = "Ever Dialer logs",
                                text = logs.ifBlank { "No logs recorded yet." }
                            )
                        },
                        enabled = logs.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy all logs"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        val scrollState: ScrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = logs.ifBlank {
                    "No logs recorded yet.\n\nEnable log recording in Settings, reproduce the issue, then return here."
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PaddingValues(16.dp)),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}