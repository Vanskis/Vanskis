package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.ToolSpec

/** A model file to load, with its multimodal projector (if any) and the context size to load it with. */
data class LoadTarget(val path: String, val mmprojPath: String?, val contextSize: Int)

/**
 * The two models [ChatController] swaps between. The resident model answers by default; the smart
 * one (larger, slower, text only) is loaded in its place for "Think harder" or the [Escalate] tool,
 * then the resident is loaded back. Null means the model is not installed.
 */
interface ModelTargets {
    suspend fun resident(): LoadTarget?
    suspend fun smart(): LoadTarget?

    /** Starts fetching the smart model, which is downloaded the first time it is wanted. */
    fun downloadSmart()

    companion object {
        val NONE = object : ModelTargets {
            override suspend fun resident(): LoadTarget? = null
            override suspend fun smart(): LoadTarget? = null
            override fun downloadSmart() = Unit
        }
    }
}

/** Where a swap to the smart model is, for the chat screen to say so. */
enum class ModelSwap {
    /** Unloading the resident model and loading the smart one. */
    TO_SMART,

    /** The smart model is answering. */
    SMART,

    /** Loading the resident model back. */
    BACK,
}

/**
 * The tool the resident model calls to hand a question to the smart model. [ChatController] answers
 * it itself by swapping models: it is never run as a tool, and never offered to the smart model.
 */
object Escalate {
    const val NAME = "escalate"

    val SPEC = ToolSpec(
        NAME,
        "Hand this question to a larger, slower model when it needs deeper reasoning, math, or long writing.",
        """{"type":"object","properties":{"reason":{"type":"string","description":"Why the question needs the larger model."}},"required":["reason"]}""",
    )
}
