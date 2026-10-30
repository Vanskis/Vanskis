package com.vanskis.liquidpocket.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.chat.VoiceEngine
import com.vanskis.liquidpocket.engine.BenchResult
import com.vanskis.liquidpocket.engine.EngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The model is reloaded with a new context size the next time the Chat screen opens, and on a new
 * accelerator with the next reply.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as LiquidPocketApp).settings
    private val engine = (app as LiquidPocketApp).engine

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Whether this build ships the NPU backend; the public build is CPU-only. */
    val npuAvailable: Boolean = engine.npuAvailable

    /** The loaded model's accelerator note, and whether a reply is generating (no benchmark then). */
    val engineState: StateFlow<EngineState> = engine.state

    private val _benchmarking = MutableStateFlow(false)
    val benchmarking: StateFlow<Boolean> = _benchmarking.asStateFlow()

    /** The last benchmark's result or error, null before the first run. */
    private val _benchmark = MutableStateFlow<String?>(null)
    val benchmark: StateFlow<String?> = _benchmark.asStateFlow()

    fun setContextSize(value: Int) {
        viewModelScope.launch { repo.setContextSize(value) }
    }

    fun setTemperature(value: Float) {
        viewModelScope.launch { repo.setTemperature(value) }
    }

    fun setDynamicColor(value: Boolean) {
        viewModelScope.launch { repo.setDynamicColor(value) }
    }

    fun setSpeakReplies(value: Boolean) {
        viewModelScope.launch { repo.setSpeakReplies(value) }
    }

    fun setVoiceEngine(value: VoiceEngine) {
        viewModelScope.launch { repo.setVoiceEngine(value) }
    }

    fun setWebSearch(value: Boolean) {
        viewModelScope.launch { repo.setWebSearch(value) }
    }

    fun setCustomInstructions(value: String) {
        viewModelScope.launch { repo.setCustomInstructions(value) }
    }

    fun setUseNpu(value: Boolean) {
        viewModelScope.launch { repo.setUseNpu(value) }
    }

    /** Measures the loaded model's decode speed; ignored while one runs or a reply is generating. */
    fun runBenchmark() {
        if (_benchmarking.value || engine.state.value == EngineState.Generating) return
        _benchmarking.value = true
        viewModelScope.launch {
            try {
                _benchmark.value = describe(withContext(Dispatchers.IO) { engine.benchmark() })
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
        return if (npu == null) cpu else "$cpu · $npu"
    }

    private fun tokPerSec(value: Double) = String.format(Locale.ROOT, "%.1f tok/s", value)
}
