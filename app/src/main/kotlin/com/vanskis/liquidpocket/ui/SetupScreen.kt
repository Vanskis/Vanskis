package com.vanskis.liquidpocket.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.download.DownloadStatus
import com.vanskis.liquidpocket.download.NetworkKind
import com.vanskis.liquidpocket.download.SetupViewModel
import com.vanskis.liquidpocket.download.percent
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onOpenChat: () -> Unit, vm: SetupViewModel = viewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val mediaStatus by vm.mediaStatus.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The model a dialog (or the notification permission request) is about; null when none is open.
    var askMetered by remember { mutableStateOf<ModelSpec?>(null) }
    var askDelete by remember { mutableStateOf<ModelSpec?>(null) }
    // Saved as the model id, so a rotation with the permission dialog up still starts the download.
    var requestedId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = status == DownloadStatus.Ready, onBack = onOpenChat)

    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }

    fun startDownload(spec: ModelSpec) {
        when (vm.currentNetwork()) {
            NetworkKind.METERED -> askMetered = spec
            // With no network the job waits for Wi-Fi.
            NetworkKind.NONE, NetworkKind.UNMETERED -> vm.download(spec, allowMetered = false)
        }
    }

    // Ask for the notification permission first so the progress notification can show; the
    // download starts whatever the answer is.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        listOf(vm.model, vm.mediaModel).firstOrNull { it.id == requestedId }?.let(::startDownload)
        requestedId = null
    }
    fun onDownload(spec: ModelSpec) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startDownload(spec)
        } else {
            requestedId = spec.id
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Set up Liquid Pocket") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ModelSection(
                spec = vm.model,
                about = "About ${formatBytes(vm.model.approxSizeBytes)}, then " +
                    "${formatBytes(vm.mediaModel.approxSizeBytes)} for photos and voice. Downloaded once from " +
                    "Hugging Face; after that everything runs on this phone without a network.",
                status = status,
                onDownload = { onDownload(vm.model) },
                onCancel = { vm.cancel(vm.model) },
                onDelete = { askDelete = vm.model },
                onOpenChat = onOpenChat,
            )
            HorizontalDivider()
            ModelSection(
                spec = vm.mediaModel,
                about = "About ${formatBytes(vm.mediaModel.approxSizeBytes)}. Optional: answers photos and voice " +
                    "messages. Downloads after ${vm.model.displayName}; you can chat meanwhile.",
                status = mediaStatus,
                onDownload = { onDownload(vm.mediaModel) },
                onCancel = { vm.cancel(vm.mediaModel) },
                onDelete = { askDelete = vm.mediaModel },
            )
        }
    }

    askMetered?.let { spec ->
        // The main model brings the media model after it, on the same networks.
        val bytes = spec.approxSizeBytes + if (spec == vm.model) vm.mediaModel.approxSizeBytes else 0L
        AlertDialog(
            onDismissRequest = { askMetered = null },
            title = { Text("Not on Wi-Fi") },
            text = {
                Text(
                    "You are on a metered connection. The download is about " +
                        "${formatBytes(bytes)}. Wait for Wi-Fi, or download now?",
                )
            },
            confirmButton = {
                TextButton(onClick = { askMetered = null; vm.download(spec, allowMetered = true) }) { Text("Download anyway") }
            },
            dismissButton = {
                TextButton(onClick = { askMetered = null; vm.download(spec, allowMetered = false) }) { Text("Wait for Wi-Fi") }
            },
        )
    }

    askDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text("Delete ${spec.displayName}?") },
            text = {
                Text(
                    if (spec == vm.model) "You will need to download it again to chat."
                    else "Photos and voice messages need it; you can download it again here.",
                )
            },
            confirmButton = { TextButton(onClick = { askDelete = null; vm.delete(spec) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("Keep") } },
        )
    }
}

/** One model: its name, [about] text, download status and buttons; Open chat shows when [onOpenChat] is given and it is ready. */
@Composable
private fun ModelSection(
    spec: ModelSpec,
    about: String,
    status: DownloadStatus?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpenChat: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(spec.displayName, style = MaterialTheme.typography.headlineSmall)
        Text(about, style = MaterialTheme.typography.bodyMedium)
        when (status) {
            null -> CircularProgressIndicator()
            else -> StatusSection(status)
        }
        if (status != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    status == DownloadStatus.Ready -> onOpenChat?.let { Button(onClick = it) { Text("Open chat") } }
                    status.active -> Button(onClick = onCancel) { Text("Cancel") }
                    else -> Button(onClick = onDownload) {
                        Text(if (status is DownloadStatus.NotDownloaded && status.partialBytes > 0) "Resume download" else "Download")
                    }
                }
                val deletable = status == DownloadStatus.Ready || status is DownloadStatus.Failed ||
                    (status is DownloadStatus.NotDownloaded && status.partialBytes > 0)
                if (deletable) OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
        }
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
