package com.vanskis.liquidpocket.engine

import java.util.Locale

/** Memory reading taken from Android ActivityManager and Debug. */
data class MemorySnapshot(
    val pssKb: Long,
    val nativeHeapBytes: Long,
    val availMemBytes: Long,
    val totalMemBytes: Long,
    val lowMemory: Boolean,
)

/** TTFT and prompt token counts for a prompt. */
data class TtftResult(
    val promptTokens: Int,
    val ttftMs: Long,
    val prefillTokensPerSecond: Double? = null,
)

/** Memory reading with an extra model loaded alongside the chat model. */
data class OmniMemoryResult(
    val installed: Boolean,
    val snapshot: MemorySnapshot? = null,
    val error: String? = null,
    val extraModelFreed: Boolean = true,
)

/** Save and load file timing for a prefilled chat state. */
data class StateMeasureResult(
    val tokens: Int,
    val fileSizeBytes: Long,
    val saveTimeMs: Long,
    val loadTimeMs: Long,
    val repreTimeMs: Long,
) {
    /** How many times faster loading state is than re-prefilling. */
    val speedupRatio: Double
        get() = if (loadTimeMs > 0) repreTimeMs.toDouble() / loadTimeMs else 0.0
}

/** Complete measurement result for Settings Performance. */
data class MeasureResult(
    val modelName: String,
    val accelerator: Accelerator,
    val modelLoadTimeMs: Long,
    val shortPrompt: TtftResult,
    val longPrompt: TtftResult,
    val chatMemory: MemorySnapshot,
    val omniMemory: OmniMemoryResult,
    val state8k: StateMeasureResult?,
    val state32k: StateMeasureResult?,
    val state32kNote: String? = null,
) {
    fun describe(): String {
        val lines = mutableListOf<String>()

        // 1. TTFT and load time
        val accName = if (accelerator == Accelerator.NPU) "NPU" else "CPU"
        lines += "1. Model & TTFT ($accName):"
        lines += "• Model load time: $modelLoadTimeMs ms"
        val shortSpeed = shortPrompt.prefillTokensPerSecond?.let { String.format(Locale.ROOT, ", %.1f tok/s", it) } ?: ""
        lines += "• Short prompt TTFT: ${shortPrompt.ttftMs} ms (${shortPrompt.promptTokens} tokens$shortSpeed)"
        val longSpeed = longPrompt.prefillTokensPerSecond?.let { String.format(Locale.ROOT, ", %.1f tok/s", it) } ?: ""
        lines += "• ~8K prompt TTFT: ${longPrompt.ttftMs} ms (${longPrompt.promptTokens} tokens$longSpeed)"
        lines += ""

        // 2. Memory
        lines += "2. Memory:"
        lines += "• Chat model loaded:"
        lines += "  PSS: ${formatKbToMb(chatMemory.pssKb)} · Native heap: ${formatBytesToMb(chatMemory.nativeHeapBytes)} · Avail RAM: ${formatBytesToMb(chatMemory.availMemBytes)}"
        if (chatMemory.lowMemory) {
            lines += "  Warning: phone signaled low memory"
        }
        lines += "• With Omni loaded alongside:"
        when {
            !omniMemory.installed -> lines += "  Omni: not installed"
            omniMemory.error != null -> lines += "  Omni load failed: ${omniMemory.error}"
            omniMemory.snapshot != null -> {
                val s = omniMemory.snapshot
                lines += "  PSS: ${formatKbToMb(s.pssKb)} · Native heap: ${formatBytesToMb(s.nativeHeapBytes)} · Avail RAM: ${formatBytesToMb(s.availMemBytes)}"
                if (s.lowMemory) {
                    lines += "  Warning: phone signaled low memory"
                }
            }
        }
        lines += "• Extra model freed: ${if (omniMemory.extraModelFreed) "yes" else "no"}"
        lines += ""

        // 3. Saved chat state
        lines += "3. Saved chat state:"
        if (state8k != null) {
            lines += "• 8K tokens (${state8k.tokens} tokens):"
            lines += "  State file: ${formatBytesToMb(state8k.fileSizeBytes)}"
            lines += "  Save: ${state8k.saveTimeMs} ms · Load: ${state8k.loadTimeMs} ms · Re-prefill: ${state8k.repreTimeMs} ms (${String.format(Locale.ROOT, "%.1f×", state8k.speedupRatio)} faster)"
        } else {
            lines += "• 8K tokens: not measured"
        }
        if (state32k != null) {
            lines += "• 32K tokens (${state32k.tokens} tokens):"
            lines += "  State file: ${formatBytesToMb(state32k.fileSizeBytes)}"
            lines += "  Save: ${state32k.saveTimeMs} ms · Load: ${state32k.loadTimeMs} ms · Re-prefill: ${state32k.repreTimeMs} ms (${String.format(Locale.ROOT, "%.1f×", state32k.speedupRatio)} faster)"
        } else if (state32kNote != null) {
            lines += "• 32K tokens: $state32kNote"
        } else {
            lines += "• 32K tokens: skipped"
        }

        return lines.joinToString("\n")
    }

    companion object {
        fun formatBytesToMb(bytes: Long): String =
            String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

        fun formatKbToMb(kb: Long): String =
            String.format(Locale.ROOT, "%.1f MB", kb / 1024.0)
    }
}
