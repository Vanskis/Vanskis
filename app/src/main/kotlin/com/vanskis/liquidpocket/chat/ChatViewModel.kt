package com.vanskis.liquidpocket.chat

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.MEDIA_MODEL
import com.vanskis.liquidpocket.access.LaunchRequest
import com.vanskis.liquidpocket.access.SharedDocument
import com.vanskis.liquidpocket.access.SharedInbox
import com.vanskis.liquidpocket.core.CustomModel
import com.vanskis.liquidpocket.core.Heretic
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.docs.DocumentText
import com.vanskis.liquidpocket.docs.ExtractedDocument
import com.vanskis.liquidpocket.docs.ExtractionProgress
import com.vanskis.liquidpocket.docs.MlKitOcr
import com.vanskis.liquidpocket.engine.Accelerator
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.media.Dictation
import com.vanskis.liquidpocket.media.ReplyAudioPlayer
import com.vanskis.liquidpocket.media.ReplySpeaker
import com.vanskis.liquidpocket.media.StreamingRecorder
import com.vanskis.liquidpocket.media.VoiceRecorder
import com.vanskis.liquidpocket.settings.AppSettings
import com.vanskis.liquidpocket.tools.PermissionGate
import com.vanskis.liquidpocket.tools.WebTools
import com.vanskis.liquidpocket.tools.phoneTools
import com.vanskis.liquidpocket.ui.components.ChatActions
import com.vanskis.liquidpocket.ui.components.DocumentExtraction
import com.vanskis.liquidpocket.ui.components.Indicators
import com.vanskis.liquidpocket.ui.components.ModelMenuState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Plays the model's speech as it streams; Android reads the finished reply when the model cannot speak.
     * In live voice the live loop decides what is spoken, and speech of a reply the user talked over is dropped.
     */
    private val sink = object : ReplySink {
        override fun audio(samples: FloatArray) {
            liveTurns?.let { turns ->
                if (!turns.generating) return
                liveAudio = true
                liveEvent(LiveEvent.ReplyAudio)
            }
            player.enqueue(samples, graph.engine.capabilities.value.audioOutSampleRate)
        }

        override fun finished(text: String, completed: Boolean, spoke: Boolean) {
            liveTurns?.let { turns ->
                if (turns.generating && completed) liveReply = text
                return
            }
            val s = settings.value
            if (completed && VoiceRoute.androidSpeaks(s.speakReplies, spoke)) speaker.speak(text)
        }
    }

    /** Runtime permissions the tools ask for; the Chat screen shows the system dialog. */
    val permissions = PermissionGate(app)
    private val tools = phoneTools(graph, permissions) { settings.value.webSearch }
    /**
     * The model picked in the composer's menu answers, else LFM3-1.7B; Omni takes its place for photo
     * and voice turns, when installed. Each is the Heretic build when chosen in Settings and
     * downloaded, else the standard one.
     */
    private val models = object : ModelTargets {
        override suspend fun text() =
            picked(settings.value)?.let { installed(it) } ?: installed(settings.value.textModel) ?: installed(ACTIVE_MODEL)
        override suspend fun media() = installed(settings.value.mediaModel) ?: installed(MEDIA_MODEL) ?: pickedVision()
    }

    private val controller = ChatController(
        graph.engine, graph.chats, viewModelScope, Dispatchers.IO, tools, media = graph.media,
        documents = DocumentLoader(graph.documents::load), sink = sink, models = models,
        customInstructions = { settings.value.customInstructions },
        accelerator = { if (settings.value.useNpu) Accelerator.NPU else Accelerator.CPU },
        environment = PhoneStateProbe(app, { graph.engine.state.value })::line,
    )

    val state: StateFlow<ChatState> = controller.state
    val engineState: StateFlow<EngineState> = graph.engine.state

    /** Saved conversations for the drawer, newest first. */
    val conversations: StateFlow<List<Conversation>> =
        graph.chats.conversations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether the media model is installed, so the mic records voice messages for it. */
    private val mediaInstalled: StateFlow<Boolean> =
        graph.downloads.usable(settings.map { it.mediaModel }, MEDIA_MODEL).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The Think chip: whether replies reason first. */
    val think: StateFlow<Boolean> =
        settings.map { it.think }.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings().think)

    private val _modelMenu = MutableStateFlow(ModelMenuState(AppSettings().think, AppSettings().thinkBudget, emptyList(), null))

    /** The composer's menu: the thinking level and the installed models, refreshed on each settings change. */
    val modelMenu: StateFlow<ModelMenuState> = _modelMenu.asStateFlow()

    init {
        viewModelScope.launch { think.collect { controller.think = it } }
        viewModelScope.launch { settings.collect { refreshModels() } }
    }

    /**
     * Every model the chat can answer with: each catalogued one and, once its repo is set, its Heretic
     * build; then each custom model and its Heretic build.
     */
    private fun candidates(s: AppSettings): List<ModelSpec> =
        listOf(ACTIVE_MODEL to s.hereticTextRepo, MEDIA_MODEL to s.hereticMediaRepo).flatMap { (base, repo) ->
            listOfNotNull(base, Heretic.spec(base, repo).takeIf { Heretic.isRepo(repo) })
        } + s.customModels.flatMap { listOfNotNull(it.spec, it.heretic) }

    /** The custom model picked in the menu (or whose Heretic build is), if one is. */
    private fun pickedCustom(s: AppSettings): CustomModel? =
        s.customModels.firstOrNull { it.id == s.chatModel || it.heretic?.id == s.chatModel }

    /** With Omni not installed, a picked custom model that sees photos describes them instead. */
    private suspend fun pickedVision(): LoadTarget? {
        val s = settings.value
        if (pickedCustom(s)?.vision != true) return null
        return picked(s)?.let { installed(it) }?.takeIf { it.mmprojPath != null }
    }

    /** The model picked in the composer's menu, while it is still offered. */
    private fun picked(s: AppSettings): ModelSpec? = candidates(s).firstOrNull { it.id == s.chatModel }

    private suspend fun refreshModels() {
        val s = settings.value
        val ready = withContext(Dispatchers.IO) { candidates(s).filter { graph.installer.installedFile(it) != null } }
        // The one models.text() loads.
        val selected = listOfNotNull(picked(s), s.textModel, ACTIVE_MODEL).firstOrNull { it in ready }
        _modelMenu.value = ModelMenuState(s.think, s.thinkBudget, ready.map { ModelChoice(it.id, it.displayName, it.thinks) }, selected?.id)
    }

    val speakReplies: StateFlow<Boolean> =
        settings.map { it.speakReplies }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val indicators: StateFlow<Indicators> =
        settings.map { it.indicators }.stateIn(viewModelScope, SharingStarted.Eagerly, Indicators())

    private val _pending = MutableStateFlow<List<Attachment>>(emptyList())

    /** Photos, voice messages and documents waiting to go with the next message. */
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
            // A reply runs until it ends or the context is full; a cut one can be continued.
            maxNewTokens = GenerationParams.UNTIL_CONTEXT_FULL,
            thinkBudget = s.thinkBudget,
            // A custom model whose template has no tools is not offered them.
            tools = if (pickedCustom(s)?.tools == false) emptyList()
            else tools.filter { s.webSearch || it.spec.name !in WebTools.NAMES }.map { it.spec },
            speak = VoiceRoute.modelSpeaks(s.speakReplies, s.voiceEngine),
        )
    }

    /** Called each time the Chat screen opens; loads (or reloads, after a context change) the model the chat routes to. */
    fun onChatOpened() {
        viewModelScope.launch {
            if (models.text() == null && models.media() == null) {
                controller.showError("${ACTIVE_MODEL.displayName} is not downloaded. Open Settings → Manage model.")
                return@launch
            }
            refreshModels()
            controller.ensureLoaded()
        }
    }

    private suspend fun installed(spec: ModelSpec): LoadTarget? {
        val (file, mmproj) = withContext(Dispatchers.IO) { graph.installer.installedFile(spec) to graph.installer.installedMmproj(spec) }
        file ?: return null
        return LoadTarget(file.path, mmproj?.path, graph.settings.settings.first().contextSize, spec.displayName)
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

    /** The context ring's Compact now: summarizes the older turns, see [ChatController.compact]. */
    fun compact() = controller.compact()

    override fun declineTool(confirmationId: Long) = controller.declineTool(confirmationId)

    override fun setSkill(skill: Skill?) = controller.setSkill(skill)

    /** What "/" lists: the built-in skills, then the user's own from Settings. */
    val skills: StateFlow<List<Skill>> =
        settings.map { Skills.BUILT_IN + it.userSkills }.stateIn(viewModelScope, SharingStarted.Eagerly, Skills.BUILT_IN)

    /** Reports a problem the screen ran into (a denied permission, no camera app). */
    fun showError(message: String) = controller.showError(message)

    /** The composer menu's thinking level. */
    fun setThinking(on: Boolean, budget: Int) {
        viewModelScope.launch { graph.settings.setThinking(on, budget) }
    }

    /** Answers with [id] from now on: the chat stays, and the model is loaded in place of the current one. */
    fun pickModel(id: String) {
        viewModelScope.launch {
            graph.settings.setChatModel(id)
            settings.first { it.chatModel == id }
            controller.ensureLoaded()
        }
    }

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
        viewModelScope.launch(Dispatchers.IO) {
            // A document's path is its DocumentStore id, not a file.
            if (item.kind == AttachmentKind.DOCUMENT) graph.documents.delete(item.path) else files.delete(item.path)
        }
    }

    private val _extracting = MutableStateFlow<List<DocumentExtraction>>(emptyList())

    /** Documents being read before they are attached, each with its progress. */
    val extracting: StateFlow<List<DocumentExtraction>> = _extracting.asStateFlow()

    private val extractions = mutableMapOf<Long, Job>()
    private var nextExtraction = 0L

    /** Reads a PDF or text file from the picker and attaches its text. */
    fun attachDocument(uri: Uri) = extractDocument(name = null) { reader, progress -> reader.extract(uri, onProgress = progress) }

    /** Reads a document shared from another app and attaches its text; the shared copy is deleted once read. */
    fun useSharedDocument(document: SharedDocument) {
        SharedInbox.remove(document)
        val file = File(document.path)
        extractDocument(document.name, cleanup = { file.delete() }) { reader, progress ->
            reader.extract(file, document.mime, document.name, progress)
        }
    }

    /** Stops reading the document [key]; nothing is attached. */
    fun cancelExtraction(key: Long) {
        extractions[key]?.cancel()
    }

    /**
     * Reads a document with [read] while its chip shows the progress, saves the text and adds it to the
     * pending attachments. [cleanup] runs (blocking, on IO) when reading ends, however it ends.
     */
    private fun extractDocument(
        name: String?,
        cleanup: () -> Unit = {},
        read: suspend (DocumentText, (ExtractionProgress) -> Unit) -> ExtractedDocument,
    ) {
        val key = nextExtraction++
        _extracting.update { it + DocumentExtraction(key, name) }
        // Registered before it starts, so its own finally always finds and removes it.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val doc = MlKitOcr().use { ocr ->
                    read(DocumentText(graph, ocr)) { p ->
                        val ocrStage = p.stage == ExtractionProgress.Stage.OCR
                        _extracting.update { list ->
                            list.map { if (it.key == key) it.copy(ocr = ocrStage, done = p.done, total = p.total) else it }
                        }
                    }
                }
                if (doc.pages.all { it.isBlank() }) {
                    controller.showError("Could not attach ${doc.name}: no text was found in it.")
                    return@launch
                }
                // Not cancellable, so a cancel during the save deletes the file below instead of losing its id.
                val id = withContext(NonCancellable + Dispatchers.IO) { graph.documents.save(doc) }
                if (isActive) {
                    _pending.update { it + Attachment(id, AttachmentKind.DOCUMENT, doc.name) }
                } else {
                    withContext(NonCancellable + Dispatchers.IO) { graph.documents.delete(id) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controller.showError("Could not attach the document: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                extractions.remove(key)
                _extracting.update { list -> list.filterNot { it.key == key } }
                withContext(NonCancellable + Dispatchers.IO) { cleanup() }
            }
        }
        extractions[key] = job
        job.start()
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
                if (VoiceRoute.modelHears(settings.value.voiceEngine, mediaInstalled.value)) {
                    startRecording(VoiceRoute.RECORD_SAMPLE_RATE)
                } else {
                    startDictation()
                }
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
                // No recognizer does the language: the model transcribes a recording instead, when one can hear.
                onUnavailable = if (mediaInstalled.value) ({ endDictation(); recordForModel() }) else null,
            )
        } catch (e: Exception) {
            controller.showError("Could not start speech recognition: ${e.message ?: e.javaClass.simpleName}")
            dictation.cancel()
            endDictation()
        }
    }

    /** Records a clip for the model, as the mic does when the model hears. */
    private fun recordForModel() {
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            controller.showError(MIC_PERMISSION)
            return
        }
        startRecording(VoiceRoute.RECORD_SAMPLE_RATE)
    }

    private fun endDictation() {
        if (mic != Mic.LISTENING) return
        mic = Mic.IDLE
        micTimer?.cancel()
        micTimer = null
        _recordingMillis.value = null
    }

    // ---- Live voice: a hands-free conversation on its own screen (LiveVoice.kt has the turn-taking). ----

    private val liveRecorder = StreamingRecorder()
    private val liveDictation = Dictation(app)
    private var liveTurns: LiveTurns? = null
    private var liveCapture: LiveCapture? = null
    private var liveModel = LiveVoiceModel.TEXT
    private var liveFrames: Channel<ShortArray>? = null
    private var liveJob: Job? = null
    private var liveSend: Job? = null
    private var liveRecognizing = false
    /** No recognizer does the language; live voice reopens with LFM3-Omni hearing instead. */
    private var liveToOmni = false
    /** What Android's recognizer heard of the turn (1.7B mode). */
    private var liveTranscript: String? = null
    /** What was heard and has not reached the chat; a send that new speech cut short goes with the next turn. */
    private val liveUnsent = LiveUnsent()
    /** The finished reply's text, and whether the model's own voice played it. */
    private var liveReply: String? = null
    private var liveAudio = false
    private val _live = MutableStateFlow<LiveUi?>(null)
    /** The live camera preview's current frame, set while it is showing. Main thread. */
    private var liveFrame: (() -> Bitmap?)? = null

    /** The live voice screen's state; null when it is closed. */
    val live: StateFlow<LiveUi?> = _live.asStateFlow()

    /** Opens live voice with the model the settings choose. The screen asks for the microphone first. */
    fun startLive(model: LiveVoiceModel? = null) {
        if (liveTurns != null) return
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            controller.showError(MIC_PERMISSION)
            return
        }
        val model = model ?: LiveVoiceModel.resolve(settings.value.liveVoiceModel, mediaInstalled.value)
        if (model == LiveVoiceModel.TEXT && !liveDictation.available) {
            controller.showError("Speech recognition is not available on this phone.")
            return
        }
        when (mic) {
            Mic.RECORDING -> finishRecording()
            Mic.LISTENING -> {
                dictation.cancel()
                endDictation()
            }
            else -> Unit
        }
        silence()
        liveModel = model
        liveTurns = LiveTurns()
        liveCapture = LiveCapture()
        liveAudio = false
        val frames = Channel<ShortArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        liveFrames = frames
        _live.value = LiveUi(LivePhase.LISTENING, 0f, model)
        liveJob = viewModelScope.launch {
            launch { for (frame in frames) onLiveFrame(frame) }
            while (true) {
                delay(LIVE_TICK_MS)
                liveTick()
            }
        }
        syncLiveMic()
    }

    /**
     * Closes live voice: the mic stops and so does the reply, unless [keepReply] (it then finishes in
     * the chat as any other would).
     */
    fun stopLive(keepReply: Boolean = false) {
        val turns = liveTurns ?: return
        liveTurns = null
        liveCapture = null
        liveJob?.cancel()
        liveJob = null
        liveSend?.cancel()
        liveSend = null
        liveFrames?.close()
        liveFrames = null
        liveRecorder.stop()
        liveDictation.cancel()
        liveRecognizing = false
        liveTranscript = null
        liveUnsent.clear()
        liveReply = null
        if (!keepReply) {
            if (turns.generating) controller.stop()
            silence()
        }
        _live.value = null
    }

    /** Turns the camera on (back lens first) or off. The screen asks for the camera permission first. */
    fun toggleLiveCamera() = _live.update { it?.copy(camera = if (it.camera == null) LiveCamera.BACK else null) }

    fun flipLiveCamera() = _live.update { ui -> ui?.copy(camera = ui.camera?.flipped) }

    /** Set by the preview once it shows frames, null when it goes. */
    fun setLiveFrameSource(source: (() -> Bitmap?)?) {
        liveFrame = source
    }

    private fun liveEvent(event: LiveEvent) {
        val turns = liveTurns ?: return
        for (action in turns.on(event)) {
            when (action) {
                LiveAction.STOP_PLAYBACK -> silence()
                LiveAction.STOP_GENERATION -> controller.stop()
                LiveAction.START_CAPTURE -> {
                    liveSend?.cancel()
                    liveReply = null
                    liveAudio = false
                }
                LiveAction.SEND_TURN -> sendLiveTurn()
                LiveAction.LISTEN -> Unit
            }
        }
        _live.update { it?.copy(phase = turns.phase) }
        syncLiveMic()
    }

    private fun onLiveFrame(frame: ShortArray) {
        val capture = liveCapture ?: return
        val event = capture.feed(frame)
        _live.update { it?.copy(level = LiveLevel.fromRms(capture.level)) }
        when (event) {
            VadEvent.SPEECH_START -> liveEvent(LiveEvent.SpeechStart)
            // With 1.7B, Android's recognizer ends the turn; this mic only listens for barge-in.
            VadEvent.SPEECH_END -> if (liveModel == LiveVoiceModel.OMNI) liveEvent(LiveEvent.SpeechEnd)
            null -> Unit
        }
    }

    /**
     * Sends the turn into the chat: Omni hears the clip, 1.7B reads what Android heard. A turn whose
     * send new speech cut short ([liveUnsent]) goes in front of it.
     */
    private fun sendLiveTurn() {
        val model = liveModel
        val heard = if (model == LiveVoiceModel.OMNI) liveCapture?.takeTurn() ?: FloatArray(0) else FloatArray(0)
        val (text, clip) = liveUnsent.take(if (model == LiveVoiceModel.TEXT) liveTranscript.orEmpty() else "", heard)
        // What the camera sees as the user stops talking.
        val frame = if (_live.value?.camera != null) liveFrame?.invoke() else null
        liveTranscript = null
        liveSend?.cancel()
        liveSend = viewModelScope.launch {
            val saved = mutableListOf<File>()
            var sent = false
            try {
                val attachments = mutableListOf<Attachment>()
                if (frame != null) {
                    val image = withContext(Dispatchers.IO) { files.saveFrame(frame) }
                    saved += image
                    attachments += Attachment(image.path, AttachmentKind.IMAGE)
                }
                if (clip.isNotEmpty()) {
                    val voice = withContext(Dispatchers.IO) { files.saveVoice(clip, VoiceRoute.RECORD_SAMPLE_RATE) }
                    saved += voice
                    attachments += Attachment(voice.path, AttachmentKind.AUDIO)
                }
                // A reply the user talked over may still be wrapping up.
                withTimeoutOrNull(LIVE_SEND_WAIT_MS) { controller.state.first { !it.busy } }
                sent = controller.send(text, params().copy(speak = model == LiveVoiceModel.OMNI), attachments)
            } catch (e: CancellationException) {
                // Cut short by new speech: what was heard stays in liveUnsent for the next turn.
                throw e
            } catch (e: Exception) {
                controller.showError("Could not send the voice message: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (!sent && saved.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { saved.forEach { files.delete(it.path) } }
            }
            // Sent, or dropped for good: either way it does not go with the next turn.
            liveUnsent.clear()
            liveEvent(if (sent) LiveEvent.Sent else LiveEvent.TurnDropped)
        }
    }

    /** Notices the end of the reply (and speaks it if the model did not) and the end of its playback. */
    private fun liveTick() {
        val turns = liveTurns ?: return
        val chat = controller.state.value
        if (turns.generating && chat.confirmation != null) {
            // The approval card is in the chat, under this screen: hand the reply over to it.
            stopLive(keepReply = true)
            return
        }
        if (turns.generating && !chat.generating) {
            val reply = liveReply
            liveReply = null
            val speaking = when {
                liveAudio -> true
                reply != null -> {
                    speaker.speak(reply)
                    speaker.busy
                }
                else -> false
            }
            liveEvent(LiveEvent.ReplyEnded(speaking))
        } else if (turns.phase == LivePhase.SPEAKING && !turns.generating && !player.busy && !speaker.busy) {
            liveEvent(LiveEvent.PlaybackDone)
        }
    }

    /**
     * Points the mic at the phase: with Omni the recorder always runs; with 1.7B Android's recognizer
     * hears the turn and the recorder only listens for barge-in while the reply comes. Talking over
     * the reply takes a louder voice than starting a turn, so its echo is less likely to interrupt it.
     */
    private fun syncLiveMic() {
        val turns = liveTurns ?: return
        val replying = turns.phase == LivePhase.WAITING || turns.phase == LivePhase.SPEAKING
        liveCapture?.vad?.threshold = if (replying) EnergyVad.BARGE_IN_THRESHOLD else EnergyVad.LISTEN_THRESHOLD
        val wantRecorder = liveModel == LiveVoiceModel.OMNI || replying
        val wantRecognizer = liveModel == LiveVoiceModel.TEXT && !replying
        if (!wantRecorder && liveRecorder.isRecording) liveRecorder.stop()
        if (wantRecognizer && !liveRecognizing) startLiveRecognizer()
        if (wantRecorder && !liveRecorder.isRecording) startLiveRecorder()
    }

    private fun startLiveRecorder() {
        val frames = liveFrames ?: return
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            controller.showError(MIC_PERMISSION)
            stopLive()
            return
        }
        liveCapture?.reset()
        try {
            liveRecorder.stop()
            liveRecorder.start(VoiceRoute.RECORD_SAMPLE_RATE, LIVE_FRAME_SAMPLES) { frames.trySend(it) }
        } catch (e: Exception) {
            controller.showError("Could not start the microphone: ${e.message ?: e.javaClass.simpleName}")
            stopLive()
        }
    }

    private fun startLiveRecognizer() {
        liveRecognizing = true
        liveTranscript = null
        try {
            liveDictation.start(
                onText = { liveTranscript = it },
                onError = { message ->
                    controller.showError(message)
                    stopLive()
                },
                onDone = ::onLiveRecognizerDone,
                onSpeechStart = { liveEvent(LiveEvent.SpeechStart) },
                onLevel = { db -> _live.update { it?.copy(level = ((db + 2f) / 12f).coerceIn(0f, 1f)) } },
                onNoSpeech = {},
                onUnavailable = if (mediaInstalled.value) ({ liveToOmni = true }) else null,
            )
        } catch (e: Exception) {
            liveRecognizing = false
            controller.showError("Could not start speech recognition: ${e.message ?: e.javaClass.simpleName}")
            stopLive()
        }
    }

    private fun onLiveRecognizerDone() {
        liveRecognizing = false
        if (liveToOmni) {
            liveToOmni = false
            stopLive()
            startLive(LiveVoiceModel.OMNI)
            return
        }
        val turns = liveTurns ?: return
        when {
            // A turn cut short waits in liveUnsent: send it even when nothing more was heard.
            liveTranscript != null || !liveUnsent.isEmpty -> {
                if (turns.phase == LivePhase.LISTENING) liveEvent(LiveEvent.SpeechStart)
                liveEvent(LiveEvent.SpeechEnd)
            }
            turns.phase == LivePhase.CAPTURING -> liveEvent(LiveEvent.TurnDropped)
            // Nothing heard: listen again, after a pause so a failing recognizer does not spin.
            else -> viewModelScope.launch {
                delay(LIVE_RESTART_MS)
                if (liveTurns === turns) syncLiveMic()
            }
        }
    }

    override fun onCleared() {
        stopLive()
        player.release()
        speaker.release()
        recorder.stop()
        dictation.cancel()
    }

    private companion object {
        const val TICK_MS = 200L
        const val LIVE_TICK_MS = 100L
        /** 30 ms at [VoiceRoute.RECORD_SAMPLE_RATE]. */
        const val LIVE_FRAME_SAMPLES = 480
        const val LIVE_SEND_WAIT_MS = 15_000L
        const val LIVE_RESTART_MS = 300L
        const val MIC_PERMISSION = "Microphone permission is needed for voice input."
    }
}
