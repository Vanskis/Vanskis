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
)

object ModelCatalog {
    val LFM3_OMNI_2B = ModelSpec(
        id = "lfm3-omni-2b",
        displayName = "LFM3-Omni-2B",
        repo = "LiquidAI/LFM3-Omni-2B-GGUF",
        quant = "Q4_K_M",
        approxSizeBytes = 1_300_000_000L,
        offered = true,
    )

    /** The "smart" model, used from M4. */
    val LFM25_8B_A1B = ModelSpec(
        id = "lfm2.5-8b-a1b",
        displayName = "LFM2.5-8B-A1B",
        repo = "LiquidAI/LFM2.5-8B-A1B-GGUF",
        quant = "Q4_K_M",
        approxSizeBytes = 5_000_000_000L,
        offered = false,
    )

    val all: List<ModelSpec> = listOf(LFM3_OMNI_2B, LFM25_8B_A1B)

    val offered: List<ModelSpec> get() = all.filter { it.offered }

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }
}
