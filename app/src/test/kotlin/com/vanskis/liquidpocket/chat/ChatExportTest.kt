package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportTest {
    @Test fun `turns become titled You and Assistant sections in order`() {
        val md = ChatExport.toMarkdown(
            "Trip",
            listOf(Turn(Role.USER, "Hi"), Turn(Role.ASSISTANT, " Hello there ")),
        )
        assertEquals("# Trip\n\n**You:**\n\nHi\n\n**Assistant:**\n\nHello there\n", md)
    }

    @Test fun `tool turn is one italic line and system and app-written turns are skipped`() {
        val md = ChatExport.toMarkdown(
            "T",
            listOf(
                Turn(Role.SYSTEM, "secret prompt"),
                Turn(Role.USER, "photo of a cat", toolName = "take_photo"),
                Turn(Role.TOOL, "{\"ok\":true}", toolName = "get_time"),
            ),
        )
        assertEquals("# T\n\n_Used tool: get_time_\n", md)
    }

    @Test fun `attachments become photo and voice message markers`() {
        val md = ChatExport.toMarkdown(
            "T",
            listOf(
                Turn(Role.USER, "look", attachments = listOf(Attachment("/a.jpg", AttachmentKind.IMAGE))),
                Turn(Role.USER, "", attachments = listOf(Attachment("/b.wav", AttachmentKind.AUDIO))),
            ),
        )
        assertEquals("# T\n\n**You:**\n\n[photo]\n\nlook\n\n**You:**\n\n[voice message]\n", md)
    }

    @Test fun `a blank assistant turn is omitted`() {
        val md = ChatExport.toMarkdown("T", listOf(Turn(Role.ASSISTANT, "  ")))
        assertEquals("# T\n", md)
        assertFalse(md.contains("Assistant"))
    }

    @Test fun `like pattern escapes percent underscore and backslash`() {
        assertEquals("%100\\% sure%", ChatSearch.likePattern(" 100% sure "))
        assertEquals("%a\\_b%", ChatSearch.likePattern("a_b"))
        assertEquals("%c:\\\\x%", ChatSearch.likePattern("c:\\x"))
    }

    @Test fun `snippet surrounds the first match ignoring case`() {
        val text = "a".repeat(100) + " Needle " + "b".repeat(100)
        val s = ChatSearch.snippet(text, "needle")
        assertTrue(s.startsWith("…") && s.endsWith("…") && s.contains("Needle"))
        assertEquals("short Needle", ChatSearch.snippet("short\n Needle", "needle"))
    }
}
