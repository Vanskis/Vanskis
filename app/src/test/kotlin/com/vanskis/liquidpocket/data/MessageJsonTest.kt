package com.vanskis.liquidpocket.data

import com.vanskis.liquidpocket.chat.Attachment
import com.vanskis.liquidpocket.chat.AttachmentKind
import com.vanskis.liquidpocket.engine.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageJsonTest {
    @Test fun `attachments round-trip and none is stored as null`() {
        val list = listOf(
            Attachment("/data/files/attachments/a \"b\".jpg", AttachmentKind.IMAGE),
            Attachment("/data/files/attachments/note.wav", AttachmentKind.AUDIO),
        )
        assertEquals(list, MessageJson.decodeAttachments(MessageJson.encodeAttachments(list)))
        assertNull(MessageJson.encodeAttachments(emptyList()))
        assertEquals(emptyList<Attachment>(), MessageJson.decodeAttachments(null))
    }

    @Test fun `a document attachment round-trips with its name, and older rows decode without one`() {
        val doc = listOf(Attachment("0f8fad5b-d9cb-469f-a165-70867728950e", AttachmentKind.DOCUMENT, "report \"Q3\".pdf"))
        assertEquals(doc, MessageJson.decodeAttachments(MessageJson.encodeAttachments(doc)))
        assertEquals(
            listOf(Attachment("/a/p.jpg", AttachmentKind.IMAGE)),
            MessageJson.decodeAttachments("""[{"path": "/a/p.jpg", "kind": "IMAGE"}]"""),
        )
    }

    @Test fun `tool calls keep their arguments text as is`() {
        val calls = listOf(ToolCall("get_weather", """{"city": "Lyon", "days": 2}"""))
        assertEquals(calls, MessageJson.decodeToolCalls(MessageJson.encodeToolCalls(calls)))
        assertNull(MessageJson.encodeToolCalls(emptyList()))
    }

    @Test fun `extras round-trip`() {
        val both = MessageExtras("Let me \"think\"\nabout it", 4200L)
        assertEquals(both, MessageJson.decodeExtras(MessageJson.encodeExtras(both)))
        val onlyText = MessageExtras(thinking = "hm")
        assertEquals(onlyText, MessageJson.decodeExtras(MessageJson.encodeExtras(onlyText)))
    }

    @Test fun `the summary marker round-trips`() {
        val summary = MessageExtras(summary = true)
        assertTrue(!summary.isEmpty)
        val json = MessageJson.encodeExtras(summary)
        assertEquals("""{"summary":true}""", json)
        assertEquals(summary, MessageJson.decodeExtras(json))
        assertEquals(MessageExtras(), MessageJson.decodeExtras("""{"summary":"yes"}"""))
    }

    @Test fun `a photo caption round-trips`() {
        val captioned = MessageExtras(caption = "A tabby cat on a \"red\" sofa.")
        assertTrue(!captioned.isEmpty)
        assertEquals(captioned, MessageJson.decodeExtras(MessageJson.encodeExtras(captioned)))
        val all = MessageExtras("hm", 12L, summary = false, caption = "A cat.", thinkingTokens = 340)
        assertEquals(all, MessageJson.decodeExtras(MessageJson.encodeExtras(all)))
        assertEquals(MessageExtras(), MessageJson.decodeExtras("""{"caption": 3}"""))
    }

    @Test fun `no extras is stored as null`() {
        assertNull(MessageJson.encodeExtras(MessageExtras()))
        assertNull(MessageJson.encodeExtras(MessageExtras(thinking = "")))
        assertTrue(MessageJson.decodeExtras(null).isEmpty)
        assertTrue(MessageJson.decodeExtras("").isEmpty)
    }

    @Test fun `malformed or unknown extras decode to none`() {
        for (bad in listOf("{not json", "[1,2]", "\"x\"", """{"thinking": {"a": 1}}""", """{"other": 1}""", "null")) {
            assertTrue(bad, MessageJson.decodeExtras(bad).isEmpty)
        }
        assertEquals(MessageExtras("a", null), MessageJson.decodeExtras("""{"thinking":"a","thinkingMillis":"soon","new":1}"""))
    }
}
