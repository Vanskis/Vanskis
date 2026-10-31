package com.vanskis.liquidpocket.settings

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.MEDIA_MODEL
import com.vanskis.liquidpocket.chat.LiveVoiceModel
import com.vanskis.liquidpocket.chat.Skill
import com.vanskis.liquidpocket.chat.SystemPrompt
import com.vanskis.liquidpocket.chat.VoiceEngine
import com.vanskis.liquidpocket.core.ContextSize
import com.vanskis.liquidpocket.core.CustomModel
import com.vanskis.liquidpocket.core.GgufHeader
import com.vanskis.liquidpocket.core.HttpFetcher
import com.vanskis.liquidpocket.core.HuggingFace
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.core.TemplateTraits
import com.vanskis.liquidpocket.core.infoUrl
import com.vanskis.liquidpocket.core.parseChatTemplate
import com.vanskis.liquidpocket.download.DownloadStatus
import com.vanskis.liquidpocket.engine.BenchResult
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.MemorySnapshot
import com.vanskis.liquidpocket.ui.components.Indicators
import com.vanskis.liquidpocket.ui.components.RepoLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Changes are held as a draft until [save]. Once saved, the model is reloaded with a new context size
 * the next time the Chat screen opens, and on a new accelerator with the next reply.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LiquidPocketApp
    private val repo = graph.settings
    private val engine = graph.engine

    /** What is stored. */
    val saved: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * The screen's unsaved edits, in order. Kept as changes rather than a copy of the settings, so a
     * setting changed elsewhere meanwhile (the composer's Think chip) is not put back by [save].
     */
    private val edits = MutableStateFlow<List<(AppSettings) -> AppSettings>>(emptyList())

    /** What the screen shows: what is stored with the edits on top. */
    val settings: StateFlow<AppSettings> =
        combine(saved, edits, ::applied).stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Whether there are edits that [save] would store. */
    val unsaved: StateFlow<Boolean> =
        combine(saved, edits) { stored, changes -> applied(stored, changes) != stored }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether this build ships the NPU backend; the public build is CPU-only. */
    val npuAvailable: Boolean = engine.npuAvailable

    /** The loaded model's accelerator note, and whether a reply is generating (no benchmark then). */
    val engineState: StateFlow<EngineState> = engine.state

    private val _benchmarking = MutableStateFlow(false)
    val benchmarking: StateFlow<Boolean> = _benchmarking.asStateFlow()

    /** The last benchmark's result or error, null before the first run. */
    private val _benchmark = MutableStateFlow<String?>(null)
    val benchmark: StateFlow<String?> = _benchmark.asStateFlow()

    private val _measuring = MutableStateFlow(false)
    val measuring: StateFlow<Boolean> = _measuring.asStateFlow()

    /** The last measurement result or error, null before the first run. */
    private val _measurement = MutableStateFlow<String?>(null)
    val measurement: StateFlow<String?> = _measurement.asStateFlow()

    /** Whether Omni is installed, which live voice needs to use it. */
    val omniInstalled: StateFlow<Boolean> =
        graph.downloads.usable(settings.map { it.mediaModel }, MEDIA_MODEL).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setContextSize(value: Int) = edit { it.copy(contextSize = ContextSize.clamp(value)) }

    fun setThinkBudget(value: Int) = edit { it.copy(thinkBudget = ThinkBudget.clamp(value)) }

    fun setTemperature(value: Float) = edit { it.copy(temperature = value.coerceIn(0f, AppSettings.MAX_TEMPERATURE)) }

    fun setTheme(value: AppTheme) = edit { it.copy(theme = value) }

    fun setSpeakReplies(value: Boolean) = edit { it.copy(speakReplies = value) }

    fun setVoiceEngine(value: VoiceEngine) = edit { it.copy(voiceEngine = value) }

    fun setWebSearch(value: Boolean) = edit { it.copy(webSearch = value) }
    fun setIndicators(value: Indicators) = edit { it.copy(indicators = value) }

    fun setLiveVoiceModel(value: LiveVoiceModel) = edit { it.copy(liveVoiceModel = value) }

    fun setCustomInstructions(value: String) = edit { it.copy(customInstructions = value.take(SystemPrompt.MAX_CUSTOM_CHARS)) }

    fun setUseNpu(value: Boolean) = edit { it.copy(useNpu = value) }

    fun setUserSkills(value: List<Skill>) = edit { it.copy(userSkills = value) }

    /** [id]'s own settings; the defaults clear them. */
    fun setTuning(id: String, value: ModelTuning) = edit { s ->
        val clean = value.clamped()
        s.copy(tunings = if (clean == ModelTuning()) s.tunings - id else s.tunings + (id to clean))
    }

    fun setHeretic(media: Boolean, use: Boolean, repo: String) = edit {
        // A text model choice in Settings replaces the one picked in the composer's menu.
        if (media) it.copy(hereticMedia = use, hereticMediaRepo = repo) else it.copy(hereticText = use, hereticTextRepo = repo, chatModel = "")
    }

    fun downloadStatus(spec: ModelSpec): Flow<DownloadStatus> = graph.downloads.status(spec)

    private val _lookup = MutableStateFlow<RepoLookup>(RepoLookup.Idle)

    /** The Add model dialog's lookup of a Hugging Face repo. */
    val lookup: StateFlow<RepoLookup> = _lookup.asStateFlow()

    private val _importing = MutableStateFlow<String?>(null)

    /** The progress line of a .gguf import under way, else null (or its error, until the next one). */
    val importing: StateFlow<String?> = _importing.asStateFlow()

    /** Lists [repo]'s single-file GGUF models, and reads what its chat template says it can do. */
    fun lookUp(repo: String) {
        _lookup.value = RepoLookup.Loading
        viewModelScope.launch {
            _lookup.value = try {
                withContext(Dispatchers.IO) {
                    val http = HttpFetcher()
                    val files = HuggingFace.parseTree(http.fetchText(HuggingFace.treeUrl(repo)))
                    val names = files.map { it.path.substringAfterLast('/').lowercase() }
                    // A missing template only means no detection; the files still list.
                    val template = runCatching { HuggingFace.parseChatTemplate(http.fetchText(HuggingFace.infoUrl(repo))) }.getOrNull()
                    RepoLookup.Found(
                        repo,
                        files.filter { f -> val n = f.path.substringAfterLast('/').lowercase(); n.endsWith(".gguf") && "mmproj" !in n && !SHARD.containsMatchIn(n) },
                        TemplateTraits.of(template),
                        vision = names.any { it.endsWith(".gguf") && "mmproj" in it },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RepoLookup.Failed("Could not look up $repo: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun dismissLookup() {
        _lookup.value = RepoLookup.Idle
    }

    /** Stores [model] and starts its download. */
    fun addCustomModel(model: CustomModel) {
        viewModelScope.launch {
            repo.putCustomModel(model)
            download(model.spec)
        }
    }

    fun changeCustomModel(model: CustomModel) {
        viewModelScope.launch { repo.putCustomModel(model) }
    }

    /** Forgets [model] and deletes its files, and its Heretic build's. */
    fun removeCustomModel(model: CustomModel) {
        viewModelScope.launch {
            listOfNotNull(model.spec, model.heretic).forEach {
                graph.downloads.cancel(it)
                graph.downloads.delete(it)
            }
            repo.removeCustomModel(model.id)
        }
    }

    /** Copies the .gguf at [uri] into the models folder, reads its chat template, and adds it. */
    fun importModel(uri: Uri) {
        if (_importing.value?.startsWith(IMPORTING) == true) return
        _importing.value = IMPORTING
        viewModelScope.launch {
            _importing.value = try {
                val model = withContext(Dispatchers.IO) { copyIn(uri) }
                repo.putCustomModel(model)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun copyIn(uri: Uri): CustomModel {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "model.gguf"
        val file = if (name.endsWith(".gguf", ignoreCase = true)) name else "$name.gguf"
        val template = resolver.openInputStream(uri)?.use(GgufHeader::chatTemplate) ?: throw IOException("Could not open the file")
        val dest = graph.installer.importFile(file)
        val part = File(dest.path + ".part")
        resolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    _importing.value = "$IMPORTING ${copied / 1_000_000} MB"
                }
            }
        } ?: throw IOException("Could not open the file")
        if (!part.renameTo(dest)) throw IOException("Could not save the file")
        val traits = TemplateTraits.of(template)
        val model = CustomModel(CustomModel.idFor("", file), CustomModel.nameFor(file), "", file, tools = traits.tools, thinks = traits.thinks)
        graph.installer.adopt(model.spec, dest)
        return model
    }

    /** Waits for Wi-Fi, as the first download does: these are over a gigabyte. */
    fun download(spec: ModelSpec) = graph.downloads.download(spec, allowMetered = false)

    fun cancelDownload(spec: ModelSpec) = graph.downloads.cancel(spec)

    private fun edit(change: (AppSettings) -> AppSettings) {
        edits.value = edits.value + change
    }

    private fun applied(stored: AppSettings, changes: List<(AppSettings) -> AppSettings>) =
        changes.fold(stored) { value, change -> change(value) }

    fun save() {
        val changes = edits.value
        if (changes.isEmpty()) return
        viewModelScope.launch {
            val target = applied(saved.value, changes)
            repo.save(target)
            // Dropped once the stored value has caught up, so the screen does not flash the old one.
            withTimeoutOrNull(SAVE_SETTLE_MS) { saved.first { it == target } }
            // Edits made while saving stay unsaved.
            edits.value = edits.value.drop(changes.size)
        }
    }

    fun discard() {
        edits.value = emptyList()
    }

    /**
     * Measures the loaded model on the CPU and NPU and keeps the NPU on only where it wins; ignored
     * while one runs or a reply is generating.
     */
    fun runBenchmark() {
        if (_benchmarking.value || engine.state.value == EngineState.Generating) return
        _benchmarking.value = true
        viewModelScope.launch {
            try {
                graph.npuPick.run()?.let { _benchmark.value = describe(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _benchmark.value = "Benchmark failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _benchmarking.value = false
            }
        }
    }

    /**
     * Measures TTFT, load time, memory with the chat model and Omni, and saved chat state; ignored
     * while one runs or a reply is generating.
     */
    fun runMeasure() {
        if (_measuring.value || _benchmarking.value || engine.state.value == EngineState.Generating) return
        _measuring.value = true
        viewModelScope.launch {
            try {
                val omniFile = withContext(Dispatchers.IO) {
                    graph.installer.installedFile(MEDIA_MODEL)
                }
                val app = getApplication<Application>()
                val cacheDir = app.cacheDir
                val result = engine.measure(cacheDir, omniFile?.absolutePath) {
                    val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val mi = android.app.ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    MemorySnapshot(
                        pssKb = android.os.Debug.getPss(),
                        nativeHeapBytes = android.os.Debug.getNativeHeapAllocatedSize(),
                        availMemBytes = mi.availMem,
                        totalMemBytes = mi.totalMem,
                        lowMemory = mi.lowMemory,
                    )
                }
                _measurement.value = result.describe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _measurement.value = "Measurement failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _measuring.value = false
            }
        }
    }

    private fun describe(result: BenchResult): String {
        val cpu = "CPU ${tokPerSec(result.cpuTokPerSec)}"
        val npu = result.npuTokPerSec?.let { "NPU ${tokPerSec(it)}" } ?: result.npuNote
        val speeds = if (npu == null) cpu else "$cpu · $npu"
        val prefill = listOfNotNull(
            result.cpuPrefillTokPerSec?.let { "CPU ${tokPerSec(it)}" },
            result.npuPrefillTokPerSec?.let { "NPU ${tokPerSec(it)}" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Prompt reading: ")
        val battery = listOfNotNull(
            result.cpuMahPerReply?.let { "CPU ${mah(it)}" },
            result.npuMahPerReply?.let { "NPU ${mah(it)}" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Battery per reply: ")
        val pick = when {
            !npuAvailable -> null
            result.npuWins -> "Using the NPU: it wins on this model."
            else -> "Using the CPU: the NPU does not win on this model."
        }
        val numbers = listOfNotNull("Reply speed: $speeds", prefill, battery).joinToString("\n")
        return listOfNotNull(numbers, result.verdict, pick, result.npuReport).joinToString("\n\n")
    }

    private fun tokPerSec(value: Double) = String.format(Locale.ROOT, "%.1f tok/s", value)

    private fun mah(value: Double) = String.format(Locale.ROOT, "%.2f mAh", value)

    private companion object {
        const val SAVE_SETTLE_MS = 1_000L
        const val IMPORTING = "Importing…"
        val SHARD = Regex("""-\d{5}-of-\d{5}\.gguf$""")
    }
}
