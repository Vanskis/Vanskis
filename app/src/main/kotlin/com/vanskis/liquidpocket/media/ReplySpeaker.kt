package com.vanskis.liquidpocket.media

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Reads finished replies aloud with Android's text-to-speech, for models that cannot speak or when
 * the user chose Android. The engine starts on first use; text spoken before it is ready waits.
 */
class ReplySpeaker(context: Context) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var utterance = 0
    /** The id of the last piece of the text being spoken; its end (done, error, stop) ends [busy]. */
    @Volatile private var lastPiece: String? = null
    @Volatile private var speaking = false

    /** Whether text is waiting to be spoken or being spoken; false once its last piece ends or [stop] ran. */
    val busy: Boolean get() = speaking

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = ended(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = ended(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = ended(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId)

        private fun ended(utteranceId: String?) {
            if (utteranceId != null && utteranceId == lastPiece) speaking = false
        }
    }

    /** Speaks [markdown] as plain text, replacing whatever is being spoken. Main thread. */
    fun speak(markdown: String) {
        val text = SpeechText.from(markdown)
        if (text.isBlank()) return
        speaking = true
        if (tts == null) {
            tts = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                val queued = pending
                pending = null
                // A failed start is retried on the next reply.
                if (!ready) release() else if (queued != null) say(queued)
            }.also { it.setOnUtteranceProgressListener(progress) }
        }
        if (ready) say(text) else pending = text
    }

    fun stop() {
        pending = null
        speaking = false
        if (ready) tts?.stop()
    }

    fun release() {
        pending = null
        speaking = false
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun say(text: String) {
        val engine = tts ?: return
        // Long replies go in pieces under the engine's input limit, cut at paragraph or sentence ends.
        val pieces = split(text, TextToSpeech.getMaxSpeechInputLength() - 1)
        val ids = pieces.map { "reply-${utterance++}" }
        lastPiece = ids.lastOrNull()
        if (ids.isEmpty()) speaking = false
        pieces.forEachIndexed { i, piece ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(piece, mode, null, ids[i]) != TextToSpeech.SUCCESS && i == pieces.lastIndex) speaking = false
        }
    }

    private fun split(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = listOf(window.lastIndexOf("\n"), window.lastIndexOf(". "), window.lastIndexOf(' '))
                .firstOrNull { it > max / 2 } ?: max
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out
    }
}
