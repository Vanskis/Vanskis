package com.vanskis.liquidpocket.data

import com.vanskis.liquidpocket.chat.Attachment
import com.vanskis.liquidpocket.chat.AttachmentKind
import com.vanskis.liquidpocket.engine.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
