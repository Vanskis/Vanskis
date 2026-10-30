package com.vanskis.liquidpocket.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.MEDIA_MODEL
import com.vanskis.liquidpocket.access.SharedInbox
import com.vanskis.liquidpocket.chat.ChatViewModel
import com.vanskis.liquidpocket.chat.LiveVoiceModel
import com.vanskis.liquidpocket.chat.modelStatus
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.media.AndroidMediaLoader
import com.vanskis.liquidpocket.ui.components.ChatLayout
import com.vanskis.liquidpocket.ui.components.ChatMediaUi
import com.vanskis.liquidpocket.ui.components.LocalGalleryThumbnail
import com.vanskis.liquidpocket.ui.components.LocalIndicators
import com.vanskis.liquidpocket.ui.components.LocalImageThumbnail
import com.vanskis.liquidpocket.ui.components.LocalMathRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long side of the decoded thumbnails; enough for the 200 dp single-photo bubble. */
private const val THUMBNAIL_SIDE = 400

@Composable
fun ChatScreen(onOpenSettings: () -> Unit, vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val speakReplies by vm.speakReplies.collectAsStateWithLifecycle()
    val indicators by vm.indicators.collectAsStateWithLifecycle()
    val recordingMillis by vm.recordingMillis.collectAsStateWithLifecycle()
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val think by vm.think.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val extracting by vm.extracting.collectAsStateWithLifecycle()
    val shared by SharedInbox.pendingDocuments.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    LaunchedEffect(Unit) { vm.onChatOpened() }

    // A tool asks for a permission the first time it needs it; the answer goes back to the tool.
    val permissionRequest by vm.permissions.request.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.permissions.onResult()
    }
    LaunchedEffect(permissionRequest) {
        permissionRequest?.let { if (it.markLaunched()) permissionLauncher.launch(it.permissions.toTypedArray()) }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attachPhoto(uri)
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachDocument(uri)
    }
    // Saved, as the camera app may outlive this activity.
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        capturePath?.let { vm.onCameraResult(it, taken) }
        capturePath = null
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleMic() else vm.showError("Voice input needs the microphone. You can allow it in the app's settings.")
    }

    val onMic = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        // A tap while recording stops it; otherwise the permission is asked for on first use.
        if (recordingMillis != null || granted) vm.toggleMic() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Live voice listens continuously, so it ends when the app leaves the screen.
    val askMicForLive = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startLive() else vm.showError("Live voice needs the microphone. You can allow it in the app's settings.")
    }
    val onLive = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.startLive() else askMicForLive.launch(Manifest.permission.RECORD_AUDIO)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopLive() }
    val cameraGranted = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val startCapture = {
        val (file, uri) = vm.newCameraCapture()
        capturePath = file.path
        try {
            takePhoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            capturePath = null
            vm.onCameraResult(file.path, taken = false)
            vm.showError("No camera app is available.")
        }
    }
    val askCameraForPhoto = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCapture() else vm.showError("Taking a photo needs the camera. You can allow it in the app's settings.")
    }
    val askCameraForLive = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleLiveCamera() else vm.showError("The camera needs permission. You can allow it in the app's settings.")
    }

    // The widget's mic button opens a new chat listening, its Ask one with the keyboard up (access/LaunchRequest.kt).
    val launch by vm.launch.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(launch) {
        val request = launch ?: return@LaunchedEffect
        if (request.startVoice && recordingMillis == null) onMic()
        if (request.focusInput) {
            withFrameNanos {} // the field is attached to the requester once a frame is laid out
            focusRequester.requestFocus()
            keyboard?.show()
        }
        // Last: clearing it recomposes with a new key, which would cancel this effect mid-wait.
        vm.launchHandled()
    }

    val status = modelStatus(engineState, state)
    // The engine reports its context size only while Ready; keep it through Generating.
    var contextTotal by remember { mutableIntStateOf(0) }
    LaunchedEffect(engineState) {
        (engineState as? EngineState.Ready)?.let { contextTotal = it.contextSize }
    }
    val media = ChatMediaUi(
        pending = pending,
        onRemovePending = vm::removePending,
        extracting = extracting,
        onCancelExtraction = { vm.cancelExtraction(it.key) },
        shared = shared,
        onUseShared = vm::useSharedDocument,
        onPickPhoto = {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onPickDocument = { pickDocument.launch(arrayOf("application/pdf", "text/*")) },
        focusRequester = focusRequester,
        // With the CAMERA permission declared (live voice), the camera app also needs it granted.
        onTakePhoto = if (!hasCamera) null else fun() {
            if (cameraGranted()) startCapture() else askCameraForPhoto.launch(Manifest.permission.CAMERA)
        },
        onMic = onMic,
        recordingMillis = recordingMillis,
        transcript = transcript,
        onTranscriptUsed = vm::transcriptUsed,
        speakReplies = speakReplies,
        onToggleSpeakReplies = vm::toggleSpeakReplies,
        onLive = onLive,
    )

    // Full screen over the chat, which the turns still land in; Back closes it.
    live?.let { ui ->
        Dialog(onDismissRequest = { vm.stopLive() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LiveVoiceScreen(
                live = ui,
                modelName = if (ui.model == LiveVoiceModel.OMNI) MEDIA_MODEL.displayName else ACTIVE_MODEL.displayName,
                onClose = { vm.stopLive() },
                // Omni sees; Android's recognizer and 1.7B do not.
                onToggleCamera = if (ui.model != LiveVoiceModel.OMNI || !hasCamera) null else fun() {
                    if (ui.camera != null || cameraGranted()) vm.toggleLiveCamera() else askCameraForLive.launch(Manifest.permission.CAMERA)
                },
                onFlipCamera = vm::flipLiveCamera,
                preview = { lens, modifier -> LiveCameraPreview(lens, vm::setLiveFrameSource, modifier) },
            )
        }
    }

    val placeholder = LocalImageThumbnail.current
    val thumbnail = remember(placeholder) {
        @Composable { path: String, modifier: Modifier -> ImageThumbnail(path, modifier, placeholder) }
    }
    val gallery = remember(placeholder) {
        @Composable { uri: String, modifier: Modifier -> GalleryThumbnail(uri, modifier, placeholder) }
    }
    val math = remember { JLatexMathRenderer(context) }
    CompositionLocalProvider(
        LocalImageThumbnail provides thumbnail,
        LocalGalleryThumbnail provides gallery,
        LocalIndicators provides indicators,
        LocalMathRenderer provides math,
    ) {
        ChatLayout(
            state = state,
            status = status,
            modelName = ACTIVE_MODEL.displayName,
            conversations = conversations,
            actions = vm,
            // Android 13+ shows its own confirmation when something is copied, so no snackbar here.
            onCopy = { text ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Liquid Pocket reply", text))
            },
            onShare = { title, markdown ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, markdown)
                }
                context.startActivity(Intent.createChooser(send, "Share chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onOpenSettings = onOpenSettings,
            media = media,
            think = think,
            onToggleThink = vm::toggleThink,
            skills = skills,
            contextTotal = contextTotal,
            onCompact = vm::compact,
        )
    }
}

/** The gallery photo at content [uri], from the system's thumbnail cache; [placeholder] if it cannot be read. */
@Composable
private fun GalleryThumbnail(
    uri: String,
    modifier: Modifier,
    placeholder: @Composable (path: String, modifier: Modifier) -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val decoded by produceState<Result<ImageBitmap>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                resolver.loadThumbnail(Uri.parse(uri), android.util.Size(THUMBNAIL_SIDE, THUMBNAIL_SIDE), null).asImageBitmap()
            }
        }
    }
    val result = decoded
    when {
        result == null -> Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
        result.isSuccess -> Image(
            bitmap = result.getOrThrow(),
            contentDescription = "Photo the model looked at",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        else -> placeholder(uri, modifier)
    }
}

/** The photo at [path], decoded upright off the main thread; [placeholder] if it cannot be read. */
@Composable
private fun ImageThumbnail(
    path: String,
    modifier: Modifier,
    placeholder: @Composable (path: String, modifier: Modifier) -> Unit,
) {
    val decoded by produceState<Result<ImageBitmap>?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { AndroidMediaLoader.decodeUpright(path, THUMBNAIL_SIDE).asImageBitmap() }
        }
    }
    val result = decoded
    when {
        result == null -> Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
        result.isSuccess -> Image(
            bitmap = result.getOrThrow(),
            contentDescription = "Attached photo",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        else -> placeholder(path, modifier)
    }
}
