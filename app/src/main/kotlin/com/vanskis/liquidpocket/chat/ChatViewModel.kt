package com.vanskis.liquidpocket.chat

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.SMART_MODEL
import com.vanskis.liquidpocket.access.LaunchRequest
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.download.DownloadStatus
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.media.Dictation
import com.vanskis.liquidpocket.media.ReplyAudioPlayer
import com.vanskis.liquidpocket.media.ReplySpeaker
import com.vanskis.liquidpocket.media.VoiceRecorder
import com.vanskis.liquidpocket.settings.AppSettings
import com.vanskis.liquidpocket.tools.PermissionGate
import com.vanskis.liquidpocket.tools.WebTools
import com.vanskis.liquidpocket.tools.phoneTools
import com.vanskis.liquidpocket.ui.components.ChatActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Holds the open conversation for the Chat screen; it survives visits to Settings and Setup. */
class ChatViewModel(app: Application) : AndroidViewModel(app), ChatActions {
    private val graph = app as LiquidPocketApp
    private val files = graph.attachments

    // Released in onCleared, so they belong to the view model rather than the process.
    private val player = ReplyAudioPlayer()
    private val speaker = ReplySpeaker(app)
    private val recorder = VoiceRecorder()
    private val dictation = Dictation(app)

    private val settings: StateFlow<AppSettings> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Plays the model's speech as it streams; Android reads the finished reply when the model cannot speak. */
    private val sink = object : ReplySink {
        override fun audio(samples: FloatArray) = player.enqueue(samples, graph.engine.capabilities.value.audioOutSampleRate)

        override fun finished(text: String, completed: Boolean) {
            val s = settings.value
            if (completed && VoiceRoute.androidSpeaks(s.speakReplies, s.voiceEngine, graph.engine.capabilities.value)) {
                speaker.speak(text)
            }
        }
    }

    /** Runtime permissions the tools ask for; the Chat screen shows the system dialog. */
    val permissions = PermissionGate(app)
    private val tools = phoneTools(graph, permissions) { settings.value.webSearch }
    /** Omni stays loaded; the smart model takes its place for one reply, fetched over Wi-Fi the first time. */
    private val models = object : ModelTargets {
        override suspend fun resident() = installed(ACTIVE_MODEL)
        override suspend fun smart() = installed(SMART_MODEL)
        override fun downloadSmart() = graph.downloads.download(SMART_MODEL, allowMetered = false)
    }

    private val controller = ChatController(
        graph.engine, graph.chats, viewModelScope, Dispatchers.IO, tools, media = graph.media, sink = sink, models = models,
    )

    val state: StateFlow<ChatState> = controller.state
    val engineState: StateFlow<EngineState> = graph.engine.state

    /** Saved conversations for the drawer, newest first. */
    val conversations: StateFlow<List<Conversation>> =
        graph.chats.conversations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The smart model's download, shown while it runs. */
    val smartDownload: StateFlow<DownloadStatus> =
        graph.downloads.status(SMART_MODEL).stateIn(viewModelScope, SharingStarted.Eagerly, DownloadStatus.NotDownloaded(0))

    init {
        // A failed download is reported once, when it fails.
        viewModelScope.launch {
            var wasActive = false
            smartDownload.collect { status ->
                if (wasActive && status is DownloadStatus.Failed) {
                    controller.showError("Could not download ${SMART_MODEL.displayName}: ${status.message}")
                }
                wasActive = status.active
            }
        }
    }

    val speakReplies: StateFlow<Boolean> =
        settings.map { it.speakReplies }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _pending = MutableStateFlow<List<Attachment>>(emptyList())

    /** Photos and voice messages waiting to go with the next message. */
    val pending: StateFlow<List<Attachment>> = _pending.asStateFlow()

    private enum class Mic { IDLE, RECORDING, LISTENING, SAVING }

    private var mic = Mic.IDLE
    private var micTimer: Job? = null
    private val _recordingMillis = MutableStateFlow<Long?>(null)

    /** How long the mic has been recording or listening; null when it is off. */
    val recordingMillis: StateFlow<Long?> = _recordingMillis.asStateFlow()

