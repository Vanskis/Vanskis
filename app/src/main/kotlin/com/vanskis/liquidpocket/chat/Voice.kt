package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.Capabilities

/** Who listens and speaks: the loaded model itself, or Android's speech services. */
enum class VoiceEngine { MODEL, ANDROID }

/** Which voice path a turn takes, from the user's settings and what the loaded model can do. */
object VoiceRoute {
    /** The model speaks its reply ([com.vanskis.liquidpocket.engine.GenerationParams.speak]). */
    fun modelSpeaks(speakReplies: Boolean, engine: VoiceEngine, caps: Capabilities): Boolean =
        speakReplies && engine == VoiceEngine.MODEL && caps.audioOut && caps.audioOutSampleRate > 0

    /** Android's text-to-speech reads the finished reply instead. */
    fun androidSpeaks(speakReplies: Boolean, engine: VoiceEngine, caps: Capabilities): Boolean =
        speakReplies && !modelSpeaks(speakReplies, engine, caps)

    /** The mic records a clip for the model; otherwise Android transcribes into the text field. */
    fun modelHears(engine: VoiceEngine, caps: Capabilities): Boolean =
        engine == VoiceEngine.MODEL && caps.audioIn && caps.audioInSampleRate > 0
}
