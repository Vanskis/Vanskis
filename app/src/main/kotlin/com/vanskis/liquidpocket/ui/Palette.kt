package com.vanskis.liquidpocket.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** The app's own teal palette, used when wallpaper colors are turned off in Settings. */
object Palette {
    val light: ColorScheme = lightColorScheme(
        primary = Color(0xFF006782),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBDE9FF),
        onPrimaryContainer = Color(0xFF001F29),
        secondary = Color(0xFF4D616C),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD0E6F2),
        onSecondaryContainer = Color(0xFF081E27),
        tertiary = Color(0xFF5D5B7D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE3DFFF),
        onTertiaryContainer = Color(0xFF1A1836),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF6FAFE),
        onBackground = Color(0xFF171C1F),
        surface = Color(0xFFF6FAFE),
        onSurface = Color(0xFF171C1F),
        surfaceVariant = Color(0xFFDCE4E9),
        onSurfaceVariant = Color(0xFF40484C),
        outline = Color(0xFF70787D),
        outlineVariant = Color(0xFFC0C8CC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF0F4F8),
        surfaceContainer = Color(0xFFEAEEF2),
        surfaceContainerHigh = Color(0xFFE4E9ED),
        surfaceContainerHighest = Color(0xFFDFE3E7),
    )

    val dark: ColorScheme = darkColorScheme(
        primary = Color(0xFF63D3FF),
        onPrimary = Color(0xFF003545),
        primaryContainer = Color(0xFF004D63),
        onPrimaryContainer = Color(0xFFBDE9FF),
        secondary = Color(0xFFB4CAD6),
        onSecondary = Color(0xFF1F333C),
        secondaryContainer = Color(0xFF354A53),
        onSecondaryContainer = Color(0xFFD0E6F2),
        tertiary = Color(0xFFC6C2EA),
        onTertiary = Color(0xFF2F2D4C),
        tertiaryContainer = Color(0xFF454364),
        onTertiaryContainer = Color(0xFFE3DFFF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0F1417),
        onBackground = Color(0xFFDEE3E7),
        surface = Color(0xFF0F1417),
        onSurface = Color(0xFFDEE3E7),
        surfaceVariant = Color(0xFF40484C),
        onSurfaceVariant = Color(0xFFC0C8CC),
        outline = Color(0xFF8A9297),
        outlineVariant = Color(0xFF40484C),
        surfaceContainerLowest = Color(0xFF0A0F12),
        surfaceContainerLow = Color(0xFF171C1F),
        surfaceContainer = Color(0xFF1B2023),
        surfaceContainerHigh = Color(0xFF252B2E),
        surfaceContainerHighest = Color(0xFF303538),
    )
}
