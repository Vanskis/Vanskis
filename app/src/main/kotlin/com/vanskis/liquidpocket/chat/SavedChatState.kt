package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineState
import java.io.File

/**
 * Key identifying the model file and context settings.
 * Each saved chat state is tied to this; any mismatch deletes the state file and falls back to re-reading.
 */
data class ModelStateKey(
    val modelPath: String,
    val modelSize: Long,
    val modelLastModified: Long,
    val contextSize: Int,
) {
    companion object {
        fun from(engineState: EngineState): ModelStateKey? {
            val ready = engineState as? EngineState.Ready ?: return null
            val file = File(ready.modelPath)
            if (!file.exists()) return null
            return ModelStateKey(
                modelPath = ready.modelPath,
                modelSize = file.length(),
                modelLastModified = file.lastModified(),
                contextSize = ready.contextSize,
            )
        }
    }
}

/**
 * Metadata recorded next to each chat's KV state file.
 */
data class StateMeta(
    val conversationId: Long,
    val modelPath: String,
    val modelSize: Long,
    val modelLastModified: Long,
    val contextSize: Int,
    val stateBytes: Long,
    val lastAccessed: Long,
) {
    fun matches(key: ModelStateKey): Boolean =
        modelPath == key.modelPath &&
        modelSize == key.modelSize &&
        modelLastModified == key.modelLastModified &&
        contextSize == key.contextSize
}

/**
 * Manages persisted KV cache states for conversations under [kvDir].
 *
 * Rules:
 * - Stores state (.bin), text sidecar (.bin.text), and metadata (.meta) by conversation id.
 * - Each state is tied to the model file and context settings ([ModelStateKey]). A changed model file or
 *   context deletes the files; another model being loaded leaves them for when this one returns.
 * - Deletes state on conversation delete, truncate/edit before end, or model change.
 * - Total size cap [maxBytes] (default 512 MB): evicts least recently used state files past cap.
 * - Never saves a single state larger than [maxBytes].
 * - Failures fall back silently to normal re-reading without surfacing chat errors.
 */
