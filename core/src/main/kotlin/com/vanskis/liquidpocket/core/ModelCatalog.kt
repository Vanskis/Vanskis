package com.vanskis.liquidpocket.core

/** A model the app knows how to fetch: a Hugging Face GGUF repo plus the quantization to pick. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val repo: String,
    val quant: String,
    /** Shown before the exact size is known from the repo listing. */
    val approxSizeBytes: Long,
    /** Only offered models appear in the UI; others are catalogued for later milestones. */
    val offered: Boolean,
    /**
     * Null: no multimodal projector. Otherwise quant tags to look for in `mmproj` filenames, in
     * order of preference (case-insensitive); the smallest mmproj is used if none match.
     */
    val mmprojQuants: List<String>? = null,
    /** Tried in order when no model file matches [quant]. */
    val fallbackQuants: List<String> = emptyList(),
)

object ModelCatalog {
    /** The main model: text, thinking and tools. */
    val LFM3_1_7B = ModelSpec(
        id = "lfm3-1.7b",
        displayName = "LFM3-1.7B",
        repo = "LiquidAI/LFM3-1.7B-GGUF",
        quant = "Q4_0",
        approxSizeBytes = 1_050_000_000L,
        offered = true,
    )

    /** Loaded on demand for photo and voice turns. */
    val LFM3_OMNI_2B = ModelSpec(
        id = "lfm3-omni-2b",
        displayName = "LFM3-Omni-2B",
        repo = "LiquidAI/LFM3-Omni-2B-GGUF",
        quant = "Q4_K_M",
        approxSizeBytes = 1_300_000_000L,
        offered = true,
        mmprojQuants = listOf("Q8_0", "F16", "BF16"),
    )

    val all: List<ModelSpec> = listOf(LFM3_1_7B, LFM3_OMNI_2B)

    val offered: List<ModelSpec> get() = all.filter { it.offered }

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }
}
