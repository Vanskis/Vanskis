package com.vanskis.liquidpocket.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Android speech-to-text into the message field, for models that cannot hear or when the user chose
 * Android. Prefers the on-device recognizer, so speech stays on the phone where it can. Main thread.
 */
class Dictation(context: Context) {
    private val app = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean
        get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(app) || SpeechRecognizer.isRecognitionAvailable(app)

    /** Listens until the user pauses; [onText] gets the best transcript, [onDone] always runs once at the end. */
    fun start(onText: (String) -> Unit, onError: (String) -> Unit, onDone: () -> Unit) {
        cancel()
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(onText)
                finish()
            }

            override fun onError(error: Int) {
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice input."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "Speech recognition is not available for your language on this phone."
                        else -> "Speech recognition failed (error $error)."
                    },
                )
                finish()
            }

            private fun finish() {
                if (recognizer === r) {
                    r.destroy()
                    recognizer = null
                }
                onDone()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false),
        )
    }

    /** Stops listening and transcribes what was heard so far. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Drops the session without a result. */
    fun cancel() {
        recognizer?.destroy()
        recognizer = null
    }
}
