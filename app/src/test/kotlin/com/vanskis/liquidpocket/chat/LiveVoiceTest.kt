package com.vanskis.liquidpocket.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceTest {
    private val rate = 16_000
    private val frameMs = 30
    private val frameSamples = rate * frameMs / 1000

    private fun loud() = ShortArray(frameSamples) { if (it % 2 == 0) 6000 else -6000 }
    private fun quiet() = ShortArray(frameSamples) { if (it % 2 == 0) 20 else -20 }
    private fun frames(ms: Int, frame: () -> ShortArray) = List(ms / frameMs) { frame() }

    /** Feeds [frames] and returns each event with the time (ms from the first frame's end) it came at. */
    private fun run(vad: EnergyVad, frames: List<ShortArray>): List<Pair<VadEvent, Int>> =
        frames.mapIndexedNotNull { i, f -> vad.process(f)?.let { it to (i + 1) * frameMs } }

    @Test fun `speech ends after 0_8 s of silence, not after 0_5 s`() {
        val vad = EnergyVad(rate)
        val events = run(vad, frames(600, ::loud) + frames(510, ::quiet) + frames(600, ::loud) + frames(1200, ::quiet))
        assertEquals(listOf(VadEvent.SPEECH_START, VadEvent.SPEECH_END), events.map { it.first })
        // The 0.5 s pause did not end the turn; it ended 0.8 s into the final silence.
        val speechEndsAt = 600 + 510 + 600
        assertEquals(speechEndsAt + 810, events[1].second)
    }

    @Test fun `a 150 ms blip is not speech`() {
        val vad = EnergyVad(rate)
        assertTrue(run(vad, frames(150, ::loud) + frames(1500, ::quiet)).isEmpty())
        assertFalse(vad.speaking)
    }

    @Test fun `speech starts once 250 ms of it is heard`() {
        val vad = EnergyVad(rate)
        val events = run(vad, frames(600, ::quiet) + frames(600, ::loud))
        assertEquals(listOf(VadEvent.SPEECH_START to 600 + 270), events)
    }

    @Test fun `two blips with a long gap between them do not add up to speech`() {
        val vad = EnergyVad(rate)
        assertTrue(run(vad, frames(150, ::loud) + frames(300, ::quiet) + frames(150, ::loud) + frames(300, ::quiet)).isEmpty())
    }

    @Test fun `a captured turn keeps the onset and drops most of the trailing silence`() {
        val capture = LiveCapture(EnergyVad(rate))
        val events = (frames(1000, ::quiet) + frames(900, ::loud) + frames(900, ::quiet)).mapNotNull(capture::feed)
        assertEquals(listOf(VadEvent.SPEECH_START, VadEvent.SPEECH_END), events)
        val turn = capture.takeTurn()
        val seconds = turn.size.toDouble() / rate
        // The 0.5 s pre-roll holds the first 0.24 s of speech and 0.27 s before it; then the rest of
        // the 0.9 s of speech and a 0.2 s tail of the silence.
        assertEquals(0.27 + 0.9 + 0.2, seconds, 0.04)
        assertEquals(6000 / 32768f, turn.maxOrNull()!!, 1e-4f)
        assertEquals(0, capture.takeTurn().size)
    }

    @Test fun `a turn that runs past the maximum ends there`() {
        val capture = LiveCapture(EnergyVad(rate), maxTurnMillis = 2_000)
        val events = frames(3000, ::loud).mapNotNull(capture::feed)
        assertEquals(VadEvent.SPEECH_END, events[1])
        assertEquals(2.0, capture.takeTurn().size.toDouble() / rate, 0.04)
    }

    @Test fun `a turn goes listen, capture, wait, speak and back to listening`() {
        val turns = LiveTurns()
        assertEquals(listOf(LiveAction.START_CAPTURE), turns.on(LiveEvent.SpeechStart))
        assertEquals(LivePhase.CAPTURING, turns.phase)
        assertEquals(listOf(LiveAction.SEND_TURN), turns.on(LiveEvent.SpeechEnd))
        assertEquals(LivePhase.WAITING, turns.phase)
        turns.on(LiveEvent.Sent)
        assertTrue(turns.generating)
        assertEquals(emptyList<LiveAction>(), turns.on(LiveEvent.ReplyEnded(speaking = true)))
        assertEquals(LivePhase.SPEAKING, turns.phase)
        assertEquals(listOf(LiveAction.LISTEN), turns.on(LiveEvent.PlaybackDone))
        assertEquals(LivePhase.LISTENING, turns.phase)
    }

    @Test fun `talking over the spoken reply stops playback and captures the new turn`() {
        val turns = LiveTurns()
        turns.on(LiveEvent.SpeechStart)
        turns.on(LiveEvent.SpeechEnd)
        turns.on(LiveEvent.Sent)
        turns.on(LiveEvent.ReplyEnded(speaking = true))
        assertEquals(LivePhase.SPEAKING, turns.phase)
        assertEquals(listOf(LiveAction.STOP_PLAYBACK, LiveAction.START_CAPTURE), turns.on(LiveEvent.SpeechStart))
        assertEquals(LivePhase.CAPTURING, turns.phase)
    }

    @Test fun `talking over a reply still being generated stops generation and playback`() {
        val turns = LiveTurns()
        turns.on(LiveEvent.SpeechStart)
        turns.on(LiveEvent.SpeechEnd)
        turns.on(LiveEvent.Sent)
        turns.on(LiveEvent.ReplyAudio)
        assertEquals(LivePhase.SPEAKING, turns.phase)
        assertEquals(
            listOf(LiveAction.STOP_PLAYBACK, LiveAction.STOP_GENERATION, LiveAction.START_CAPTURE),
            turns.on(LiveEvent.SpeechStart),
        )
        assertEquals(LivePhase.CAPTURING, turns.phase)
        assertFalse(turns.generating)
        // The stopped reply's end and the end of its playback belong to the abandoned turn.
        assertEquals(emptyList<LiveAction>(), turns.on(LiveEvent.ReplyEnded(speaking = false)))
        assertEquals(emptyList<LiveAction>(), turns.on(LiveEvent.PlaybackDone))
        assertEquals(LivePhase.CAPTURING, turns.phase)
    }

    @Test fun `the model's voice plays to the end of generation before listening again`() {
        val turns = LiveTurns()
        turns.on(LiveEvent.SpeechStart)
        turns.on(LiveEvent.SpeechEnd)
        turns.on(LiveEvent.Sent)
        turns.on(LiveEvent.ReplyAudio)
        // A gap in the stream is not the end of the reply.
        assertEquals(emptyList<LiveAction>(), turns.on(LiveEvent.PlaybackDone))
        assertEquals(LivePhase.SPEAKING, turns.phase)
        turns.on(LiveEvent.ReplyEnded(speaking = true))
        assertEquals(listOf(LiveAction.LISTEN), turns.on(LiveEvent.PlaybackDone))
    }

    @Test fun `a reply with nothing to say or a dropped turn goes back to listening`() {
        val turns = LiveTurns()
        turns.on(LiveEvent.SpeechStart)
        turns.on(LiveEvent.SpeechEnd)
        turns.on(LiveEvent.Sent)
        assertEquals(listOf(LiveAction.LISTEN), turns.on(LiveEvent.ReplyEnded(speaking = false)))
        assertEquals(LivePhase.LISTENING, turns.phase)
        turns.on(LiveEvent.SpeechStart)
        turns.on(LiveEvent.SpeechEnd)
        assertEquals(listOf(LiveAction.LISTEN), turns.on(LiveEvent.TurnDropped))
        assertEquals(LivePhase.LISTENING, turns.phase)
    }

    @Test fun `live voice uses Omni when it is installed unless 1_7B was chosen`() {
        assertEquals(LiveVoiceModel.OMNI, LiveVoiceModel.resolve(null, omniInstalled = true))
        assertEquals(LiveVoiceModel.TEXT, LiveVoiceModel.resolve(null, omniInstalled = false))
        assertEquals(LiveVoiceModel.TEXT, LiveVoiceModel.resolve(LiveVoiceModel.TEXT, omniInstalled = true))
        assertEquals(LiveVoiceModel.TEXT, LiveVoiceModel.resolve(LiveVoiceModel.OMNI, omniInstalled = false))
    }

    @Test fun `the orb level maps -50 to -10 dBFS onto 0 to 1`() {
        assertEquals(0f, LiveLevel.fromRms(0f), 0f)
        assertEquals(0f, LiveLevel.fromRms(0.001f), 1e-3f)
        assertEquals(0.25f, LiveLevel.fromRms(0.01f), 1e-3f)
        assertEquals(0.5f, LiveLevel.fromRms(0.0316228f), 1e-3f)
        assertEquals(1f, LiveLevel.fromRms(0.5f), 0f)
    }
}