class SavedChatState(
    val kvDir: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val saveState: suspend (File) -> Long,
    private val loadState: suspend (File) -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(
        kvDir: File,
        engine: Engine,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        kvDir = kvDir,
        maxBytes = maxBytes,
        saveState = { file -> engine.saveState(file) },
        loadState = { file -> engine.loadState(file) },
        clock = clock,
    )

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024 * 1024 // 512 MB
    }

    fun binFile(conversationId: Long) = File(kvDir, "$conversationId.bin")
    fun textFile(conversationId: Long) = File(kvDir, "$conversationId.bin.text")
    fun metaFile(conversationId: Long) = File(kvDir, "$conversationId.meta")

    /** Total bytes occupied by all files in [kvDir]. */
    fun totalBytes(): Long {
        if (!kvDir.exists()) return 0L
        return kvDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Whether a saved state exists for [conversationId]. */
    fun hasState(conversationId: Long): Boolean =
        binFile(conversationId).exists() && metaFile(conversationId).exists()

    /**
     * Saves sequence state for [conversationId] under [key].
     * Returns true if saved, false if not saved (multimodal units, too large, or failure).
     */
    suspend fun save(conversationId: Long, key: ModelStateKey): Boolean {
        try {
            if (!kvDir.exists()) kvDir.mkdirs()

            val bin = binFile(conversationId)
            val text = textFile(conversationId)
            val meta = metaFile(conversationId)

            val bytes = saveState(bin)
            if (bytes <= 0L) {
                // Not saved: native returned 0 (e.g. multimodal units or empty cache)
                delete(conversationId)
                return false
            }

            // Do not save a single state larger than the cap
            val singleStateTotal = bin.length() + (if (text.exists()) text.length() else 0L)
            if (singleStateTotal > maxBytes) {
                delete(conversationId)
                return false
            }

            // Write metadata
            val now = clock()
            val stateMeta = StateMeta(
                conversationId = conversationId,
                modelPath = key.modelPath,
                modelSize = key.modelSize,
                modelLastModified = key.modelLastModified,
                contextSize = key.contextSize,
                stateBytes = bytes,
                lastAccessed = now,
            )
            writeMeta(meta, stateMeta)
            bin.setLastModified(now)

            // Evict LRU files past cap
            evictPastCap(keepConversationId = conversationId)
            return true
        } catch (_: Exception) {
            delete(conversationId)
            return false
        }
    }

    /**
     * Restores sequence state for [conversationId] if its metadata matches [key].
     * Returns true if restored; false if missing, saved for another model (kept), mismatched (deleted), or load failed.
     */
    suspend fun load(conversationId: Long, key: ModelStateKey): Boolean {
        try {
            val bin = binFile(conversationId)
            val meta = metaFile(conversationId)
            if (!bin.exists() || !meta.exists()) {
                delete(conversationId)
                return false
            }

            val savedMeta = readMeta(meta)
            // Another model is loaded (Omni for a photo, say): keep the state for when this chat's model returns.
            if (savedMeta != null && savedMeta.modelPath != key.modelPath) return false
            if (savedMeta == null || !savedMeta.matches(key)) {
                // Key mismatch: delete the file and fall back to normal re-prefill
                delete(conversationId)
                return false
            }

            val tokens = loadState(bin)
            if (tokens <= 0) {
                // Load failed: delete and fall back
                delete(conversationId)
                return false
            }

            // Touch files to update LRU order
            val now = clock()
            writeMeta(meta, savedMeta.copy(lastAccessed = now))
            bin.setLastModified(now)
            return true
        } catch (_: Exception) {
            delete(conversationId)
            return false
        }
    }

    /** Deletes all state files for [conversationId]. */
    fun delete(conversationId: Long) {
        val bin = binFile(conversationId)
        val text = textFile(conversationId)
        val meta = metaFile(conversationId)
        if (bin.exists()) bin.delete()
        if (text.exists()) text.delete()
        if (meta.exists()) meta.delete()
    }

    /** Deletes all saved states in [kvDir]. */
    fun clear() {
        if (!kvDir.exists()) return
        kvDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Evicts least recently used state files until total size is within [maxBytes].
     */
    fun evictPastCap(keepConversationId: Long? = null) {
        if (!kvDir.exists()) return
        val metas = listMetas().filter { it.conversationId != keepConversationId }
            .sortedBy { it.lastAccessed }

        for (meta in metas) {
            if (totalBytes() <= maxBytes) break
            delete(meta.conversationId)
        }
    }

    private fun listMetas(): List<StateMeta> {
        if (!kvDir.exists()) return emptyList()
        val metaFiles = kvDir.listFiles { _, name -> name.endsWith(".meta") } ?: return emptyList()
        return metaFiles.mapNotNull { file ->
            readMeta(file) ?: file.name.removeSuffix(".meta").toLongOrNull()?.let { id ->
                StateMeta(id, "", 0L, 0L, 0, 0L, 0L)
            }
        }
    }

    private fun writeMeta(file: File, meta: StateMeta) {
        file.writeText(
            buildString {
                appendLine("conversationId=${meta.conversationId}")
                appendLine("modelPath=${meta.modelPath}")
                appendLine("modelSize=${meta.modelSize}")
                appendLine("modelLastModified=${meta.modelLastModified}")
                appendLine("contextSize=${meta.contextSize}")
                appendLine("stateBytes=${meta.stateBytes}")
                appendLine("lastAccessed=${meta.lastAccessed}")
            }
        )
    }

    private fun readMeta(file: File): StateMeta? {
        if (!file.exists()) return null
        return try {
            val lines = file.readLines()
            val map = lines.mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq) to line.substring(eq + 1) else null
            }.toMap()
            StateMeta(
                conversationId = map["conversationId"]?.toLongOrNull() ?: return null,
                modelPath = map["modelPath"] ?: return null,
                modelSize = map["modelSize"]?.toLongOrNull() ?: return null,
                modelLastModified = map["modelLastModified"]?.toLongOrNull() ?: return null,
                contextSize = map["contextSize"]?.toIntOrNull() ?: return null,
                stateBytes = map["stateBytes"]?.toLongOrNull() ?: 0L,
                lastAccessed = map["lastAccessed"]?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            null
        }
    }
}
