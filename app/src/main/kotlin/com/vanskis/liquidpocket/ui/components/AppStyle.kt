package com.vanskis.liquidpocket.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vanskis.liquidpocket.settings.AppTheme

/**
 * What a theme sets beyond Material colors: the accent (the greeting, the assistant's mark, the
 * send button, the context ring), the user's bubble and the heading font.
 */
@Immutable
class AppStyle(
    /** Three colors, left to right; the typing dots use one each. */
    val accents: List<Color>,
    /** The send button's icon on the accent. */
    val onAccent: Color,
    val userBubbleShape: Shape,
    /** For display, headline and title text; null keeps the system font. */
    val headingFont: FontFamily? = null,
) {
    val brush: Brush = Brush.linearGradient(accents)

    /** [base] with [headingFont] on its display, headline and title styles. */
    fun typography(base: Typography = Typography()): Typography {
        val font = headingFont ?: return base
        return base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = font),
            displayMedium = base.displayMedium.copy(fontFamily = font),
            displaySmall = base.displaySmall.copy(fontFamily = font),
            headlineLarge = base.headlineLarge.copy(fontFamily = font),
            headlineMedium = base.headlineMedium.copy(fontFamily = font),
            headlineSmall = base.headlineSmall.copy(fontFamily = font),
            titleLarge = base.titleLarge.copy(fontFamily = font),
        )
    }

    companion object {
        private val gradient = AppStyle(
            accents = listOf(Accent.Blue, Accent.Purple, Accent.Pink),
            onAccent = Color.White,
            userBubbleShape = RoundedCornerShape(topStart = 24.dp, topEnd = 6.dp, bottomEnd = 24.dp, bottomStart = 24.dp),
        )

        fun of(theme: AppTheme, dark: Boolean): AppStyle = when (theme) {
            AppTheme.AURORA, AppTheme.WALLPAPER -> gradient
            AppTheme.CLAY -> AppStyle(
                accents = listOf(Color(0xFFE08A68), Color(0xFFD97757), Color(0xFFC2613F)),
                onAccent = Color.White,
                userBubbleShape = RoundedCornerShape(16.dp),
                headingFont = FontFamily.Serif,
            )
            AppTheme.MONO -> if (dark) {
                AppStyle(listOf(Color(0xFFFFFFFF), Color(0xFFD0D0D0), Color(0xFFA0A0A0)), Color(0xFF0D0D0D), RoundedCornerShape(24.dp))
            } else {
                AppStyle(listOf(Color(0xFF0D0D0D), Color(0xFF4A4A4A), Color(0xFF7A7A7A)), Color.White, RoundedCornerShape(24.dp))
            }
        }
    }
}

val LocalAppStyle = staticCompositionLocalOf { AppStyle.of(AppTheme.AURORA, dark = false) }
