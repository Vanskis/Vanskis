package com.vanskis.liquidpocket.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A model the user added: [file], a GGUF from the Hugging Face [repo], or imported from the phone
 * ([repo] empty, [file] its name in the models folder). [vision], [tools] and [thinks] start as
 * detected ([TemplateTraits], an mmproj in the repo) and the user can change them. [hereticRepo],
 * when set, is a Heretic build of it that can be used in its place.
 */
data class CustomModel(
    val id: String,
    val name: String,
    val repo: String,
    val file: String,
    val vision: Boolean = false,
    val tools: Boolean = false,
    val thinks: Boolean = false,
    val hereticRepo: String = "",
) {
    val local: Boolean get() = repo.isEmpty()

    /** What the installer fetches: exactly [file], with an mmproj from the repo for [vision]. */
    val spec: ModelSpec get() = ModelSpec(
        id = id,
        displayName = name,
        repo = repo,
        // A full file name matches only itself (see HuggingFace.selectGguf).
        quant = file.substringAfterLast('/'),
        approxSizeBytes = 0L,
        offered = false,
        mmprojQuants = if (vision && !local) MMPROJ_QUANTS else null,
        thinks = thinks,
    )

    /** Its Heretic build, when [hereticRepo] is a repo. */
    val heretic: ModelSpec? get() = hereticRepo.takeIf(Heretic::isRepo)?.let { Heretic.spec(spec, it) }

    companion object {
        const val ID_PREFIX = "custom-"
        private val MMPROJ_QUANTS = listOf("Q8_0", "F16", "BF16")

        /** A stable id for [file] from [repo] (empty for an import). */
        fun idFor(repo: String, file: String): String =
            ID_PREFIX + "$repo/${file.substringAfterLast('/').removeSuffix(".gguf")}".lowercase()
                .replace(Regex("[^a-z0-9]+"), "-").trim('-')

        /** "LFM2-1.2B-Q4_K_M.gguf" → "LFM2-1.2B-Q4_K_M". */
        fun nameFor(file: String): String = file.substringAfterLast('/').removeSuffix(".gguf")

        fun encode(models: List<CustomModel>): String = buildJsonArray {
            models.forEach { m ->
                add(
                    buildJsonObject {
                        put("id", m.id)
                        put("name", m.name)
                        put("repo", m.repo)
                        put("file", m.file)
                        put("vision", m.vision)
                        put("tools", m.tools)
                        put("thinks", m.thinks)
                        put("hereticRepo", m.hereticRepo)
                    },
                )
            }
        }.toString()

        /** Malformed JSON, or entries missing an id or file, give nothing; this never throws. */
        fun decode(json: String?): List<CustomModel> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                (Json.parseToJsonElement(json) as? JsonArray).orEmpty().mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    fun s(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    fun b(key: String) = (o[key] as? JsonPrimitive)?.booleanOrNull ?: false
                    val id = s("id")?.takeIf { it.startsWith(ID_PREFIX) } ?: return@mapNotNull null
                    val file = s("file")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    CustomModel(id, s("name") ?: nameFor(file), s("repo").orEmpty(), file, b("vision"), b("tools"), b("thinks"), s("hereticRepo").orEmpty())
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/** What a chat template says a model can do. */
data class TemplateTraits(val thinks: Boolean, val tools: Boolean) {
    companion object {
        private val THINKING = listOf("<think>", "enable_thinking", "reasoning_content", "thinking")
        private val TOOLS = listOf("tools", "tool_call", "<tool_call>", "tool_response")

        /** Read from a Jinja chat [template]; none means a plain chat model. */
        fun of(template: String?): TemplateTraits =
            if (template.isNullOrEmpty()) TemplateTraits(thinks = false, tools = false)
            else TemplateTraits(THINKING.any { it in template }, TOOLS.any { it in template })
    }
}

/** Reads `tokenizer.chat_template` from a GGUF file's header (versions 2 and 3). */
object GgufHeader {
    private const val MAGIC = 0x46554747 // "GGUF", little-endian
    private const val TEMPLATE_KEY = "tokenizer.chat_template"
    private const val MAX_STRING = 16 * 1024 * 1024L

    /** The chat template, or null when the header has none; [IOException] when it is not a GGUF. */
    fun chatTemplate(input: InputStream): String? {
        val r = LittleEndian(DataInputStream(input.buffered()))
        if (r.int() != MAGIC) throw IOException("Not a GGUF file")
        val version = r.int()
        if (version < 2) throw IOException("GGUF version $version is not supported")
        r.long() // tensor count
        val kvCount = r.long()
        for (i in 0 until kvCount) {
            val key = r.string()
            val type = r.int()
            if (key == TEMPLATE_KEY && type == TYPE_STRING) return r.string()
            skip(r, type)
        }
        return null
    }

    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9

    private fun skip(r: LittleEndian, type: Int) {
        when (type) {
            0, 1, 7 -> r.skip(1)
            2, 3 -> r.skip(2)
            4, 5, 6 -> r.skip(4)
            10, 11, 12 -> r.skip(8)
            TYPE_STRING -> r.skip(r.length())
            TYPE_ARRAY -> {
                val itemType = r.int()
                val count = r.long()
                for (i in 0 until count) skip(r, itemType)
            }
            else -> throw IOException("Unknown GGUF value type $type")
        }
    }

    private class LittleEndian(private val d: DataInputStream) {
        fun int(): Int = Integer.reverseBytes(d.readInt())
        fun long(): Long = java.lang.Long.reverseBytes(d.readLong())
        fun length(): Long = long().also { if (it < 0 || it > MAX_STRING) throw IOException("Bad GGUF string length $it") }
        fun string(): String = ByteArray(length().toInt()).also { d.readFully(it) }.decodeToString()
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val skipped = d.skipBytes(minOf(left, Int.MAX_VALUE.toLong()).toInt())
                if (skipped <= 0) {
                    d.readByte() // throws at the end of the file
                    left--
                } else {
                    left -= skipped
                }
            }
        }
    }
}

/** The chat template Hugging Face lists for a GGUF repo (`/api/models/<repo>` → `gguf.chat_template`). */
fun HuggingFace.infoUrl(repo: String, base: String = HuggingFace.BASE_URL): String = "$base/api/models/$repo"

fun HuggingFace.parseChatTemplate(json: String): String? = try {
    ((Json.parseToJsonElement(json) as? JsonObject)?.get("gguf") as? JsonObject)?.get("chat_template")?.jsonPrimitive?.contentOrNull
} catch (_: Exception) {
    null
}

/**
 * A [ModelSpec] as plain strings, for a download job's input: catalogued and Heretic models are
 * found again by id, a custom one (or its Heretic build) only from these.
 */
object ModelSpecData {
    fun encode(spec: ModelSpec): Map<String, String> = mapOf(
        "spec_id" to spec.id,
        "spec_name" to spec.displayName,
        "spec_repo" to spec.repo,
        "spec_quant" to spec.quant,
        "spec_mmproj" to spec.mmprojQuants.orEmpty().joinToString(","),
        "spec_vision" to (spec.mmprojQuants != null).toString(),
        "spec_fallback" to spec.fallbackQuants.joinToString(","),
        "spec_thinks" to spec.thinks.toString(),
    )

    /** Null when [get] lacks an id, repo or quant. */
    fun decode(get: (String) -> String?): ModelSpec? {
        val id = get("spec_id")?.takeIf { it.isNotEmpty() } ?: return null
        val repo = get("spec_repo")?.takeIf { it.isNotEmpty() } ?: return null
        val quant = get("spec_quant")?.takeIf { it.isNotEmpty() } ?: return null
        fun list(key: String) = get(key).orEmpty().split(',').filter { it.isNotEmpty() }
        return ModelSpec(
            id = id,
            displayName = get("spec_name") ?: id,
            repo = repo,
            quant = quant,
            approxSizeBytes = 0L,
            offered = false,
            mmprojQuants = if (get("spec_vision") == "true") list("spec_mmproj") else null,
            fallbackQuants = list("spec_fallback"),
            thinks = get("spec_thinks") != "false",
        )
    }
}
