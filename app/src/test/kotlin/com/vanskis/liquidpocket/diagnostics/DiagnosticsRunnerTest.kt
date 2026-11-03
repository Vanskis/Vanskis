package com.vanskis.liquidpocket.diagnostics

import com.vanskis.liquidpocket.engine.Capabilities
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.ReplyChunk
import com.vanskis.liquidpocket.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeEngine : Engine {
        override val state = MutableStateFlow<EngineState>(EngineState.Ready("/models/chat.gguf", 8192))
        override val capabilities = MutableStateFlow(Capabilities.NONE)
        override val lastStats = MutableStateFlow<GenerationStats?>(null)

        var chatResponses = mutableListOf<String>()
        var chatCallCount = 0
        var syncResult = true
        var saveBytes = 500L
        var loadTokens = 100
        var fastPath = true
        var reReadTokens = 0
        var extraModelLoaded = false
        var loadExtraModelResult = true

        override val hasExtraModel: Boolean
            get() = extraModelLoaded

        override suspend fun loadExtraModel(modelPath: String): Boolean {
            if (loadExtraModelResult) {
                extraModelLoaded = true
                return true
            }
            return false
        }

        override suspend fun freeExtraModel(): Boolean {
            if (extraModelLoaded) {
                extraModelLoaded = false
                return true
            }
            return false
        }

        override suspend fun load(modelPath: String, config: EngineConfig) {
            state.value = EngineState.Ready(modelPath, config.contextSize)
        }

        override suspend fun unload() {
            state.value = EngineState.Idle
        }

        override fun chat(messages: List<ChatMessage>, params: GenerationParams): Flow<ReplyChunk> = flow {
            chatCallCount++
            val response = if (chatResponses.isNotEmpty()) chatResponses.removeAt(0) else "default reply"
            emit(ReplyChunk.Text(response))
            lastStats.value = GenerationStats(
                promptTokens = 10,
                prefillTokensPerSecond = 50.0,
                generatedTokens = 5,
                decodeTokensPerSecond = 25.0,
                fastPath = fastPath,
                reReadTokens = reReadTokens,
            )
        }

        override fun stop() {}

        override suspend fun syncCache(messages: List<ChatMessage>, params: GenerationParams): Boolean = syncResult

        override suspend fun saveState(file: File): Long {
            file.writeBytes(ByteArray(saveBytes.toInt()))
            return saveBytes
        }

        override suspend fun loadState(file: File): Int = loadTokens
    }

    @Test
    fun initialItemsAreAllPending() = runTest {
        val engine = FakeEngine()
        val runner = DiagnosticsRunner(
            engine = engine,
            getSettings = { AppSettings() },
            installedFile = { null },
            cacheDir = tempFolder.newFolder(),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val items = runner.items.value
        assertEquals(12, items.size)
        assertTrue(items.all { it.status == DiagnosticStatus.PENDING })
        assertFalse(runner.running.value)
    }

    @Test
    fun runAllSamplingAndSavedStateFastPathPass() = runTest {
        val engine = FakeEngine()
        // Provide responses for sampling tests:
        // Temp 0 runs: out1, out2
        engine.chatResponses.add("Sentence one.")
        engine.chatResponses.add("Sentence one.")
        // Temp 1.5 runs: outHigh1, outHigh2
        engine.chatResponses.add("Red")
        engine.chatResponses.add("Blue")
        // Top-k 1 runs: greedyOut, topKOut
        engine.chatResponses.add("3, 4, 5")
        engine.chatResponses.add("3, 4, 5")
        // Saved state fast path runs: turn1 out, turn2 out
        engine.chatResponses.add("yes")
        engine.chatResponses.add("yes again")

        val runner = DiagnosticsRunner(
            engine = engine,
            getSettings = { AppSettings(contextSize = 8192) },
            installedFile = { null },
            cacheDir = tempFolder.newFolder(),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        runner.runAll()
        advanceUntilIdle()

        assertFalse(runner.running.value)
        val itemsById = runner.items.value.associateBy { it.id }

        // Sampling checks
        assertEquals(DiagnosticStatus.PASS, itemsById["sampling_temp0"]?.status)
        assertEquals(DiagnosticStatus.PASS, itemsById["sampling_temp_high"]?.status)
        assertEquals(DiagnosticStatus.PASS, itemsById["sampling_topk1"]?.status)

        // Custom model check (none configured) -> SKIP
        assertEquals(DiagnosticStatus.SKIP, itemsById["custom_model"]?.status)

        // 32K context check (<32K) -> SKIP
        assertEquals(DiagnosticStatus.SKIP, itemsById["saved_state_32k"]?.status)

        // Saved state fast path -> PASS
        assertEquals(DiagnosticStatus.PASS, itemsById["saved_state_fastpath"]?.status)

        // Placeholders -> SKIP
        assertEquals(DiagnosticStatus.SKIP, itemsById["omni_resident"]?.status)
        assertEquals(DiagnosticStatus.SKIP, itemsById["shared_prefix"]?.status)
        assertEquals(DiagnosticStatus.SKIP, itemsById["lookup_decoding"]?.status)
        assertEquals(DiagnosticStatus.SKIP, itemsById["per_phase_accelerator"]?.status)
        assertEquals(DiagnosticStatus.SKIP, itemsById["turn_recorder_replay"]?.status)
        assertEquals(DiagnosticStatus.SKIP, itemsById["engine_process_isolation"]?.status)

        // Formatted report generated
        val report = runner.report.value
        assertNotNull(report)
        assertTrue(report!!.contains("Liquid Pocket Diagnostics Report"))
        assertTrue(report.contains("PASS"))
        assertTrue(report.contains("SKIP"))
        assertTrue(report.contains("Summary: 4 PASS, 0 FAIL, 8 SKIP"))
    }

    @Test
    fun runAll32kContextStateExecutesWhenConfigured() = runTest {
        val engine = FakeEngine()
        engine.state.value = EngineState.Ready("/models/chat.gguf", 32768)
        engine.saveBytes = 10 * 1024 * 1024L
        engine.loadTokens = 30000

        val runner = DiagnosticsRunner(
            engine = engine,
            getSettings = { AppSettings(contextSize = 32768) },
            installedFile = { null },
            cacheDir = tempFolder.newFolder(),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        runner.runAll()
        advanceUntilIdle()

        val itemsById = runner.items.value.associateBy { it.id }
        assertEquals(DiagnosticStatus.PASS, itemsById["saved_state_32k"]?.status)
        assertTrue(itemsById["saved_state_32k"]?.detail?.contains("32K state saved") == true)
    }

    @Test
    fun runAllOmniResidentPassesWhenInstalled() = runTest {
        val engine = FakeEngine()
        val omniFile = tempFolder.newFile("omni.gguf")

        val runner = DiagnosticsRunner(
            engine = engine,
            getSettings = { AppSettings() },
            installedFile = { spec -> if (spec == com.vanskis.liquidpocket.MEDIA_MODEL) omniFile else null },
            cacheDir = tempFolder.newFolder(),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        runner.runAll()
        advanceUntilIdle()

        val itemsById = runner.items.value.associateBy { it.id }
        assertEquals(DiagnosticStatus.PASS, itemsById["omni_resident"]?.status)
        assertTrue(itemsById["omni_resident"]?.detail?.contains("Omni stays resident") == true)
        assertTrue(engine.hasExtraModel)
    }

    @Test
    fun runAllOmniResidentFailsWhenLoadFails() = runTest {
        val engine = FakeEngine()
        engine.loadExtraModelResult = false
        val omniFile = tempFolder.newFile("omni.gguf")

        val runner = DiagnosticsRunner(
            engine = engine,
            getSettings = { AppSettings() },
            installedFile = { spec -> if (spec == com.vanskis.liquidpocket.MEDIA_MODEL) omniFile else null },
            cacheDir = tempFolder.newFolder(),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        runner.runAll()
        advanceUntilIdle()

        val itemsById = runner.items.value.associateBy { it.id }
        assertEquals(DiagnosticStatus.FAIL, itemsById["omni_resident"]?.status)
        assertTrue(itemsById["omni_resident"]?.detail?.contains("loadExtraModel returned false") == true)
    }
}
