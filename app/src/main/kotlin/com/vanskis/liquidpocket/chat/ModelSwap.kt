package com.vanskis.liquidpocket.chat

/** A model file to load, with its multimodal projector (if any) and the context size to load it with. */
data class LoadTarget(
    val path: String,
    val mmprojPath: String?,
    val contextSize: Int,
    /** The model's display name, saved with each reply it writes ([Turn.model]); empty for none. */
    val name: String = "",
)

/** A model the composer's menu offers to answer with: its [ModelSpec] id and display name. */
data class ModelChoice(val id: String, val name: String, val thinks: Boolean)

/**
 * The models [ChatController] routes replies to; only one is in memory at a time. Null means the
 * model is not installed.
 */
interface ModelTargets {
    /** The main model (LFM3-1.7B): text, thinking and tools. */
    suspend fun text(): LoadTarget?

    /** The model loaded on demand for photo and voice turns (LFM3-Omni-2B). */
    suspend fun media(): LoadTarget?

    companion object {
        val NONE = object : ModelTargets {
            override suspend fun text(): LoadTarget? = null
            override suspend fun media(): LoadTarget? = null
        }
    }
}

/**
 * The model for a reply whose newest user turn has attachments ([hasMedia]) or not: the model made
 * for it, else the other one. Null when neither is installed, and whatever is loaded answers.
 */
suspend fun ModelTargets.route(hasMedia: Boolean): LoadTarget? =
    if (hasMedia) media() ?: text() else text() ?: media()
