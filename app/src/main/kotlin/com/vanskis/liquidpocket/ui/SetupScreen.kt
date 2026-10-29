package com.vanskis.liquidpocket.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanskis.liquidpocket.download.DownloadStatus
import com.vanskis.liquidpocket.download.NetworkKind
import com.vanskis.liquidpocket.download.SetupViewModel
import com.vanskis.liquidpocket.download.percent
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onOpenChat: () -> Unit, vm: SetupViewModel = viewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var askMetered by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = status == DownloadStatus.Ready, onBack = onOpenChat)

    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }

    fun startDownload() {
        when (vm.currentNetwork()) {
            NetworkKind.METERED -> askMetered = true
            // With no network the job waits for Wi-Fi.
            NetworkKind.NONE, NetworkKind.UNMETERED -> vm.download(allowMetered = false)
        }
    }

    // Ask for the notification permission first so the progress notification can show; the
    // download starts whatever the answer is.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startDownload()
    }
    val onDownload = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startDownload() else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Set up Liquid Pocket") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(vm.model.displayName, style = MaterialTheme.typography.headlineSmall)
            Text(
                "About ${formatBytes(vm.model.approxSizeBytes)}. Downloaded once from Hugging Face; " +
                    "after that everything runs on this phone without a network.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when (val s = status) {
                null -> CircularProgressIndicator()
                else -> StatusSection(s)
            }
            val s = status
            if (s != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        s == DownloadStatus.Ready -> Button(onClick = onOpenChat) { Text("Open chat") }
                        s.active -> Button(onClick = { vm.cancel() }) { Text("Cancel") }
                        else -> Button(onClick = onDownload) {
                            Text(if (s is DownloadStatus.NotDownloaded && s.partialBytes > 0) "Resume download" else "Download")
                        }
                    }
                    val deletable = s == DownloadStatus.Ready || s is DownloadStatus.Failed ||
                        (s is DownloadStatus.NotDownloaded && s.partialBytes > 0)
                    if (deletable) OutlinedButton(onClick = { askDelete = true }) { Text("Delete") }
                }
            }
        }
    }

    if (askMetered) {
        AlertDialog(
            onDismissRequest = { askMetered = false },
            title = { Text("Not on Wi-Fi") },
            text = {
                Text(
                    "You are on a metered connection. The model is about " +
                        "${formatBytes(vm.model.approxSizeBytes)}. Wait for Wi-Fi, or download now?",
                )
            },
            confirmButton = {
                TextButton(onClick = { askMetered = false; vm.download(allowMetered = true) }) { Text("Download anyway") }
            },
            dismissButton = {
                TextButton(onClick = { askMetered = false; vm.download(allowMetered = false) }) { Text("Wait for Wi-Fi") }
            },
        )
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete the model?") },
            text = { Text("You will need to download it again to chat.") },
            confirmButton = { TextButton(onClick = { askDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun StatusSection(status: DownloadStatus) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            is DownloadStatus.NotDownloaded -> Text(
                if (status.partialBytes > 0) "Paused: ${formatBytes(status.partialBytes)} downloaded" else "Not downloaded",
            )
            DownloadStatus.Waiting -> {
                Text("Waiting for Wi-Fi…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            DownloadStatus.Resolving -> {
                Text("Looking up the model file…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is DownloadStatus.Downloading -> {
                Text(
                    "Downloading ${percent(status.bytes, status.total)}% " +
                        "(${formatBytes(status.bytes)} of ${formatBytes(status.total)})",
                )
                LinearProgressIndicator(
                    progress = { percent(status.bytes, status.total) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is DownloadStatus.Verifying -> {
                Text("Verifying ${percent(status.bytes, status.total)}%")
                LinearProgressIndicator(
                    progress = { percent(status.bytes, status.total) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DownloadStatus.Ready -> Text("Ready", color = MaterialTheme.colorScheme.primary)
            is DownloadStatus.Failed -> Text("Error: ${status.message}", color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1_000_000_000L) String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
    else String.format(Locale.ROOT, "%d MB", bytes / 1_000_000L)
