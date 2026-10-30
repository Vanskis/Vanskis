package com.vanskis.liquidpocket.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.vanskis.liquidpocket.settings.AppTheme
import com.vanskis.liquidpocket.ui.components.AppStyle
import com.vanskis.liquidpocket.ui.components.LocalAppStyle

/**
 * [theme] in light or dark following the system: its [Palette] colors (Material You colors from the
 * wallpaper for [AppTheme.WALLPAPER]; minSdk 33 always has them) and its [AppStyle].
 */
@Composable
fun LiquidPocketTheme(theme: AppTheme = AppTheme.WALLPAPER, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (theme == AppTheme.WALLPAPER) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        Palette.of(theme, dark)
    }
    val style = AppStyle.of(theme, dark)
    CompositionLocalProvider(LocalAppStyle provides style) {
        MaterialTheme(colorScheme = colors, typography = style.typography(), content = content)
    }
}
