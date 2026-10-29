package com.vanskis.liquidpocket.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/** One file entry from `https://huggingface.co/api/models/<repo>/tree/main`. */
data class RepoFile(
    val path: String,
    val size: Long,
    /** sha256 of the content, present for LFS files (`lfs.oid`). */
    val sha256: String?,
)

object HuggingFace {
    const val BASE_URL = "https://huggingface.co"

    fun treeUrl(repo: String, base: String = BASE_URL): String = "$base/api/models/$repo/tree/main"

    fun resolveUrl(repo: String, path: String, base: String = BASE_URL): String =
        "$base/$repo/resolve/main/" + path.split('/').joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Parses the tree listing; directories and malformed entries are skipped. */
    fun parseTree(json: String): List<RepoFile> {
        val root = Json.parseToJsonElement(json)
        require(root is JsonArray) { "Expected a JSON array from the tree API" }
        return root.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            if (obj.string("type") == "directory") return@mapNotNull null
            val path = obj.string("path") ?: return@mapNotNull null
            val lfs = obj["lfs"] as? JsonObject
            val size = lfs?.long("size") ?: obj.long("size") ?: return@mapNotNull null
            RepoFile(path = path, size = size, sha256 = lfs?.string("oid")?.lowercase())
        }
    }

    /**
     * Picks the main-model GGUF for [quant]: name contains the quant (case-insensitive), is a
     * `.gguf`, and is not a multimodal projector (`mmproj`). Split shards (`-00001-of-0000N`) are
     * not supported in M1 and are skipped. Ties go to the shortest path.
     */
    fun selectGguf(files: List<RepoFile>, quant: String): RepoFile? =
        files.filter { f ->
            val name = f.path.substringAfterLast('/').lowercase()
            name.endsWith(".gguf") &&
                name.contains(quant.lowercase()) &&
                !name.contains("mmproj") &&
                !SHARD.containsMatchIn(name)
        }.minByOrNull { it.path.length }

    /**
     * Picks the multimodal projector: the first of [prefs] (case-insensitive) that some `mmproj`
     * `.gguf` name contains, else the smallest `mmproj` file. Ties go to the shortest path.
     */
    fun selectMmproj(files: List<RepoFile>, prefs: List<String>): RepoFile? {
        val projs = files.filter { f ->
            val name = f.path.substringAfterLast('/').lowercase()
            name.endsWith(".gguf") && name.contains("mmproj")
        }
        for (q in prefs) {
            projs.filter { it.path.substringAfterLast('/').lowercase().contains(q.lowercase()) }
                .minByOrNull { it.path.length }?.let { return it }
        }
        return projs.minWithOrNull(compareBy<RepoFile> { it.size }.thenBy { it.path.length })
    }

    private val SHARD = Regex("""-\d{5}-of-\d{5}\.gguf$""")

    private fun JsonObject.string(key: String): String? = (this[key])?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key])?.jsonPrimitive?.longOrNull
}
