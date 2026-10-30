package com.vanskis.liquidpocket.ui

import com.vanskis.liquidpocket.core.ModelSpec
import java.util.Locale

/**
 * A rough figure for the memory a model takes at a given context size, shown in Settings:
 * the model file (mapped into memory whole) plus the KV cache, which grows with the context.
 *
 * The per-token KV-cache sizes are estimates, not measurements. LFM models are hybrid: most layers
 * are short convolutions with a fixed-size state, and only a few are attention layers that keep keys
 * and values per token, so the cost per token is far below a same-size pure transformer's.
 * Compute buffers and the app itself come on top, so the real figure is somewhat higher.
 */
object MemoryEstimate {
    /** Estimated KV-cache bytes per context token for LFM3-1.7B. */
    const val LFM3_1_7B_KV_BYTES_PER_TOKEN: Long = 40L * 1024

    /** Estimated KV-cache bytes per context token for LFM3-Omni-2B. */
    const val LFM3_OMNI_2B_KV_BYTES_PER_TOKEN: Long = 50L * 1024

    /** Models without their own figure take the larger (more cautious) one. */
    fun kvBytesPerToken(model: ModelSpec): Long = when {
        model.displayName.startsWith("LFM3-1.7B") -> LFM3_1_7B_KV_BYTES_PER_TOKEN
        else -> LFM3_OMNI_2B_KV_BYTES_PER_TOKEN
    }

    /** Model file size plus [kvBytesPerToken] for each of [contextTokens]. */
    fun bytes(modelFileBytes: Long, kvBytesPerToken: Long, contextTokens: Int): Long =
        modelFileBytes + kvBytesPerToken * contextTokens.coerceAtLeast(0)

    /** [bytes] for [model], from its approximate download size. */
    fun bytes(model: ModelSpec, contextTokens: Int): Long =
        bytes(model.approxSizeBytes, kvBytesPerToken(model), contextTokens)

    /** "≈ 2.1 GB with LFM3-1.7B" (decimal GB, as download sizes are given). */
    fun label(model: ModelSpec, contextTokens: Int): String =
        String.format(Locale.ROOT, "≈ %.1f GB with %s", bytes(model, contextTokens) / 1e9, model.displayName)
}
