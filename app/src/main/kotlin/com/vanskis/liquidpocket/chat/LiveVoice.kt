package com.vanskis.liquidpocket.chat

import kotlin.math.log10
import kotlin.math.sqrt

/** Which model a live voice conversation talks to. */
enum class LiveVoiceModel {
    /** LFM3-Omni hears each turn and answers in its own voice. */
    OMNI,

    /** Android's recognizer types each turn for LFM3-1.7B (thinking, tools, web search); Android reads the reply. */
    TEXT,
    ;

    companion object {
        /** The user's [chosen] model (none chosen means Omni); Omni only when it is installed. */
        fun resolve(chosen: LiveVoiceModel?, omniInstalled: Boolean): LiveVoiceModel =
            if (omniInstalled && chosen != TEXT) OMNI else TEXT
    }
}

enum class VadEvent { SPEECH_START, SPEECH_END }

/**
 * An energy voice-activity detector over mono PCM16 frames. Speech starts once loud frames add up to
 * [minSpeechMillis] (quiet gaps shorter than [onsetGapMillis] do not reset the count, so a word's
 * syllables count but a click does not); it ends after [endSilenceMillis] of quiet. Loud means a
 * frame's RMS (0..1 of full scale) is at least [threshold], which can change between frames.
 */
class EnergyVad(
    val sampleRate: Int = VoiceRoute.RECORD_SAMPLE_RATE,
    var threshold: Float = LISTEN_THRESHOLD,
    val minSpeechMillis: Int = 250,
    val endSilenceMillis: Int = 800,
    val onsetGapMillis: Int = 120,
) {
    /** The last frame's RMS, 0..1. */
    var level = 0f
        private set

    /** Between a [VadEvent.SPEECH_START] and its [VadEvent.SPEECH_END]. */
    var speaking = false
        private set

    private var voiced = 0L
    private var quiet = 0L

    /** Trailing quiet samples so far (in speech, since the last loud frame). */
    val quietSamples: Long get() = quiet

    fun process(frame: ShortArray): VadEvent? {
        level = rms(frame)
        val loud = level >= threshold
        if (!speaking) {
            if (loud) {
                voiced += frame.size
                quiet = 0
                if (voiced >= samples(minSpeechMillis)) {
                    speaking = true
                    voiced = 0
                    return VadEvent.SPEECH_START
                }
            } else if (voiced > 0) {
                quiet += frame.size
                if (quiet >= samples(onsetGapMillis)) reset()
            }
            return null
        }
        if (loud) {
            quiet = 0
            return null
        }
        quiet += frame.size
        if (quiet < samples(endSilenceMillis)) return null
        speaking = false
        voiced = 0
        return VadEvent.SPEECH_END
    }

    /** Forgets any speech in progress. */
    fun reset() {
        speaking = false
        voiced = 0
        quiet = 0
    }

    private fun samples(millis: Int): Long = millis.toLong() * sampleRate / 1000

    companion object {
        /** About -36 dBFS: speech near the phone, above a quiet room. */
        const val LISTEN_THRESHOLD = 0.016f

        /** Higher while the reply plays, so its echo is less likely to count as the user talking over it. */
        const val BARGE_IN_THRESHOLD = 0.05f

        fun rms(frame: ShortArray): Float {
            if (frame.isEmpty()) return 0f
            var sum = 0.0
            for (s in frame) {
                val v = s / 32768.0
                sum += v * v
            }
            return sqrt(sum / frame.size).toFloat()
        }
    }
}

/**
 * Runs the [vad] over the mic's frames and keeps the audio of each turn: from a little before speech
 * was detected ([preRollMillis], so the first syllable is not cut) to [tailMillis] after it ended. A
 * turn longer than [maxTurnMillis] ends there.
 */
class LiveCapture(
    val vad: EnergyVad = EnergyVad(),
    val preRollMillis: Int = 500,
    val tailMillis: Int = 200,
    val maxTurnMillis: Int = 30_000,
) {
    private val preRoll = ArrayDeque<ShortArray>()
    private var preRollSamples = 0
    private val turn = ArrayList<ShortArray>()
    private var turnSamples = 0
    private var finished: FloatArray? = null

    val level: Float get() = vad.level

    /** Feeds one frame; returns the VAD's event for it, if any. A finished turn waits in [takeTurn]. */
    fun feed(frame: ShortArray): VadEvent? {
        val event = vad.process(frame)
        when {
            event == VadEvent.SPEECH_START -> {
                turn.clear()
                turn.addAll(preRoll)
                turnSamples = preRollSamples
                clearPreRoll()
                append(frame)
            }
            vad.speaking -> {
                append(frame)
                if (turnSamples.toLong() * 1000 >= maxTurnMillis.toLong() * vad.sampleRate) {
                    vad.reset()
                    finish(trailingQuiet = 0)
                    return VadEvent.SPEECH_END
                }
            }
            event == VadEvent.SPEECH_END -> {
                append(frame)
                finish(trailingQuiet = vad.quietSamples)
                vad.reset()
            }
            else -> keepPreRoll(frame)
        }
        return event
    }

    /** The last finished turn as float samples at the VAD's rate, once; empty when there is none. */
    fun takeTurn(): FloatArray = (finished ?: FloatArray(0)).also { finished = null }

    /** Drops the turn in progress and the finished one. */
    fun reset() {
        vad.reset()
        turn.clear()
        turnSamples = 0
        clearPreRoll()
        finished = null
    }

    private fun append(frame: ShortArray) {
        turn += frame
        turnSamples += frame.size
    }

    private fun finish(trailingQuiet: Long) {
        val keepTail = tailMillis.toLong() * vad.sampleRate / 1000
        val drop = (trailingQuiet - keepTail).coerceIn(0, turnSamples.toLong()).toInt()
        val length = turnSamples - drop
        val out = FloatArray(length)
        var i = 0
        for (f in turn) {
            for (s in f) {
                if (i == length) break
                out[i++] = s / 32768f
            }
        }
        finished = out
        turn.clear()
        turnSamples = 0
    }

    private fun keepPreRoll(frame: ShortArray) {
        preRoll.addLast(frame)
        preRollSamples += frame.size
        val max = preRollMillis.toLong() * vad.sampleRate / 1000
        while (preRoll.size > 1 && preRollSamples - preRoll.first().size >= max) {
            preRollSamples -= preRoll.removeFirst().size
        }
    }

    private fun clearPreRoll() {
        preRoll.clear()
        preRollSamples = 0
    }
}

