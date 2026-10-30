package com.vanskis.liquidpocket.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A way of working the user picks for a chat by typing "/" in the composer. Its [instructions] go
 * in the system prompt for every reply of that chat until the user drops it.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val group: String = Skills.MINE,
)

object Skills {
    const val USEFUL = "Useful"
    const val FUN = "Fun"
    const val LEARNING = "Learning"
    const val PLANNING = "Planning"
    const val MINE = "My skills"

    const val MAX_NAME_CHARS = 40
    const val MAX_DESCRIPTION_CHARS = 120
    const val MAX_INSTRUCTIONS_CHARS = 2000

    private fun skill(group: String, id: String, name: String, description: String, instructions: String) =
        Skill(id, name, description, instructions, group)

    val BUILT_IN: List<Skill> = listOf(
        skill(USEFUL, "summarize", "Summarize", "Short summaries of text, documents or pages",
            "Summarize what the user gives you (text, a document or a web page). Start with a one-sentence gist, then up to five bullet points with the key facts, numbers and decisions. Keep names and figures exact. Ask what to summarize if nothing was given."),
        skill(USEFUL, "rewrite", "Rewrite", "Make text clearer, shorter or a different tone",
            "Rewrite the user's text so it reads clearly and naturally, keeping its meaning. Unless the user asks for a tone or length, keep it about as long and in the same register. Give the rewritten text first, then at most one line on what you changed."),
        skill(USEFUL, "translate", "Translate", "Translate between languages",
            "Translate what the user writes. If they don't name the target language, translate into English, or from English into the language they last used. Keep the meaning, tone and formatting; give only the translation, then a short note for idioms that don't carry over."),
        skill(USEFUL, "explain-simply", "Explain simply", "Plain-words explanations with examples",
            "Explain the topic in plain, everyday words, as to a curious beginner. Use one concrete example or comparison, avoid jargon (define any term you must use), and keep it short. Offer to go deeper at the end."),
        skill(USEFUL, "draft-message", "Email or message draft", "Write an email, text or reply",
            "Draft the email or message the user describes. Ask only for what you can't guess (who it's to, the key point). Match the tone to the recipient, keep it brief, and give a subject line for emails. Give the draft ready to copy, with no commentary around it."),
        skill(USEFUL, "proofread", "Proofread", "Fix spelling, grammar and punctuation",
            "Proofread the user's text: fix spelling, grammar, punctuation and clear word errors without changing the style or meaning. Give the corrected text, then list each change briefly. If it is already correct, say so."),
        skill(USEFUL, "math-steps", "Step-by-step math", "Solve math problems showing every step",
            "Solve the user's math problem step by step. State what is asked, then work through numbered steps with each formula in LaTeX, and check every calculation with the calculate tool when it is available. End with the final answer on its own line in bold."),
        skill(FUN, "short-story", "Short story", "A short story on any theme",
            "Write an original short story (about 300 to 500 words) from the user's idea, or invent one if they give none. Give it a title, vivid characters, a clear beginning, middle and end, and a satisfying twist or closing line."),
        skill(FUN, "adventure", "Choose your adventure", "An interactive story where you pick what happens",
            "Run an interactive choose-your-own-adventure story. Ask for a setting if none is given. Each turn, narrate a short scene (under 150 words) in second person, then offer two to four choices with offer_choices when available (else as a numbered list). Keep track of what the player has and has done, and let choices matter."),
        skill(FUN, "poem", "Poem or lyrics", "Poems, verses and song lyrics",
            "Write a poem or song lyrics on the user's subject. Follow any form they ask for (haiku, sonnet, rap, chorus and verses); otherwise choose one that fits. Use fresh images rather than clichés, and give it a title."),
        skill(FUN, "jokes", "Jokes", "Clean, clever jokes",
            "Tell short, clean, clever jokes on the user's topic, one or a few at a time. Vary the kind (puns, one-liners, observational). Never punch down at groups of people."),
        skill(FUN, "riddles", "Riddles", "Riddles to solve, with hints",
            "Give the user one riddle at a time and wait for their guess. Say whether they're right; if not, offer a hint, and reveal the answer only when they ask or after three tries. Keep a running score."),
        skill(FUN, "roleplay", "Roleplay", "Play a character or scene",
            "Play the character or scene the user sets up, staying in character and in the story's world. Write only your character's words and actions, keep replies short so the user can respond, and step out of character only if they ask."),
        skill(LEARNING, "quiz", "Quiz me", "Multiple-choice quiz with a score",
            "Quiz the user on the topic they choose, one question at a time. Give each question with three or four answers to tap via offer_choices when available. After each answer say if it was right and why in one line, keep score, and after ten questions give the final score and what to review."),
        skill(LEARNING, "flashcards", "Flashcards", "Practice with flashcards",
            "Run a flashcard session on the user's topic. Show the front of one card at a time, wait for the user's answer, then show the back and say whether they had it. Bring back the cards they missed until they get them, and give a short tally at the end."),
        skill(LEARNING, "socratic", "Socratic tutor", "Learn by being asked questions",
            "Tutor the user Socratically: don't give the answer. Ask one guiding question at a time that leads them a step closer, build on what they say, and correct misunderstandings gently with a question. Confirm when they reach the answer and sum up what they learned."),
        skill(PLANNING, "daily-plan", "Daily plan", "Plan your day, with calendar events",
            "Help the user plan their day. Ask what they need to do, fixed appointments and how much time they have, then lay out a realistic time-blocked schedule with breaks. Offer to add the blocks to their calendar with the calendar tool, and add them only after they agree."),
        skill(PLANNING, "recipe", "Recipe from what you have", "Cook with the ingredients you have",
            "Suggest a recipe using the ingredients the user has, assuming only basic staples (oil, salt, pepper) beyond them. Give the dish name, servings and time, an ingredient list, and short numbered steps. Mention easy swaps for anything missing."),
        skill(PLANNING, "packing", "Packing list", "A packing list for your trip",
            "Make a packing list for the user's trip. Ask for the destination, length, season and activities if not given (use the weather tool when it is available). Group the list (clothes, toiletries, documents, tech, extras) as checkboxes, scaled to the trip's length."),
    )

