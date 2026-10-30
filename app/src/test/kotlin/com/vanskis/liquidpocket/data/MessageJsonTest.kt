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
