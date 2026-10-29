package com.vanskis.liquidpocket.chat

/** Where [ChatController] sends what a reply produces besides its text: the app plays or speaks it. */
interface ReplySink {
    /** Speech samples at the model's audio-out rate, as they arrive. Called on the main thread; must not block. */
    fun audio(samples: FloatArray) {}

    /** The reply ended with [text]; [completed] is false when it was stopped or failed. */
    fun finished(text: String, completed: Boolean) {}

    companion object {
        val NONE: ReplySink = object : ReplySink {}
    }
}
