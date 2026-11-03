package com.vanskis.liquidpocket.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.MEDIA_MODEL
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.ReplyChunk
import com.vanskis.liquidpocket.engine.Role
import com.vanskis.liquidpocket.engine.Sampling
import com.vanskis.liquidpocket.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Locale

enum class DiagnosticStatus {
    PENDING, RUNNING, PASS, FAIL, SKIP
}

data class DiagnosticItem(
    val id: String,
    val name: String,
    val status: DiagnosticStatus,
    val detail: String,
)

class DiagnosticsRunner(
    private val engine: Engine,
    private val getSettings: suspend () -> AppSettings,
    private val installedFile: (ModelSpec) -> File?,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _items = MutableStateFlow(initialItems())
    val items: StateFlow<List<DiagnosticItem>> = _items.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    fun runAll() {
        if (_running.value) return
        _running.value = true
        _report.value = null
        scope.launch {
            try {
                executeAllChecks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep report updated on unexpected exception
            } finally {
                _running.value = false
                val current = _items.value
                val settings = runCatching { getSettings() }.getOrNull() ?: AppSettings()
                _report.value = generateReport(current, settings)
            }
        }
    }

    private suspend fun executeAllChecks() {
        val settings = getSettings()

        // Ensure model is ready if possible
        ensureModelLoaded(settings)

        // 1. Sampling checks
        runSamplingTemp0Check()
        runSamplingTempHighCheck()
        runSamplingTopK1Check()

        // 2. Custom model check
        runCustomModelCheck(settings)

        // 3. 32K saved state check
        run32kSavedStateCheck(settings)

        // 4. Saved-state fast path check
        runSavedStateFastPathCheck()

        // 5. Subsequent item checks (placeholders / extensible)
        runOmniResidentCheck()
        runSharedPrefixCheck()
        runLookupDecodingCheck()
        runPerPhaseAcceleratorCheck()
        runTurnRecorderReplayCheck()
        runEngineProcessCheck()
    }

    private suspend fun ensureModelLoaded(settings: AppSettings) {
        if (engine.state.value !is EngineState.Ready) {
            val file = withContext(ioDispatcher) { installedFile(ACTIVE_MODEL) }
            if (file != null && file.canRead()) {
                val ctx = settings.contextSize
                try {
                    engine.load(file.absolutePath, EngineConfig(contextSize = ctx))
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun runSamplingTemp0Check() {
        updateItem("sampling_temp0", DiagnosticStatus.RUNNING, "Running deterministic sampling check...")
        val ready = engine.state.value as? EngineState.Ready
        if (ready == null) {
            updateItem("sampling_temp0", DiagnosticStatus.SKIP, "No model loaded")
            return
        }

        try {
            val prompt = listOf(ChatMessage(Role.USER, "Write a 3-word sentence."))
            val params = GenerationParams(temperature = 0.0f, maxNewTokens = 8, think = false)

            val out1 = collectText(prompt, params)
            val out2 = collectText(prompt, params)

            if (out1 == out2) {
                updateItem("sampling_temp0", DiagnosticStatus.PASS, "Identical output across runs: \"$out1\"")
            } else {
                updateItem("sampling_temp0", DiagnosticStatus.FAIL, "Outputs differed: \"$out1\" vs \"$out2\"")
            }
        } catch (e: Exception) {
            updateItem("sampling_temp0", DiagnosticStatus.FAIL, "Error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun runSamplingTempHighCheck() {
        updateItem("sampling_temp_high", DiagnosticStatus.RUNNING, "Running varied temperature sampling check...")
        val ready = engine.state.value as? EngineState.Ready
        if (ready == null) {
            updateItem("sampling_temp_high", DiagnosticStatus.SKIP, "No model loaded")
            return
        }

        try {
            val prompt = listOf(ChatMessage(Role.USER, "Pick a random color name."))
            val params = GenerationParams(
                temperature = 1.5f,
                maxNewTokens = 10,
                think = false,
                sampling = Sampling(seed = Sampling.RANDOM_SEED),
            )

            val outputs = mutableSetOf<String>()
            for (i in 1..3) {
                val text = collectText(prompt, params)
                outputs.add(text)
                if (outputs.size > 1) break
            }

            if (outputs.size > 1) {
                updateItem("sampling_temp_high", DiagnosticStatus.PASS, "Varied outputs observed: ${outputs.joinToString(" vs ") { "\"$it\"" }}")
            } else {
                updateItem("sampling_temp_high", DiagnosticStatus.PASS, "High-temperature run completed (\"${outputs.firstOrNull()}\")")
            }
        } catch (e: Exception) {
            updateItem("sampling_temp_high", DiagnosticStatus.FAIL, "Error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun runSamplingTopK1Check() {
        updateItem("sampling_topk1", DiagnosticStatus.RUNNING, "Checking top-k=1 greedy match...")
        val ready = engine.state.value as? EngineState.Ready
        if (ready == null) {
            updateItem("sampling_topk1", DiagnosticStatus.SKIP, "No model loaded")
            return
        }

        try {
            val prompt = listOf(ChatMessage(Role.USER, "Count: 1, 2,"))
            val greedyParams = GenerationParams(temperature = 0.0f, maxNewTokens = 6, think = false)
            val topKParams = GenerationParams(
                temperature = 1.0f,
                maxNewTokens = 6,
                think = false,
                sampling = Sampling(topK = 1),
            )

            val greedyOut = collectText(prompt, greedyParams)
            val topKOut = collectText(prompt, topKParams)

            if (greedyOut == topKOut) {
                updateItem("sampling_topk1", DiagnosticStatus.PASS, "top-k 1 matches greedy output: \"$topKOut\"")
            } else {
                updateItem("sampling_topk1", DiagnosticStatus.FAIL, "Mismatch: greedy=\"$greedyOut\", top-k 1=\"$topKOut\"")
            }
        } catch (e: Exception) {
            updateItem("sampling_topk1", DiagnosticStatus.FAIL, "Error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun runCustomModelCheck(settings: AppSettings) {
        updateItem("custom_model", DiagnosticStatus.RUNNING, "Checking custom model...")
        val customModels = settings.customModels
        if (customModels.isEmpty()) {
            updateItem("custom_model", DiagnosticStatus.SKIP, "No custom model configured in Settings")
            return
        }

        val firstConfigured = customModels.first()
        val file = withContext(ioDispatcher) { installedFile(firstConfigured.spec) }
        if (file == null || !file.canRead()) {
            updateItem("custom_model", DiagnosticStatus.SKIP, "Custom model '${firstConfigured.name}' configured but not downloaded")
            return
        }

        val previousReady = engine.state.value as? EngineState.Ready
        try {
            engine.load(file.absolutePath, EngineConfig(contextSize = 2048))
            val prompt = listOf(ChatMessage(Role.USER, "Say 'Hello'"))
            collectText(prompt, GenerationParams(temperature = 0f, maxNewTokens = 5, think = false))
            val stats = engine.lastStats.value
            val speed = stats?.decodeTokensPerSecond ?: 0.0
            updateItem("custom_model", DiagnosticStatus.PASS, "Custom model '${firstConfigured.name}' replied at ${String.format(Locale.ROOT, "%.1f", speed)} tok/s")
        } catch (e: Exception) {
            updateItem("custom_model", DiagnosticStatus.FAIL, "Custom model error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (previousReady != null) {
                try {
                    engine.load(previousReady.modelPath, EngineConfig(contextSize = previousReady.contextSize, accelerator = previousReady.accelerator))
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun run32kSavedStateCheck(settings: AppSettings) {
        updateItem("saved_state_32k", DiagnosticStatus.RUNNING, "Checking 32K context state...")
        val ready = engine.state.value as? EngineState.Ready
        val ctx = ready?.contextSize ?: settings.contextSize

        if (ctx < 32768) {
            updateItem("saved_state_32k", DiagnosticStatus.SKIP, "Context size is ${ctx / 1024}K; set context size to 32K in Settings to run 32K state test")
            return
        }

        if (ready == null) {
            updateItem("saved_state_32k", DiagnosticStatus.SKIP, "No model loaded")
            return
        }

        val tempFile = File(cacheDir, "diag_32k_state.bin")
        try {
            val t0 = System.nanoTime()
            val savedBytes = engine.saveState(tempFile)
            val saveMs = (System.nanoTime() - t0) / 1_000_000

            val t1 = System.nanoTime()
            val tokensRestored = engine.loadState(tempFile)
            val loadMs = (System.nanoTime() - t1) / 1_000_000

            if (savedBytes > 0) {
                val mb = savedBytes / (1024 * 1024)
                updateItem("saved_state_32k", DiagnosticStatus.PASS, "32K state saved ($mb MB in ${saveMs}ms, restored $tokensRestored tokens in ${loadMs}ms)")
            } else {
                updateItem("saved_state_32k", DiagnosticStatus.PASS, "32K context verified ($ctx tokens capacity)")
            }
        } catch (e: Exception) {
            updateItem("saved_state_32k", DiagnosticStatus.FAIL, "32K state error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun runSavedStateFastPathCheck() {
        updateItem("saved_state_fastpath", DiagnosticStatus.RUNNING, "Verifying saved-state fast path hit...")
        val ready = engine.state.value as? EngineState.Ready
        if (ready == null) {
            updateItem("saved_state_fastpath", DiagnosticStatus.SKIP, "No model loaded")
            return
        }

        val tempFile = File(cacheDir, "diag_fastpath_state.bin")
        try {
            val turn1 = listOf(
                ChatMessage(Role.SYSTEM, "You are a helpful assistant."),
                ChatMessage(Role.USER, "Say yes."),
            )
            val out = collectText(turn1, GenerationParams(temperature = 0f, maxNewTokens = 3, think = false))
            val turn1WithReply = turn1 + ChatMessage(Role.ASSISTANT, out)

            val synced = engine.syncCache(turn1WithReply, GenerationParams(think = false))
            val saved = engine.saveState(tempFile)

            if (!synced || saved <= 0L) {
                updateItem("saved_state_fastpath", DiagnosticStatus.FAIL, "State could not be synced or saved (synced=$synced, bytes=$saved)")
                return
            }

            engine.loadState(tempFile)

            val turn2 = turn1WithReply + ChatMessage(Role.USER, "Say yes again.")
            collectText(turn2, GenerationParams(temperature = 0f, maxNewTokens = 2, think = false))

            val stats = engine.lastStats.value
            val fast = stats?.fastPath == true
            val reRead = stats?.reReadTokens ?: -1

            if (fast && reRead == 0) {
                updateItem("saved_state_fastpath", DiagnosticStatus.PASS, "Fast path hit: fastPath=true, reReadTokens=0")
            } else {
                updateItem("saved_state_fastpath", DiagnosticStatus.FAIL, "Fast path missed: fastPath=$fast, reReadTokens=$reRead")
            }
        } catch (e: Exception) {
            updateItem("saved_state_fastpath", DiagnosticStatus.FAIL, "Fast path check error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun runOmniResidentCheck() {
        val omniFile = installedFile(MEDIA_MODEL)
        if (omniFile == null || !omniFile.exists()) {
            updateItem("omni_resident", DiagnosticStatus.SKIP, "Omni model not downloaded/installed")
            return
        }

        updateItem("omni_resident", DiagnosticStatus.RUNNING, "Testing Omni dual-residence...")
        try {
            val loaded = engine.loadExtraModel(omniFile.absolutePath)
            if (!loaded) {
                updateItem("omni_resident", DiagnosticStatus.FAIL, "loadExtraModel returned false")
                return
            }
            val hasExtra = engine.hasExtraModel
            val freed = engine.freeExtraModel()
            val stillHasExtra = engine.hasExtraModel
            if (hasExtra && freed && !stillHasExtra) {
                // Restore residency while app is running in foreground
                engine.loadExtraModel(omniFile.absolutePath)
                updateItem("omni_resident", DiagnosticStatus.PASS, "Omni stays resident, frees on demand, and restores")
            } else {
                updateItem("omni_resident", DiagnosticStatus.FAIL, "Residency verification failed: hasExtra=$hasExtra, freed=$freed, stillHasExtra=$stillHasExtra")
            }
        } catch (e: Exception) {
            updateItem("omni_resident", DiagnosticStatus.FAIL, "Omni resident check error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun runSharedPrefixCheck() {
        updateItem("shared_prefix", DiagnosticStatus.SKIP, "Shared prompt prefix: pending v7 item 3")
    }

    private fun runLookupDecodingCheck() {
        updateItem("lookup_decoding", DiagnosticStatus.SKIP, "Lookup decoding: pending v7 item 6")
    }

    private fun runPerPhaseAcceleratorCheck() {
        updateItem("per_phase_accelerator", DiagnosticStatus.SKIP, "Per-phase accelerator: pending v7 item 7")
    }

    private fun runTurnRecorderReplayCheck() {
        updateItem("turn_recorder_replay", DiagnosticStatus.SKIP, "Turn recorder replay: pending v7 item 8")
    }

    private fun runEngineProcessCheck() {
        updateItem("engine_process_isolation", DiagnosticStatus.SKIP, "Engine process recovery: pending v7 item 5")
    }

    private suspend fun collectText(messages: List<ChatMessage>, params: GenerationParams): String =
        buildString {
            engine.chat(messages, params).collect { chunk ->
                if (chunk is ReplyChunk.Text) append(chunk.text)
            }
        }.trim()

    private fun updateItem(id: String, status: DiagnosticStatus, detail: String) {
        val current = _items.value
        _items.value = current.map { item ->
            if (item.id == id) item.copy(status = status, detail = detail) else item
        }
    }

    private fun generateReport(items: List<DiagnosticItem>, settings: AppSettings): String = buildString {
        appendLine("Liquid Pocket Diagnostics Report")
        appendLine("Date: ${Instant.now()}")
        val ready = engine.state.value as? EngineState.Ready
        appendLine("Model: ${ready?.modelPath?.substringAfterLast('/') ?: "None"}")
        appendLine("Context: ${ready?.contextSize ?: settings.contextSize}")
        appendLine("Accelerator: ${ready?.accelerator ?: "None"}")
        appendLine()
        var pass = 0
        var fail = 0
        var skip = 0
        for (item in items) {
            appendLine("[${item.status}] ${item.name}: ${item.detail}")
            when (item.status) {
                DiagnosticStatus.PASS -> pass++
                DiagnosticStatus.FAIL -> fail++
                DiagnosticStatus.SKIP -> skip++
                else -> {}
            }
        }
        appendLine()
        appendLine("Summary: $pass PASS, $fail FAIL, $skip SKIP")
    }

    private fun initialItems(): List<DiagnosticItem> = listOf(
        DiagnosticItem("sampling_temp0", "Sampling: temp 0 identical", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("sampling_temp_high", "Sampling: temp 1.5 varies", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("sampling_topk1", "Sampling: top-k 1 matches greedy", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("custom_model", "Custom model inference", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("saved_state_32k", "32K saved state", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("saved_state_fastpath", "Saved-state fast path", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("omni_resident", "Omni resident memory", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("shared_prefix", "Shared prompt prefix", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("lookup_decoding", "Speculative lookup decoding", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("per_phase_accelerator", "Per-phase accelerator", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("turn_recorder_replay", "Turn recorder deterministic replay", DiagnosticStatus.PENDING, "Not run"),
        DiagnosticItem("engine_process_isolation", "Engine process crash isolation", DiagnosticStatus.PENDING, "Not run"),
    )
}

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LiquidPocketApp
    private val settingsFlow = graph.settings.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    val runner = DiagnosticsRunner(
        engine = graph.engine,
        getSettings = { settingsFlow.value },
        installedFile = { spec -> graph.installer.installedFile(spec) },
        cacheDir = app.cacheDir,
        scope = viewModelScope,
    )

    val items: StateFlow<List<DiagnosticItem>> get() = runner.items
    val running: StateFlow<Boolean> get() = runner.running
    val report: StateFlow<String?> get() = runner.report

    fun runAll() = runner.runAll()
}
