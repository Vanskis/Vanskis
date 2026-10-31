package com.vanskis.liquidpocket.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavedChatStateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var kvDir: File
    private var simulatedTime = 1_000_000L

    private val modelA = ModelStateKey(
        modelPath = "/models/chat-model.gguf",
        modelSize = 2_000_000_000L,
        modelLastModified = 1_700_000_000L,
        contextSize = 8192,
    )

    @Before
    fun setUp() {
        kvDir = tempFolder.newFolder("kv")
        simulatedTime = 1_000_000L
    }

    private fun createStore(
        maxBytes: Long = SavedChatState.DEFAULT_MAX_BYTES,
        saveBehavior: (File) -> Long = { file ->
            file.writeBytes(ByteArray(400))
            File(file.parentFile, "${file.name}.text").writeText("cached text")
            400L
        },
        loadBehavior: (File) -> Int = { 100 },
    ): SavedChatState = SavedChatState(
        kvDir = kvDir,
        maxBytes = maxBytes,
        saveState = { file -> saveBehavior(file) },
        loadState = { file -> loadBehavior(file) },
        clock = { simulatedTime },
    )

    @Test
    fun keyMismatchDeletesFilesAndFallsBack() = runBlocking {
        val store = createStore()

        // 1. Another model loaded: not restored, but kept for when this chat's model returns
        simulatedTime += 10
        assertTrue(store.save(1L, modelA))
        assertTrue(store.hasState(1L))
        val otherModel = modelA.copy(modelPath = "/models/other-model.gguf")
        assertFalse("Another model should not restore the state", store.load(1L, otherModel))
        assertTrue("State saved for another model should be kept", store.hasState(1L))
        assertTrue(store.textFile(1L).exists())
        assertTrue("The chat's own model restores it", store.load(1L, modelA))

        // 2. Mismatch in modelSize
        simulatedTime += 10
        assertTrue(store.save(2L, modelA))
        assertTrue(store.hasState(2L))
        val wrongSize = modelA.copy(modelSize = 999L)
        assertFalse("Mismatched modelSize should fail to load", store.load(2L, wrongSize))
        assertFalse(store.hasState(2L))

        // 3. Mismatch in modelLastModified
        simulatedTime += 10
        assertTrue(store.save(3L, modelA))
        assertTrue(store.hasState(3L))
        val wrongMtime = modelA.copy(modelLastModified = 8888L)
        assertFalse("Mismatched modelLastModified should fail to load", store.load(3L, wrongMtime))
        assertFalse(store.hasState(3L))

        // 4. Mismatch in contextSize
        simulatedTime += 10
        assertTrue(store.save(4L, modelA))
        assertTrue(store.hasState(4L))
        val wrongCtx = modelA.copy(contextSize = 16384)
        assertFalse("Mismatched contextSize should fail to load", store.load(4L, wrongCtx))
        assertFalse(store.hasState(4L))
    }

    @Test
    fun capEvictsOldestLru() = runBlocking {
        // Cap set to 1200 bytes (~2 conversations of ~500 bytes each)
        val capBytes = 1200L
        val store = createStore(
            maxBytes = capBytes,
            saveBehavior = { file ->
                file.writeBytes(ByteArray(400))
                File(file.parentFile, "${file.name}.text").writeText("txt")
                400L
            },
        )

        // Save conversation 1
        simulatedTime = 100L
        assertTrue(store.save(1L, modelA))
        assertTrue(store.hasState(1L))

        // Save conversation 2
        simulatedTime = 200L
        assertTrue(store.save(2L, modelA))
        assertTrue(store.hasState(2L))
        assertTrue("Both conversations exist within cap", store.hasState(1L) && store.hasState(2L))

        // Save conversation 3: pushes total size past cap (3 * ~500 > 1200)
        simulatedTime = 300L
        assertTrue(store.save(3L, modelA))
        assertTrue("Newest conversation 3 is saved", store.hasState(3L))
        assertTrue("Conversation 2 is kept", store.hasState(2L))
        assertFalse("Oldest conversation 1 must be evicted past cap", store.hasState(1L))
        assertTrue("Total bytes must be within cap", store.totalBytes() <= capBytes)
    }

    @Test
    fun lruOrderUpdatedOnLoad() = runBlocking {
        // Cap of 1200 bytes
        val capBytes = 1200L
        val store = createStore(
            maxBytes = capBytes,
            saveBehavior = { file ->
                file.writeBytes(ByteArray(400))
                File(file.parentFile, "${file.name}.text").writeText("txt")
                400L
            },
        )

        // Save conv 1 at t=100
        simulatedTime = 100L
        assertTrue(store.save(1L, modelA))

        // Save conv 2 at t=200
        simulatedTime = 200L
        assertTrue(store.save(2L, modelA))

        // Access (load) conv 1 at t=300 -> conv 1 is now more recently used than conv 2!
        simulatedTime = 300L
        assertTrue(store.load(1L, modelA))

        // Save conv 3 at t=400: past cap, should evict LRU (conv 2) instead of conv 1
        simulatedTime = 400L
        assertTrue(store.save(3L, modelA))

        assertTrue("Conv 1 was touched more recently, so it is kept", store.hasState(1L))
        assertTrue("Conv 3 was just saved, so it is kept", store.hasState(3L))
        assertFalse("Conv 2 was LRU, so it was evicted", store.hasState(2L))
    }

    @Test
    fun doesNotSaveSingleStateLargerThanCap() = runBlocking {
        val capBytes = 500L
        val store = createStore(
            maxBytes = capBytes,
            saveBehavior = { file ->
                file.writeBytes(ByteArray(600))
                600L
            },
        )

        assertFalse("State larger than cap should not be saved", store.save(1L, modelA))
        assertFalse("Files for rejected oversized state should not exist", store.hasState(1L))
        assertEquals(0L, store.totalBytes())
    }

    @Test
    fun chatDeleteRemovesFiles() = runBlocking {
        val store = createStore()

        assertTrue(store.save(1L, modelA))
        assertTrue(store.binFile(1L).exists())
        assertTrue(store.textFile(1L).exists())
        assertTrue(store.metaFile(1L).exists())

        store.delete(1L)
        assertFalse(store.binFile(1L).exists())
        assertFalse(store.textFile(1L).exists())
        assertFalse(store.metaFile(1L).exists())
        assertFalse(store.hasState(1L))
    }

    @Test
    fun failureFallsBackSilentlyAndDeletesCorruptedFiles() = runBlocking {
        var failLoad = false
        val store = createStore(
            loadBehavior = { file ->
                if (failLoad) throw IllegalStateException("Corrupted state") else 100
            },
        )

        assertTrue(store.save(1L, modelA))

        failLoad = true
        assertFalse("Load failure should fall back to false without throwing", store.load(1L, modelA))
        assertFalse("Corrupted file should be deleted on load failure", store.hasState(1L))
    }

    @Test
    fun multimodalUnitsNotSaved() = runBlocking {
        // Native returns 0 when multimodal chunks are present in cache
        val store = createStore(
            saveBehavior = { file -> 0L },
        )

        assertFalse("Multimodal turn must not be saved", store.save(1L, modelA))
        assertFalse(store.hasState(1L))
        assertEquals(0L, store.totalBytes())
    }
}
