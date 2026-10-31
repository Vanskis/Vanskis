package com.vanskis.liquidpocket.settings

import com.vanskis.liquidpocket.core.Heretic
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.Sampling
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * One model's own settings: how tokens are picked ([sampling]), the longest reply ([maxReply], 0
 * for until the context is full) and [instructions] given to it on top of the custom instructions.
 */
data class ModelTuning(
    val sampling: Sampling = Sampling(),
    val maxReply: Int = 0,
    val instructions: String = "",
) {
    val maxNewTokens: Int get() = if (maxReply > 0) maxReply else GenerationParams.UNTIL_CONTEXT_FULL

    /** Kept in range, so a stored value cannot break a reply. */
    fun clamped(): ModelTuning = copy(
        sampling = sampling.copy(
            topP = sampling.topP.coerceIn(0f, 1f),
            topK = sampling.topK.coerceIn(0, MAX_TOP_K),
            minP = sampling.minP.coerceIn(0f, 1f),
            repeatPenalty = sampling.repeatPenalty.coerceIn(1f, MAX_REPEAT_PENALTY),
            seed = sampling.seed.coerceAtLeast(Sampling.RANDOM_SEED),
        ),
        maxReply = maxReply.coerceAtLeast(0),
        instructions = instructions.take(MAX_INSTRUCTIONS),
    )

    companion object {
        const val MAX_TOP_K = 200
        const val MAX_REPEAT_PENALTY = 2f
        const val MAX_INSTRUCTIONS = 2000

        /** The reply lengths offered; 0 is until the context is full. */
        val MAX_REPLY_OPTIONS = listOf(0, 256, 512, 1024, 2048, 4096, 8192)

        /** [id]'s tuning; a Heretic build without its own uses its base model's. */
        fun of(id: String?, all: Map<String, ModelTuning>): ModelTuning {
            id ?: return ModelTuning()
            return all[id] ?: all.entries.firstOrNull { id.startsWith(it.key + Heretic.ID_SUFFIX) }?.value ?: ModelTuning()
        }

        /** Only the models that differ from the defaults are stored. */
        fun encode(all: Map<String, ModelTuning>): String = buildJsonObject {
            all.filterValues { it != ModelTuning() }.forEach { (id, t) ->
                put(
                    id,
                    buildJsonObject {
                        put("topP", t.sampling.topP)
                        put("topK", t.sampling.topK)
                        put("minP", t.sampling.minP)
                        put("repeatPenalty", t.sampling.repeatPenalty)
                        put("seed", t.sampling.seed)
                        put("maxReply", t.maxReply)
                        put("instructions", t.instructions)
                    },
                )
            }
        }.toString()

        /** Malformed JSON gives no tuning, and a missing field its default; this never throws. */
        fun decode(json: String?): Map<String, ModelTuning> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                val d = ModelTuning()
                (Json.parseToJsonElement(json) as? JsonObject).orEmpty().mapNotNull { (id, el) ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    fun p(key: String) = o[key] as? JsonPrimitive
                    id to ModelTuning(
                        Sampling(
                            topP = p("topP")?.floatOrNull ?: d.sampling.topP,
                            topK = p("topK")?.intOrNull ?: d.sampling.topK,
                            minP = p("minP")?.floatOrNull ?: d.sampling.minP,
                            repeatPenalty = p("repeatPenalty")?.floatOrNull ?: d.sampling.repeatPenalty,
                            seed = p("seed")?.intOrNull ?: d.sampling.seed,
                        ),
                        maxReply = p("maxReply")?.intOrNull ?: d.maxReply,
                        instructions = p("instructions")?.takeIf { it.isString }?.contentOrNull ?: d.instructions,
                    ).clamped()
                }.toMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
