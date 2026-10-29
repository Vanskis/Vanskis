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
