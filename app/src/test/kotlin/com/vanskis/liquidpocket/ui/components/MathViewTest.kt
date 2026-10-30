package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class MathViewTest {
    private val source = """\[ M = \frac{4000\;\text{N} \times 2.0\;\text{m}}{4} \]"""

    @Test
    fun `a drawn formula covers one character, so no line can break inside it`() {
        val image = InlineTextContent(Placeholder(10.sp, 10.sp, PlaceholderVerticalAlign.TextCenter)) {}
        val text = buildAnnotatedString { appendMath(0, source, mapOf("math:0" to image)) }
        assertEquals(IMAGE_TEXT, text.text)
    }

    @Test
    fun `a formula with no image shows its source`() {
        val text = buildAnnotatedString { appendMath(0, source, emptyMap()) }
        assertEquals(source, text.text)
    }
}
