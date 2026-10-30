package com.vanskis.liquidpocket.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.MEDIA_MODEL
import com.vanskis.liquidpocket.chat.LiveVoiceModel
import com.vanskis.liquidpocket.chat.Skill
import com.vanskis.liquidpocket.chat.SystemPrompt
import com.vanskis.liquidpocket.chat.VoiceEngine
import com.vanskis.liquidpocket.core.ContextSize
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.download.DownloadStatus
import com.vanskis.liquidpocket.engine.BenchResult
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.ui.components.Indicators
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull
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

    fun setHeretic(media: Boolean, use: Boolean, repo: String) = edit {
        if (media) it.copy(hereticMedia = use, hereticMediaRepo = repo) else it.copy(hereticText = use, hereticTextRepo = repo)
    }

    fun downloadStatus(spec: ModelSpec): Flow<DownloadStatus> = graph.downloads.status(spec)

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
    }
}
