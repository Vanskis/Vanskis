package com.vanskis.liquidpocket.ui

import com.vanskis.liquidpocket.ui.components.MathText
import com.vanskis.liquidpocket.ui.components.MathText.Formula
import com.vanskis.liquidpocket.ui.components.MathText.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTextTest {
    private fun formulas(text: String) = MathText.pieces(text).filterIsInstance<Piece.Math>().map { it.formula }

    @Test fun `finds inline and display formulas in every notation`() {
        assertEquals(
            listOf(
                Formula("x^2", false), Formula("\\frac{a}{b}", true),
                Formula("y", false), Formula("\\sum_i i", true),
            ),
            formulas("Let \$x^2\$ be\n\$\$ \\frac{a}{b} \$\$ and \\(y\\) then \\[\\sum_i i\\]"),
        )
    }

    @Test fun `plain text around a formula is kept in order`() {
        assertEquals(
            listOf(Piece.Plain("a "), Piece.Math(Formula("b", false), "\$b\$"), Piece.Plain(" c")),
            MathText.pieces("a \$b\$ c"),
        )
    }

    @Test fun `prices are not math`() {
        assertFalse(MathText.hasMath("It costs \$5 and \$10 today."))
        assertFalse(MathText.hasMath("Between \$ 3 and 4 \$"))
        assertFalse(MathText.hasMath("Pay \\\$x\\\$ now"))
    }

    @Test fun `code is never math`() {
        assertFalse(MathText.hasMath("Run `echo \$HOME\$` here"))
        assertFalse(MathText.hasMath("```sh\necho \$a\$\n```\n"))
        assertTrue(MathText.hasMath("```sh\necho \$a\$\n```\nso \$x\$"))
    }

    @Test fun `an unclosed formula stays text while it streams`() {
        assertFalse(MathText.hasMath("So \$\$\\frac{1}{"))
        assertFalse(MathText.hasMath("So \\[x +"))
        assertEquals("So \$\$\\frac{1}{", MathText.pieces("So \$\$\\frac{1}{").joinToString("") { (it as Piece.Plain).text })
    }

    @Test fun `inline formulas stay on one line`() {
        assertFalse(MathText.hasMath("a \$b\nc\$ d"))
    }
}
