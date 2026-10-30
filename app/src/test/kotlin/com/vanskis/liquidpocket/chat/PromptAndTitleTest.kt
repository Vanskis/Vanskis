package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAndTitleTest {
    @Test fun `without facts the system prompt is the base prompt`() {
        assertEquals(SYSTEM_PROMPT, SystemPrompt.build(emptyList()))
        assertEquals(SYSTEM_PROMPT, SystemPrompt.build(listOf("  ", "\n")))
    }

    @Test fun `facts become one bullet line each`() {
        assertEquals(
            "$SYSTEM_PROMPT\n\n${SystemPrompt.FACTS_HEADER}\n- Vegetarian\n- Two cats, Miso and Tofu",
            SystemPrompt.build(listOf(" Vegetarian ", "Two cats,\nMiso and Tofu")),
        )
    }

    @Test fun `facts past the budget are left out, oldest kept`() {
        val fact = "x".repeat(SystemPrompt.MAX_FACT_CHARS)
        val facts = List(SystemPrompt.FACTS_BUDGET_CHARS / SystemPrompt.MAX_FACT_CHARS + 2) { i -> "$i$fact" }
        val fitting = SystemPrompt.fittingCount(facts)

        assertEquals(SystemPrompt.FACTS_BUDGET_CHARS / SystemPrompt.MAX_FACT_CHARS, fitting)
        val prompt = SystemPrompt.build(facts)
        assertEquals(fitting, prompt.lines().count { it.startsWith("- ") })
        assertTrue(prompt.length <= SYSTEM_PROMPT.length + SystemPrompt.FACTS_HEADER.length + SystemPrompt.FACTS_BUDGET_CHARS + 3 * fitting + 3)
    }

    @Test fun `custom instructions are appended after the facts`() {
        assertEquals(
            "$SYSTEM_PROMPT\n\n${SystemPrompt.FACTS_HEADER}\n- Vegetarian\n\n${SystemPrompt.CUSTOM_HEADER}\nAnswer in French.",
            SystemPrompt.build(listOf("Vegetarian"), custom = "  Answer in French. "),
        )
        assertTrue(SystemPrompt.build(emptyList(), tools = true, custom = "Be brief.").endsWith("\n\n${SystemPrompt.CUSTOM_HEADER}\nBe brief."))
    }

    @Test fun `web search adds one line after the tools line`() {
        assertEquals(
            "$SYSTEM_PROMPT\n${SystemPrompt.TOOLS_LINE}\n${SystemPrompt.WEB_SEARCH_LINE}",
            SystemPrompt.build(emptyList(), tools = true, webSearch = true),
        )
        assertTrue(SystemPrompt.WEB_SEARCH_LINE.contains("web_search"))
        assertEquals("$SYSTEM_PROMPT\n${SystemPrompt.TOOLS_LINE}", SystemPrompt.build(emptyList(), tools = true))
        assertEquals("not without tools", SYSTEM_PROMPT, SystemPrompt.build(emptyList(), webSearch = true))
    }

    @Test fun `blank custom instructions add nothing`() {
        assertEquals(SYSTEM_PROMPT, SystemPrompt.build(emptyList(), custom = ""))
        assertEquals(SYSTEM_PROMPT, SystemPrompt.build(emptyList(), custom = " \n\t "))
    }

    @Test fun `custom instructions are cut at the limit`() {
        val prompt = SystemPrompt.build(emptyList(), custom = "Q".repeat(SystemPrompt.MAX_CUSTOM_CHARS + 50))
        assertEquals(SystemPrompt.MAX_CUSTOM_CHARS, prompt.count { it == 'Q' })
    }

    @Test fun `every preset fits the custom instructions limit`() {
        assertEquals(listOf("Concise", "Explain like I'm 12", "Friendly", "Formal", "Code helper", "Interactive story"), InstructionPresets.ALL.map { it.first })
        assertTrue(InstructionPresets.ALL.all { it.second.isNotBlank() && it.second.length <= SystemPrompt.MAX_CUSTOM_CHARS })
    }

    @Test fun `a short first message is the title as is, on one line`() {
        assertEquals("Plan a trip to Lisbon", ConversationTitle.from("  Plan a trip\nto   Lisbon "))
        assertEquals(ConversationTitle.UNTITLED, ConversationTitle.from("   "))
    }

    @Test fun `a long first message is cut at a word boundary`() {
        assertEquals(
            "Can you help me write a cover letter for…",
            ConversationTitle.from("Can you help me write a cover letter for a barista job in town?"),
        )
        val noSpaces = "a".repeat(60)
        assertEquals("a".repeat(ConversationTitle.MAX_LENGTH) + "…", ConversationTitle.from(noSpaces))
    }

    @Test fun `model status follows loading, ready and error`() {
        assertEquals(ModelStatus.Loading, modelStatus(EngineState.Idle, ChatState(loading = true)))
        assertEquals(ModelStatus.Loading, modelStatus(EngineState.Loading, ChatState()))
        assertEquals(ModelStatus.Ready, modelStatus(EngineState.Ready("/m", 8192), ChatState()))
        assertEquals(ModelStatus.Ready, modelStatus(EngineState.Generating, ChatState(streaming = "")))
        assertEquals(ModelStatus.Error("bad"), modelStatus(EngineState.Error("bad"), ChatState()))
        assertEquals(ModelStatus.NotLoaded, modelStatus(EngineState.Idle, ChatState()))
    }
}
