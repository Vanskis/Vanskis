package com.vanskis.liquidpocket.chat

import android.content.ComponentCallbacks2
import com.vanskis.liquidpocket.engine.Capabilities
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.ReplyChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class OmniResidencyTest {

    private class ResidentEngine : Engine {
        override val state = MutableStateFlow<EngineState>(EngineState.Idle)
        override val capabilities: StateFlow<Capabilities> = MutableStateFlow(Capabilities.NONE)
        override val lastStats: StateFlow<GenerationStats?> = MutableStateFlow(null)
        var extraLoaded = false
        var loadExtraCalls = 0
        var freeExtraCalls = 0
        var failExtraLoad = false

        override val hasExtraModel: Boolean
            get() = extraLoaded

        override suspend fun loadExtraModel(modelPath: String): Boolean {
            loadExtraCalls++
            if (failExtraLoad) return false
            extraLoaded = true
            return true
        }

        override suspend fun freeExtraModel(): Boolean {
            freeExtraCalls++
            val was = extraLoaded
            extraLoaded = false
            return was
        }

        override suspend fun load(modelPath: String, config: EngineConfig) {
            state.value = EngineState.Ready(modelPath, config.contextSize)
        }

        override suspend fun unload() {
            state.value = EngineState.Idle
        }

        override fun chat(messages: List<ChatMessage>, params: GenerationParams): Flow<ReplyChunk> = emptyFlow()

        override fun stop() {}
    }

    /** Mock helper emulating LiquidPocketApp's residency lifecycle manager. */
    private class OmniLifecycleManager(
        private val engine: Engine,
        private val getOmniFile: () -> File?,
        private val scope: CoroutineScope,
    ) {
        var startedActivities = 0
            private set

        fun onActivityStarted() {
            startedActivities++
            if (startedActivities == 1) {
                preloadOmniIfInstalled()
            }
        }

        fun onActivityStopped() {
            startedActivities--
            if (startedActivities <= 0) {
                startedActivities = 0
                scope.launch { engine.freeExtraModel() }
            }
        }

        fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                scope.launch { engine.freeExtraModel() }
            }
        }

        fun preloadOmniIfInstalled() {
            val omni = getOmniFile()
            if (omni != null && omni.exists() && !engine.hasExtraModel) {
                scope.launch { engine.loadExtraModel(omni.absolutePath) }
            }
        }
    }

    @Test
    fun engineInterfaceDefaultsAreSafe() = runTest {
        val dummy = object : Engine {
            override val state = MutableStateFlow<EngineState>(EngineState.Idle)
            override val capabilities: StateFlow<Capabilities> = MutableStateFlow(Capabilities.NONE)
            override val lastStats: StateFlow<GenerationStats?> = MutableStateFlow(null)
            override suspend fun load(modelPath: String, config: EngineConfig) {}
            override suspend fun unload() {}
            override fun chat(messages: List<ChatMessage>, params: GenerationParams): Flow<ReplyChunk> = emptyFlow()
            override fun stop() {}
        }
        assertFalse(dummy.hasExtraModel)
        assertFalse(dummy.loadExtraModel("/tmp/dummy.gguf"))
        assertFalse(dummy.freeExtraModel())
    }

    @Test
    fun omniPreloadsOnForegroundWhenInstalled() = runTest {
        val engine = ResidentEngine()
        val omniFile = File.createTempFile("omni", ".gguf").apply { deleteOnExit() }
        val manager = OmniLifecycleManager(engine, { omniFile }, this)

        assertFalse(engine.hasExtraModel)
        manager.onActivityStarted()
        advanceUntilIdle()

        assertTrue(engine.hasExtraModel)
        assertEquals(1, engine.loadExtraCalls)
        assertEquals(1, manager.startedActivities)
    }

    @Test
    fun omniDoesNotPreloadWhenNotInstalled() = runTest {
        val engine = ResidentEngine()
        val manager = OmniLifecycleManager(engine, { null }, this)

        manager.onActivityStarted()
        advanceUntilIdle()
        assertFalse(engine.hasExtraModel)
        assertEquals(0, engine.loadExtraCalls)
    }

    @Test
    fun omniFreesOnBackgroundWhenAllActivitiesStop() = runTest {
        val engine = ResidentEngine()
        val omniFile = File.createTempFile("omni", ".gguf").apply { deleteOnExit() }
        val manager = OmniLifecycleManager(engine, { omniFile }, this)

        manager.onActivityStarted()
        advanceUntilIdle()
        assertTrue(engine.hasExtraModel)

        // Multiple activities open (e.g. Settings)
        manager.onActivityStarted()
        advanceUntilIdle()
        assertEquals(2, manager.startedActivities)
        assertTrue(engine.hasExtraModel)

        // Pop one activity
        manager.onActivityStopped()
        advanceUntilIdle()
        assertEquals(1, manager.startedActivities)
        assertTrue(engine.hasExtraModel)

        // Pop last activity -> app sent to background
        manager.onActivityStopped()
        advanceUntilIdle()
        assertEquals(0, manager.startedActivities)
        assertFalse(engine.hasExtraModel)
        assertEquals(1, engine.freeExtraCalls)
    }

    @Test
    fun omniFreesOnLowMemorySignal() = runTest {
        val engine = ResidentEngine()
        val omniFile = File.createTempFile("omni", ".gguf").apply { deleteOnExit() }
        val manager = OmniLifecycleManager(engine, { omniFile }, this)

        manager.onActivityStarted()
        advanceUntilIdle()
        assertTrue(engine.hasExtraModel)

        // Moderate trim does not free extra model
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        advanceUntilIdle()
        assertTrue(engine.hasExtraModel)
        assertEquals(0, engine.freeExtraCalls)

        // Low memory signal frees extra model
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        advanceUntilIdle()
        assertFalse(engine.hasExtraModel)
        assertEquals(1, engine.freeExtraCalls)

        // Critical memory signal also frees
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        advanceUntilIdle()
        assertEquals(2, engine.freeExtraCalls)
    }

    @Test
    fun fallbackToSwappingWhenPreloadFails() = runTest {
        val engine = ResidentEngine()
        engine.failExtraLoad = true
        val omniFile = File.createTempFile("omni", ".gguf").apply { deleteOnExit() }
        val manager = OmniLifecycleManager(engine, { omniFile }, this)

        manager.onActivityStarted()
        advanceUntilIdle()
        assertFalse(engine.hasExtraModel)
        assertEquals(1, engine.loadExtraCalls)
    }
}
