package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativePromptTest {
    private val image = ImageInput(2, 1, ByteArray(6) { it.toByte() })
    private val clip = AudioInput(floatArrayOf(0.1f, -0.2f, 0.3f))

    private val conversation = listOf(
        ChatMessage(Role.SYSTEM, "sys"),
        ChatMessage(Role.USER, "what is this?", images = listOf(image), audio = listOf(clip)),
        ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCall("look", "{\"x\":1}"), ToolCall("ask", "{}"))),
        ChatMessage(Role.TOOL, "a cat", toolName = "look"),
        ChatMessage(Role.USER, "and this?", images = listOf(image)),
    )
    private val tools = listOf(ToolSpec("look", "Looks", "{\"type\":\"object\"}"))

    private fun strings(a: Array<ByteArray>) = a.map { String(it, Charsets.UTF_8) }

    @Test
    fun flattensMessagesToolCallsAndTools() {
        val p = NativePrompt.of(conversation, tools)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 1), p.roles)
        assertEquals(listOf("sys", "what is this?", "", "a cat", "and this?"), strings(p.contents))
        assertEquals(listOf("", "", "", "look", ""), strings(p.toolNames))
        assertEquals(5, p.toolCalls.size)
        assertEquals(listOf("look", "{\"x\":1}", "ask", "{}"), strings(p.toolCalls[2]))
        assertTrue(p.toolCalls.withIndex().all { (i, c) -> i == 2 || c.isEmpty() })
        assertEquals(listOf("look", "Looks", "{\"type\":\"object\"}"), strings(p.tools))
    }

    @Test
    fun mediaItemsListImagesBeforeAudioPerMessage() {
        val p = NativePrompt.of(conversation, tools)
        assertArrayEquals(intArrayOf(1, 1, 4), p.mediaMsg)
        assertArrayEquals(intArrayOf(LlamaNative.MEDIA_IMAGE, LlamaNative.MEDIA_AUDIO, LlamaNative.MEDIA_IMAGE), p.mediaKind)
        assertArrayEquals(intArrayOf(2, 1, 3, 0, 2, 1), p.mediaDims)
        assertSame(image.rgb, p.mediaData[0])
        assertSame(clip.samples, p.mediaData[1])
        assertSame(image.rgb, p.mediaData[2])
    }

    @Test
    fun placeholdersKeepMediaShapeWithoutData() {
        val p = NativePrompt.of(conversation, tools, placeholders = true)
        assertArrayEquals(intArrayOf(2, 1, 3, 0, 2, 1), p.mediaDims)
        assertEquals(3, p.mediaData.size)
        p.mediaData.forEach { assertNull(it) }
    }

    @Test
    fun utf8Content() {
        val p = NativePrompt.of(listOf(ChatMessage(Role.USER, "héllo ✓")), emptyList())
        assertArrayEquals("héllo ✓".toByteArray(Charsets.UTF_8), p.contents[0])
        assertEquals(0, p.tools.size)
        assertEquals(0, p.mediaKind.size)
    }

    private fun expectFailure(messages: List<ChatMessage>, caps: Capabilities, fragment: String) {
        try {
            NativePrompt.checkMedia(messages, caps)
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message, e.message!!.contains(fragment))
        }
    }

    @Test
    fun checkMediaRequiresCapabilityAndUserTurn() {
        val all = Capabilities(vision = true, audioIn = true, audioOut = false, audioInSampleRate = 16000, audioOutSampleRate = 0)
        NativePrompt.checkMedia(conversation, all)
        NativePrompt.checkMedia(listOf(ChatMessage(Role.USER, "text only")), Capabilities.NONE)
        expectFailure(conversation, all.copy(vision = false), "images")
        expectFailure(conversation, all.copy(audioIn = false), "audio")
        expectFailure(listOf(ChatMessage(Role.ASSISTANT, "", images = listOf(image))), all, "user messages")
        expectFailure(listOf(ChatMessage(Role.TOOL, "", audio = listOf(clip))), all, "user messages")
    }
}