    /** Built-in groups in the order the "/" list shows them, then the user's own. */
    val GROUPS = listOf(USEFUL, FUN, LEARNING, PLANNING, MINE)

    /** [all] skills for what the user typed after "/": by name first, then by description. */
    fun matching(all: List<Skill>, query: String): List<Skill> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all.sortedBy { GROUPS.indexOf(it.group).let { i -> if (i < 0) GROUPS.size else i } }
        val byName = all.filter { it.name.lowercase().startsWith(q) } + all.filter { q in it.name.lowercase() }
        val byText = all.filter { q in it.description.lowercase() }
        return (byName + byText).distinct()
    }

    /** What follows the "/" when the composer holds only a slash command being typed, else null. */
    fun command(text: String): String? = if (text.startsWith("/") && '\n' !in text && text.length <= 41) text.substring(1) else null

    /** A user skill from the Settings form; null when it has no name or no instructions. */
    fun custom(id: String, name: String, description: String, instructions: String): Skill? {
        val n = name.trim().take(MAX_NAME_CHARS)
        val i = instructions.trim().take(MAX_INSTRUCTIONS_CHARS)
        if (n.isEmpty() || i.isEmpty()) return null
        return Skill(id, n, description.trim().take(MAX_DESCRIPTION_CHARS), i, MINE)
    }

    fun encode(skills: List<Skill>): String = buildJsonArray { skills.forEach { add(encodeOne(it)) } }.toString()

    /** Malformed JSON gives no skills; this never throws. */
    fun decode(json: String?): List<Skill> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { decodeOne(it as? JsonObject) }
    }

    fun encodeOne(skill: Skill): JsonObject = buildJsonObject {
        put("id", skill.id)
        put("name", skill.name)
        put("description", skill.description)
        put("instructions", skill.instructions)
        put("group", skill.group)
    }

    fun decodeOne(obj: JsonObject?): Skill? {
        fun text(key: String) = (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val id = text("id") ?: return null
        val name = text("name") ?: return null
        val instructions = text("instructions") ?: return null
        return Skill(id, name, text("description").orEmpty(), instructions, text("group") ?: MINE)
    }
}
