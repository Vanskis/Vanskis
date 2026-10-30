package com.vanskis.liquidpocket.access

import com.vanskis.liquidpocket.access.LaunchRequest.Companion.ACTION_SEND
import com.vanskis.liquidpocket.access.LaunchRequest.Companion.ACTION_SEND_MULTIPLE
import com.vanskis.liquidpocket.access.LaunchRequest.NewChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchRequestTest {
    private fun stream(uri: String, mime: String? = null) = SharedStream(uri, mime)

    @Test fun `shared text or link prefills a new chat without attachments`() {
        val request = LaunchRequest.from(IncomingIntent<String>(ACTION_SEND, type = "text/plain", text = "  https://example.com/a \n"))
        assertEquals(NewChat<String>(prefill = "https://example.com/a"), request)
    }

    @Test fun `several shared photos all become pending images, typed by their own mime or the intent's`() {
        val request = LaunchRequest.from(
            IncomingIntent(
                ACTION_SEND_MULTIPLE,
                type = "image/*",
                streams = listOf(stream("content://a", "image/jpeg"), stream("content://b"), stream("content://c", "image/png")),
            ),
        )
        assertEquals(NewChat(images = listOf("content://a", "content://b", "content://c")), request)
    }

    @Test fun `a shared pdf becomes a document, and a mixed share splits photos from pdfs`() {
        assertEquals(
            NewChat(documents = listOf("content://doc")),
            LaunchRequest.from(IncomingIntent(ACTION_SEND, type = "application/pdf", streams = listOf(stream("content://doc")))),
        )
        assertEquals(
            NewChat(images = listOf("content://p"), documents = listOf("content://d"), prefill = "look"),
            LaunchRequest.from(
                IncomingIntent(
                    ACTION_SEND_MULTIPLE,
                    type = "*/*",
                    text = "look",
                    streams = listOf(stream("content://p", "IMAGE/HEIC"), stream("content://d", "application/pdf")),
                ),
            ),
        )
    }

    @Test fun `unknown mime types are ignored and a share with nothing usable asks for nothing`() {
        assertNull(
            LaunchRequest.from(
                IncomingIntent(ACTION_SEND, type = "video/mp4", streams = listOf(stream("content://v"), stream("content://z", "application/zip"))),
            ),
        )
        assertEquals(
            NewChat(images = listOf("content://i")),
            LaunchRequest.from(
                IncomingIntent(ACTION_SEND_MULTIPLE, type = "*/*", streams = listOf(stream("content://v", "video/mp4"), stream("content://i", "image/webp"))),
            ),
        )
        assertNull(LaunchRequest.from(IncomingIntent<String>(ACTION_SEND, type = "text/plain", text = "   ")))
    }

    @Test fun `the widget's voice and text buttons and the tile open a new chat`() {
        fun own(start: String?) = LaunchRequest.from(IncomingIntent<String>("android.intent.action.MAIN", start = start, fromOwnEntry = true))
        assertEquals(NewChat<String>(startVoice = true), own(LaunchRequest.START_VOICE))
        assertEquals(NewChat<String>(focusInput = true), own(LaunchRequest.START_TEXT))
        assertEquals(NewChat<String>(), own(LaunchRequest.START_NEW))
        assertNull(own("other"))
        assertNull(own(null))
    }

    @Test fun `the launcher and start extras from other apps ask for nothing`() {
        assertNull(LaunchRequest.from(IncomingIntent<String>("android.intent.action.MAIN")))
        assertNull(LaunchRequest.from(IncomingIntent<String>("android.intent.action.MAIN", start = LaunchRequest.START_VOICE)))
        assertNull(LaunchRequest.from(IncomingIntent<String>(null, start = LaunchRequest.START_TEXT)))
    }

    @Test fun `shared file names keep only a safe last segment`() {
        assertEquals("report 2026.pdf", SharedInbox.safeFileName("report 2026.pdf"))
        assertEquals("passwd", SharedInbox.safeFileName("../../etc/passwd"))
        assertEquals("evil.pdf", SharedInbox.safeFileName("..\\evil.pdf"))
        assertEquals("caf_.pdf", SharedInbox.safeFileName("café.pdf"))
        assertEquals("document.pdf", SharedInbox.safeFileName(null))
        assertEquals("document.pdf", SharedInbox.safeFileName(".."))
    }

    @Test fun `an intent relaunched from Recents asks for nothing`() {
        val share = IncomingIntent<String>(ACTION_SEND, type = "text/plain", text = "hello", fromHistory = true)
        assertNull(LaunchRequest.from(share))
        val voice = IncomingIntent<String>(
            action = "android.intent.action.MAIN",
            start = LaunchRequest.START_VOICE,
            fromOwnEntry = true,
            fromHistory = true,
        )
        assertNull(LaunchRequest.from(voice))
        assertEquals(NewChat<String>(startVoice = true), LaunchRequest.from(voice.copy(fromHistory = false)))
    }
}
