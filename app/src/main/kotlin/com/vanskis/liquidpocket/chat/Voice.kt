package com.vanskis.liquidpocket.chat

/** Who listens and speaks: the model itself, or Android's speech services. */
enum class VoiceEngine { MODEL, ANDROID }

/**
 * Which voice path a turn takes, from the user's settings and the installed models. Voice turns go
 * to the media model, which may not be the one loaded when the choice is made, so the loaded model's
 * capabilities do not decide it: [ChatController] drops speech for a model that cannot speak.
 */
object VoiceRoute {
    /** The rate the mic records voice messages at for the model; [MediaLoader] resamples if the model wants another. */
    const val RECORD_SAMPLE_RATE = 16_000

    /** Ask the model to speak its reply ([com.vanskis.liquidpocket.engine.GenerationParams.speak]). */
    fun modelSpeaks(speakReplies: Boolean, engine: VoiceEngine): Boolean =
        speakReplies && engine == VoiceEngine.MODEL

    /** Android's text-to-speech reads the finished reply when the model did not speak it ([ReplySink.finished]'s spoke). */
    fun androidSpeaks(speakReplies: Boolean, spoke: Boolean): Boolean = speakReplies && !spoke

    /** The mic records a clip for the model when a media model is installed; otherwise Android transcribes into the text field. */
    fun modelHears(engine: VoiceEngine, mediaInstalled: Boolean): Boolean =
        engine == VoiceEngine.MODEL && mediaInstalled
}