    private val _transcript = MutableStateFlow<String?>(null)

    /** Dictated text for the composer to add to its field, then [transcriptUsed]. */
    val transcript: StateFlow<String?> = _transcript.asStateFlow()

    private fun params(): GenerationParams {
        val s = settings.value
        return GenerationParams(
            temperature = s.temperature,
            tools = tools.filter { s.webSearch || it.spec.name !in WebTools.NAMES }.map { it.spec },
            speak = VoiceRoute.modelSpeaks(s.speakReplies, s.voiceEngine, graph.engine.capabilities.value),
        )
    }

    /** Called each time the Chat screen opens; loads (or reloads, after a context change) the model. */
    fun onChatOpened() {
        viewModelScope.launch {
            val (file, mmproj) = withContext(Dispatchers.IO) {
                graph.installer.installedFile(ACTIVE_MODEL) to graph.installer.installedMmproj(ACTIVE_MODEL)
            }
            if (file == null) {
                controller.showError("${ACTIVE_MODEL.displayName} is not downloaded. Open Settings → Manage model.")
                return@launch
            }
            controller.ensureLoaded(file.path, graph.settings.settings.first().contextSize, mmproj?.path)
        }
    }

    private suspend fun installed(spec: ModelSpec): LoadTarget? {
        val (file, mmproj) = withContext(Dispatchers.IO) { graph.installer.installedFile(spec) to graph.installer.installedMmproj(spec) }
        file ?: return null
        return LoadTarget(file.path, mmproj?.path, graph.settings.settings.first().contextSize)
    }

    /** Silences the previous reply, so a new one (or the user) is not talked over. */
    private fun silence() {
        player.stop()
        speaker.stop()
    }

    override fun send(text: String) {
        silence()
        if (controller.send(text, params(), _pending.value)) _pending.value = emptyList()
    }

    override fun stop() {
        silence()
        controller.stop()
    }

    override fun regenerate() {
        silence()
        controller.regenerate(params())
    }

    override fun thinkHarder() {
        silence()
        controller.thinkHarder(params())
    }

    override fun editAndResend(text: String) {
        silence()
        controller.editAndResend(text, params())
    }

    override fun newChat() {
        silence()
        controller.newChat()
    }

    override fun openConversation(id: Long) {
        silence()
        controller.open(id)
    }

    override fun renameConversation(id: Long, title: String) {
        viewModelScope.launch {
            runCatching { graph.chats.rename(id, title) }
                .onFailure { controller.showError("Could not rename the chat: ${it.message}") }
        }
    }

    override fun deleteConversation(id: Long) = controller.deleteConversation(id)

    override suspend fun searchChats(query: String) = graph.chats.search(query)

    override suspend fun exportChat(id: Long) = graph.chats.export(id)

    override fun dismissError() = controller.dismissError()

    override fun approveTool(confirmationId: Long) = controller.approveTool(confirmationId)

    override fun declineTool(confirmationId: Long) = controller.declineTool(confirmationId)

    /** Reports a problem the screen ran into (a denied permission, no camera app). */
    fun showError(message: String) = controller.showError(message)

    fun toggleSpeakReplies() {
        val on = !settings.value.speakReplies
        if (!on) silence()
        viewModelScope.launch { graph.settings.setSpeakReplies(on) }
    }

    private val _launch = MutableStateFlow<LaunchRequest.NewChat<Uri>?>(null)

    /** The latest share, widget or tile request, for the screen to start the mic or focus the field, then [launchHandled]. */
    val launch: StateFlow<LaunchRequest.NewChat<Uri>?> = _launch.asStateFlow()

    /** Opens a new chat for [request]: shared photos wait to be sent, shared text goes into the field (access/). */
    fun openLaunch(request: LaunchRequest.NewChat<Uri>) {
        newChat()
        request.images.forEach(::attachPhoto)
        request.prefill?.let { _transcript.value = it }
        _launch.value = request
    }

    fun launchHandled() {
        _launch.value = null
    }

    /** Attaches a photo from the picker. */
    fun attachPhoto(uri: Uri) = attach(AttachmentKind.IMAGE, "Could not attach the photo") { files.importImage(uri) }