/** Where a live conversation is: hearing the user, waiting for the reply, or playing it. */
enum class LivePhase { LISTENING, CAPTURING, WAITING, SPEAKING }

sealed interface LiveEvent {
    /** The user started talking. */
    data object SpeechStart : LiveEvent

    /** The user's turn ended. */
    data object SpeechEnd : LiveEvent

    /** The turn had nothing to send, or could not be sent. */
    data object TurnDropped : LiveEvent

    /** The turn was sent and its reply is being generated. */
    data object Sent : LiveEvent

    /** The model's own voice started playing while it still generates. */
    data object ReplyAudio : LiveEvent

    /** Generation ended; [speaking] when the reply is (still) being played or read aloud. */
    data class ReplyEnded(val speaking: Boolean) : LiveEvent

    /** Nothing is playing any more. */
    data object PlaybackDone : LiveEvent
}

enum class LiveAction {
    /** Keep what the user says from now as the turn (for Android's recognizer: start it). */
    START_CAPTURE,

    /** Send the captured turn into the chat. */
    SEND_TURN,

    /** Silence the model's voice and Android's text-to-speech. */
    STOP_PLAYBACK,

    /** Stop the reply being generated. */
    STOP_GENERATION,

    /** Listen for the next turn. */
    LISTEN,
}

/**
 * The live conversation's turn-taking: LISTENING → CAPTURING → WAITING → SPEAKING → LISTENING. Talking
 * while the reply is generated or spoken (barge-in) stops both and captures the new turn. Events that
 * belong to an abandoned reply are ignored.
 */
class LiveTurns {
    var phase = LivePhase.LISTENING
        private set

    /** A sent turn's reply is being generated. */
    var generating = false
        private set

    fun on(event: LiveEvent): List<LiveAction> = when (event) {
        LiveEvent.SpeechStart -> when (phase) {
            LivePhase.LISTENING -> to(LivePhase.CAPTURING, LiveAction.START_CAPTURE)
            LivePhase.CAPTURING -> emptyList()
            LivePhase.WAITING, LivePhase.SPEAKING -> {
                val stop = if (generating) listOf(LiveAction.STOP_GENERATION) else emptyList()
                generating = false
                phase = LivePhase.CAPTURING
                listOf(LiveAction.STOP_PLAYBACK) + stop + LiveAction.START_CAPTURE
            }
        }
        LiveEvent.SpeechEnd ->
            if (phase == LivePhase.CAPTURING) to(LivePhase.WAITING, LiveAction.SEND_TURN) else emptyList()
        LiveEvent.TurnDropped ->
            if ((phase == LivePhase.CAPTURING || phase == LivePhase.WAITING) && !generating) {
                to(LivePhase.LISTENING, LiveAction.LISTEN)
            } else {
                emptyList()
            }
        LiveEvent.Sent -> {
            if (phase == LivePhase.WAITING) generating = true
            emptyList()
        }
        LiveEvent.ReplyAudio ->
            if (phase == LivePhase.WAITING && generating) to(LivePhase.SPEAKING) else emptyList()
        is LiveEvent.ReplyEnded -> when {
            !generating -> emptyList()
            else -> {
                generating = false
                if (event.speaking) to(LivePhase.SPEAKING) else to(LivePhase.LISTENING, LiveAction.LISTEN)
            }
        }
        LiveEvent.PlaybackDone ->
            if (phase == LivePhase.SPEAKING && !generating) to(LivePhase.LISTENING, LiveAction.LISTEN) else emptyList()
    }

    private fun to(next: LivePhase, vararg actions: LiveAction): List<LiveAction> {
        phase = next
        return actions.toList()
    }
}

/**
 * What the user said in live voice that has not reached the chat yet. Each turn is [take]n with what
 * is still here, and stays until it is [clear]ed (sent, or dropped for good), so a turn whose send
 * new speech cut short goes in front of the next turn rather than being lost.
 */
class LiveUnsent {
    private var text = ""
    private var clip = FloatArray(0)

    /** Nothing is waiting. */
    val isEmpty: Boolean get() = text.isEmpty() && clip.isEmpty()

    /** Adds the turn just heard ([text] from the recognizer, or the [clip] itself) and returns all that is unsent. */
    fun take(text: String, clip: FloatArray): Pair<String, FloatArray> {
        this.text = listOf(this.text, text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        this.clip += clip
        return this.text to this.clip
    }

    fun clear() {
        text = ""
        clip = FloatArray(0)
    }
}

/** What the live voice screen shows: the phase, the mic level (0..1) for the orb, and the model. */
data class LiveUi(val phase: LivePhase, val level: Float, val model: LiveVoiceModel)

object LiveLevel {
    /** An RMS (0..1 of full scale) as 0..1 for the orb: -50 dBFS and below is 0, -10 dBFS and above is 1. */
    fun fromRms(rms: Float): Float {
        if (rms <= 0f) return 0f
        val db = 20 * log10(rms)
        return ((db + 50f) / 40f).coerceIn(0f, 1f)
    }
}
