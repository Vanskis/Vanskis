package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.Capabilities
import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineConfig
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.ImageInput
import com.vanskis.liquidpocket.engine.ReplyChunk
import com.vanskis.liquidpocket.engine.Role
import com.vanskis.liquidpocket.engine.ToolCall
import com.vanskis.liquidpocket.engine.ToolSpec
import com.vanskis.liquidpocket.tools.Choices
import com.vanskis.liquidpocket.tools.OfferChoicesTool
import com.vanskis.liquidpocket.tools.Tool
import com.vanskis.liquidpocket.tools.ToolCatalog
import com.vanskis.liquidpocket.tools.ToolResult
import com.vanskis.liquidpocket.tools.ToolResults
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolLoopTest {

    /** Each chat() answers with the next scripted reply; with the script used up it says "Done". */
    private class ScriptedEngine : Engine {
        override val state = MutableStateFlow<EngineState>(EngineState.Ready("/m.gguf", 8192))
        override val capabilities = MutableStateFlow(Capabilities.NONE)
        override val lastStats = MutableStateFlow<GenerationStats?>(null)
        val replies = ArrayDeque<Pair<String, List<ToolCall>>>()
        var always: Pair<String, List<ToolCall>>? = null
        val sent = mutableListOf<List<ChatMessage>>()
        val offered = mutableListOf<List<String>>()

        override suspend fun load(modelPath: String, config: EngineConfig) = Unit
        override suspend fun unload() = Unit
        override fun stop() = Unit

        override fun chat(messages: List<ChatMessage>, params: GenerationParams): Flow<ReplyChunk> = flow {
            sent += messages
            offered += params.tools.map { it.name }
            val (text, calls) = replies.removeFirstOrNull() ?: always ?: ("Done" to emptyList())
            if (text.isNotEmpty()) emit(ReplyChunk.Text(text))
            calls.forEach { emit(ReplyChunk.ToolCall(it)) }
        }
    }

    private class Store : ChatStore {
        val saved = mutableMapOf<Long, MutableList<Turn>>()
        private var nextId = 1L
        override suspend fun createConversation(title: String) = nextId++.also { saved[it] = mutableListOf() }
        override suspend fun turns(conversationId: Long) = saved.getValue(conversationId).toList()
        override suspend fun appendTurn(conversationId: Long, turn: Turn) {
            saved.getValue(conversationId) += turn
        }
        override suspend fun truncate(conversationId: Long, fromIndex: Int) {
            val list = saved.getValue(conversationId)
            while (list.size > fromIndex) list.removeAt(list.lastIndex)
        }
        override suspend fun deleteConversation(conversationId: Long) {
            saved.remove(conversationId)
        }
        override suspend fun factTexts() = emptyList<String>()
    }

    private class FakeTool(
        name: String,
        override val needsConfirmation: Boolean,
        private val result: ToolResult = ToolResult("""{"ok":true}"""),
        private val problem: String? = null,
    ) : Tool {
        override val spec = ToolSpec(name, "A test tool.", """{"type":"object"}""")
        val runs = mutableListOf<String>()
        override fun check(argumentsJson: String) = problem
        override fun describe(argumentsJson: String) = "Do ${spec.name} with $argumentsJson"
        override suspend fun run(argumentsJson: String): ToolResult {
            runs += argumentsJson
            return result
        }
    }

    private val engine = ScriptedEngine()
    private val store = Store()
    private val clock = FakeTool("get_time", needsConfirmation = false, result = ToolResult("""{"time":"10:00"}"""))
    private val note = FakeTool("create_note", needsConfirmation = true)
    private val params = GenerationParams(tools = listOf(clock.spec, note.spec))

    private fun TestScope.controller(vararg tools: Tool) =
        ChatController(engine, store, this, StandardTestDispatcher(testScheduler), tools.toList().ifEmpty { listOf(clock, note) })

    private fun call(name: String, args: String = "{}") = ToolCall(name, args)

    @Test fun `a read tool runs at once and its result goes back to the model as a tool turn`() = runTest {
        engine.replies += "" to listOf(call("get_time"))
        engine.replies += "It is 10:00." to emptyList()
        val c = controller()
        c.send("what time is it?", params)
        advanceUntilIdle()

        assertEquals(listOf("{}"), clock.runs)
        assertEquals(2, engine.sent.size)
        val second = engine.sent[1]
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL), second.map { it.role })
        assertTrue(second[0].content.contains(SystemPrompt.TOOLS_LINE))
        assertEquals(listOf(call("get_time")), second[2].toolCalls)
        assertEquals(ChatMessage(Role.TOOL, """{"time":"10:00"}""", toolName = "get_time"), second[3])

        val s = c.state.value
        assertFalse(s.generating)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), s.turns.map { it.role })
        assertEquals("It is 10:00.", s.turns.last().text)
        assertEquals(s.turns, store.saved.getValue(s.conversationId!!))
    }

    @Test fun `a write tool waits for the user's approval before it runs`() = runTest {
        engine.replies += "" to listOf(call("create_note", """{"text":"milk"}"""))
        val c = controller()
        c.send("note milk", params)
        advanceUntilIdle()

        val card = c.state.value.confirmation!!
        assertEquals("create_note", card.toolName)
        assertEquals("""Do create_note with {"text":"milk"}""", card.description)
        assertTrue(c.state.value.generating)
        assertEquals(emptyList<String>(), note.runs)
        assertEquals(1, engine.sent.size)
        c.send("another", params)
        assertEquals("nothing is sent while a confirmation is pending", 2, c.state.value.turns.size)

        c.approveTool(card.id)
        advanceUntilIdle()

        assertEquals(listOf("""{"text":"milk"}"""), note.runs)
        assertNull(c.state.value.confirmation)
        assertEquals(2, engine.sent.size)
        assertEquals("""{"ok":true}""", c.state.value.turns[2].text)
        assertEquals("Done", c.state.value.turns.last().text)
    }

    @Test fun `declining a write tells the model the user declined`() = runTest {
        engine.replies += "" to listOf(call("create_note", """{"text":"milk"}"""))
        val c = controller()
        c.send("note milk", params)
        advanceUntilIdle()
        c.declineTool(c.state.value.confirmation!!.id)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), note.runs)
        assertEquals(2, engine.sent.size)
        assertEquals(ChatMessage(Role.TOOL, ToolResults.DECLINED, toolName = "create_note"), engine.sent[1].last())
        assertTrue(ToolResults.isDeclined(c.state.value.turns[2].text))
    }

    @Test fun `an answer is taken only for the card it was given on`() = runTest {
        engine.replies += "" to listOf(call("create_note", """{"text":"milk"}"""), call("create_note", """{"text":"eggs"}"""))
        val c = controller()
        c.send("note milk and eggs", params)
        advanceUntilIdle()
        val first = c.state.value.confirmation!!

        c.approveTool(first.id)
        advanceUntilIdle()
        val second = c.state.value.confirmation!!
        assertEquals("""Do create_note with {"text":"eggs"}""", second.description)

        // A second tap on the first card, landing after the second card replaced it.
        c.approveTool(first.id)
        c.declineTool(first.id)
        advanceUntilIdle()

        assertEquals(listOf("""{"text":"milk"}"""), note.runs)
        assertEquals(second, c.state.value.confirmation)
        assertTrue(c.state.value.generating)

        c.approveTool(second.id)
        advanceUntilIdle()
        assertEquals(listOf("""{"text":"milk"}""", """{"text":"eggs"}"""), note.runs)
        assertNull(c.state.value.confirmation)
    }

    @Test fun `stop during a pending confirmation declines it and asks the model nothing more`() = runTest {
        engine.replies += "" to listOf(call("create_note"), call("get_time"))
        val c = controller()
        c.send("note it", params)
        advanceUntilIdle()
        c.stop()
        advanceUntilIdle()

        val s = c.state.value
        assertFalse(s.generating)
        assertNull(s.confirmation)
        assertEquals(1, engine.sent.size)
        assertEquals(emptyList<String>(), note.runs)
        assertEquals("a call after the stop does not run", emptyList<String>(), clock.runs)
        assertEquals(listOf(ToolResults.DECLINED, ToolResults.CANCELLED), s.turns.filter { it.role == Role.TOOL }.map { it.text })
        assertEquals(s.turns, store.saved.getValue(s.conversationId!!))

        c.send("thanks", params)
        advanceUntilIdle()
        assertEquals("Done", c.state.value.turns.last().text)
    }

    @Test fun `tool rounds stop at the cap`() = runTest {
        engine.always = "" to listOf(call("get_time"))
        val c = controller()
        c.send("loop", params)
        advanceUntilIdle()

        assertEquals(MAX_TOOL_ROUNDS, clock.runs.size)
        assertEquals(MAX_TOOL_ROUNDS + 1, engine.sent.size)
        val s = c.state.value
        assertFalse(s.generating)
        assertTrue(s.error!!.contains("$MAX_TOOL_ROUNDS rounds"))
        assertEquals(Role.TOOL, s.turns.last().role)
    }

    @Test fun `photos a tool returns follow its result on a user turn that is not the user's`() = runTest {
        val image = ImageInput(2, 1, ByteArray(6))
        val photos = FakeTool("look_at_photos", needsConfirmation = false, result = ToolResult("""{"shown":[7]}""", listOf(image)))
        engine.capabilities.value = Capabilities.NONE.copy(vision = true)
        engine.replies += "" to listOf(call("look_at_photos", """{"ids":[7]}"""))
        val c = controller(photos)
        c.send("what is in photo 7?", GenerationParams(tools = listOf(photos.spec)))
        advanceUntilIdle()

        val second = engine.sent[1]
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER), second.map { it.role })
        assertSame(image, second[4].images.single())
        assertNull(second[4].toolName)
        val turns = c.state.value.turns
        assertTrue(turns[3].fromTool)
        assertEquals("edit and regenerate act on the user's own turn", 0, c.state.value.lastUserIndex)
    }

    @Test fun `unknown tools and bad arguments are reported to the model without asking the user`() = runTest {
        val strict = FakeTool("create_event", needsConfirmation = true, problem = "start is missing")
        engine.replies += "" to listOf(call("nope"), call("create_event"))
        val c = controller(strict)
        c.send("go", GenerationParams(tools = listOf(strict.spec)))
        advanceUntilIdle()

        assertNull(c.state.value.confirmation)
        assertEquals(emptyList<String>(), strict.runs)
        val results = c.state.value.turns.filter { it.role == Role.TOOL }.map { it.text }
        assertEquals(listOf(ToolResults.error("There is no tool named nope."), ToolResults.error("start is missing")), results)
        assertEquals("Done", c.state.value.turns.last().text)
    }

    @Test fun `each message is sent with the phone state from when it was sent`() = runTest {
        var line = "[first]"
        val c = ChatController(engine, store, this, StandardTestDispatcher(testScheduler), listOf(clock), environment = { line })
        c.send("hi", params)
        advanceUntilIdle()
        line = "[second]"
        c.send("and now?", params)
        advanceUntilIdle()

        assertEquals(listOf("[first]\nhi", "Done", "[second]\nand now?"), engine.sent[1].drop(1).map { it.content })
        assertEquals("hi", c.state.value.turns[0].text)
        assertEquals("[first]", store.saved.getValue(c.state.value.conversationId!!)[0].context)
    }

    @Test fun `a chat's skill is in every reply's system prompt and comes back when the chat is reopened`() = runTest {
        val skill = Skills.BUILT_IN.first { it.id == "jokes" }
        val c = controller()
        c.setSkill(skill)
        c.send("one about cats", params)
        advanceUntilIdle()
        assertTrue(engine.sent[0][0].content.contains(skill.instructions))
        val id = c.state.value.conversationId!!

        c.newChat()
        advanceUntilIdle()
        assertNull(c.state.value.skill)
        c.open(id)
        advanceUntilIdle()
        assertEquals(skill, c.state.value.skill)

        c.setSkill(null)
        c.send("plain now", params)
        advanceUntilIdle()
        assertFalse(engine.sent.last()[0].content.contains(skill.instructions))
    }

    @Test fun `a tool loaded with tool_search is offered in full from then on`() = runTest {
        engine.replies += "" to listOf(call(ToolCatalog.SEARCH, """{"names":["create_note"]}"""))
        engine.replies += "Loaded." to emptyList()
        val c = controller()
        c.send("make a note", params)
        advanceUntilIdle()

        assertEquals(listOf("get_time", ToolCatalog.SEARCH), engine.offered[0])
        assertEquals(listOf("get_time", "create_note"), engine.offered[1])
        assertTrue(c.state.value.turns[2].text.contains("\"loaded\":[\"create_note\"]"))

        c.send("again", params)
        advanceUntilIdle()
        assertEquals(listOf("get_time", "create_note"), engine.offered[2])
    }

    @Test fun `tool calls are not run when no tools were offered`() = runTest {
        engine.replies += "Hi" to listOf(call("get_time"))
        val c = controller()
        c.send("hi", GenerationParams())
        advanceUntilIdle()

        assertEquals(emptyList<String>(), clock.runs)
        assertEquals(1, engine.sent.size)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), c.state.value.turns.map { it.role })
        assertEquals(emptyList<ToolCall>(), c.state.value.turns[1].toolCalls)
    }

    @Test fun `offered choices end the turn and a voice reply reads them out`() = runTest {
        val choices = OfferChoicesTool()
        val offer = call(Choices.TOOL, """{"question":"Which door?","options":["Left","Right"]}""")
        engine.replies += "Two doors stand before you." to listOf(offer)
        val heard = mutableListOf<String>()
        val sink = object : ReplySink {
            override fun finished(text: String, completed: Boolean, spoke: Boolean) {
                heard += text
            }
        }
        val c = ChatController(engine, store, this, StandardTestDispatcher(testScheduler), listOf(choices), sink = sink)
        c.send("start", GenerationParams(tools = listOf(choices.spec)))
        advanceUntilIdle()

        assertEquals("no round after the offer", 1, engine.sent.size)
        val s = c.state.value
        assertFalse(s.busy)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), s.turns.map { it.role })
        assertEquals(listOf(offer), s.turns[1].toolCalls)
        assertEquals(listOf("Two doors stand before you.\n\nWhich door? 1: Left. 2: Right."), heard)
    }

    @Test fun `an offer without two options is an error the model can fix`() = runTest {
        val choices = OfferChoicesTool()
        engine.replies += "" to listOf(call(Choices.TOOL, """{"question":"Which?","options":["Only"]}"""))
        val c = ChatController(engine, store, this, StandardTestDispatcher(testScheduler), listOf(choices))
        c.send("start", GenerationParams(tools = listOf(choices.spec)))
        advanceUntilIdle()

        assertEquals("the error goes back for another round", 2, engine.sent.size)
        assertTrue(engine.sent[1].last().content.contains("error"))
    }
}