    /** A file for the camera app to write into, and the URI to hand it. */
    fun newCameraCapture(): Pair<File, Uri> = files.newCameraCapture()

    /** Attaches the camera's photo at [capturePath], or drops the file when no photo was taken. */
    fun onCameraResult(capturePath: String, taken: Boolean) {
        val capture = File(capturePath)
        if (taken) {
            attach(AttachmentKind.IMAGE, "Could not attach the photo") { files.importCapture(capture) }
        } else {
            viewModelScope.launch(Dispatchers.IO) { capture.delete() }
        }
    }

    fun removePending(item: Attachment) {
        _pending.update { it - item }
        viewModelScope.launch(Dispatchers.IO) { files.delete(item.path) }
    }

    private fun attach(kind: AttachmentKind, failure: String, load: () -> File) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { load() } }
                .onSuccess { file -> _pending.update { it + Attachment(file.path, kind) } }
                .onFailure { controller.showError("$failure: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /**
     * The mic button. Records a voice message when the model hears audio, otherwise dictates into the
     * message field with Android's recognizer; a second tap stops. The screen asks for the permission first.
     */
    fun toggleMic() {
        when (mic) {
            Mic.RECORDING -> finishRecording()
            Mic.LISTENING -> dictation.stop()
            Mic.SAVING -> Unit
            Mic.IDLE -> {
                val app = getApplication<Application>()
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    controller.showError(MIC_PERMISSION)
                    return
                }
                val s = settings.value
                val caps = graph.engine.capabilities.value
                if (VoiceRoute.modelHears(s.voiceEngine, caps)) startRecording(caps.audioInSampleRate) else startDictation()
            }
        }
    }

    fun transcriptUsed() {
        _transcript.value = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording(sampleRate: Int) {
        try {
            recorder.start(sampleRate)
        } catch (e: Exception) {
            controller.showError("Could not start recording: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        silence()
        mic = Mic.RECORDING
        _recordingMillis.value = 0L
        micTimer = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val elapsed = recorder.elapsedMillis()
                _recordingMillis.value = elapsed
                if (elapsed >= VoiceRecorder.MAX_SECONDS * 1000L) {
                    finishRecording()
                    break
                }
            }
        }
    }

    /** Stops the recorder and adds the clip to the pending attachments. */
    private fun finishRecording() {
        mic = Mic.SAVING
        micTimer?.cancel()
        micTimer = null
        _recordingMillis.value = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    recorder.stop()?.takeIf { it.samples.isNotEmpty() }?.let { files.saveVoice(it.samples, it.sampleRate) }
                }
            }
                .onSuccess { file -> if (file != null) _pending.update { it + Attachment(file.path, AttachmentKind.AUDIO) } }
                .onFailure { controller.showError("Could not save the voice message: ${it.message ?: it.javaClass.simpleName}") }
            mic = Mic.IDLE
        }
    }

    private fun startDictation() {
        if (!dictation.available) {
            controller.showError("Speech recognition is not available on this phone.")
            return
        }
        silence()
        mic = Mic.LISTENING
        val started = SystemClock.elapsedRealtime()
        _recordingMillis.value = 0L
        micTimer = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                _recordingMillis.value = SystemClock.elapsedRealtime() - started
            }
        }
        try {
            dictation.start(
                onText = { _transcript.value = it },
                onError = { controller.showError(it) },
                onDone = ::endDictation,
            )
        } catch (e: Exception) {
            controller.showError("Could not start speech recognition: ${e.message ?: e.javaClass.simpleName}")
            dictation.cancel()
            endDictation()
        }
    }

    private fun endDictation() {
        if (mic != Mic.LISTENING) return
        mic = Mic.IDLE
        micTimer?.cancel()
        micTimer = null
        _recordingMillis.value = null
    }

    override fun onCleared() {
        player.release()
        speaker.release()
        recorder.stop()
        dictation.cancel()
    }

    private companion object {
        const val TICK_MS = 200L
        const val MIC_PERMISSION = "Microphone permission is needed for voice input."
    }
}
