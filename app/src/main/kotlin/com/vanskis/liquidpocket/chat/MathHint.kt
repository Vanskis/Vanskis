package com.vanskis.liquidpocket.chat

/**
 * Small models do arithmetic in their heads and get it wrong. A message that asks for math gets
 * [HINT] after its text, and its chat has the calculate tool offered in full from then on, so the
 * model need not find it through tool_search first.
 */
object MathHint {
    const val HINT = "(Use the calculate tool for any arithmetic here; do not work numbers out yourself.)"

    // Two numbers joined by an operator: "12*7", "3.5 + 2", "2^10". A minus needs spaces around
    // it, so dates and phone numbers ("2026-05-01", "555-1234") stay plain.
    private val OPERATION = Regex("""\d\s*[+*/×÷^]\s*\(?\s*\d|\d\s+-\s+\(?\s*\d""")
    private val WORDS = Regex(
        """\b(calculate|compute|solve|how much|how many|total|sum|product|average|mean|percent|percentage|""" +
            """square root|sqrt|power of|squared|cubed|convert|divided|times|plus|minus|multiply|interest|""" +
            """derivative|integral|equation)\b|%""",
        RegexOption.IGNORE_CASE,
    )

    /** True when [text] asks for math: an operation between numbers, or a math word with a number. */
    fun wanted(text: String): Boolean =
        OPERATION.containsMatchIn(text) || (text.any { it.isDigit() } && WORDS.containsMatchIn(text))

    /** [text] with [HINT] after it. */
    fun hinted(text: String): String = if (text.isBlank()) HINT else "$text\n\n$HINT"
}
