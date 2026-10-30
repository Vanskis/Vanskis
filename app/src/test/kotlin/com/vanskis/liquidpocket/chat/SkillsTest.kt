package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.data.MessageExtras
import com.vanskis.liquidpocket.data.MessageJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsTest {
    private val mine = Skill("u1", "Pirate talk", "Answers like a pirate", "Talk like a pirate.")

    @Test fun `the built-ins cover every group, with unique ids and instructions in bounds`() {
        val all = Skills.BUILT_IN
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(Skills.GROUPS - Skills.MINE, all.map { it.group }.distinct())
        assertTrue(all.all { it.instructions.length <= Skills.MAX_INSTRUCTIONS_CHARS && it.name.length <= Skills.MAX_NAME_CHARS })
    }

    @Test fun `a slash command lists skills by name first, then by description`() {
        assertEquals("tra", Skills.command("/tra"))
        assertEquals("", Skills.command("/"))
        assertNull(Skills.command("hello /x"))
        assertNull(Skills.command("/a\nb"))
        val all = Skills.BUILT_IN + mine
        assertEquals("Translate", Skills.matching(all, "tra").first().name)
        assertEquals(listOf("Pirate talk"), Skills.matching(all, "pirate").map { it.name })
        assertEquals(all.size, Skills.matching(all, "").size)
        assertEquals("Pirate talk", Skills.matching(all, "").last().name)
    }

    @Test fun `user skills need a name and instructions, and survive saving`() {
        assertNull(Skills.custom("x", " ", "d", "i"))
        assertNull(Skills.custom("x", "n", "d", " "))
        assertEquals(Skill("x", "N", "D", "I"), Skills.custom("x", " N ", "D", " I "))
        assertEquals(listOf(mine), Skills.decode(Skills.encode(listOf(mine))))
        assertEquals(emptyList<Skill>(), Skills.decode("not json"))
        val extras = MessageExtras(skill = mine)
        assertEquals(extras, MessageJson.decodeExtras(MessageJson.encodeExtras(extras)))
    }

    @Test fun `the skill goes last in the system prompt`() {
        val prompt = SystemPrompt.build(emptyList(), skill = mine)
        assertTrue(prompt.endsWith("${SystemPrompt.SKILL_HEADER} Pirate talk\nTalk like a pirate."))
    }
}
