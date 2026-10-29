package com.vanskis.liquidpocket.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Light or dark following the system. With [dynamicColor], Material You colors from the wallpaper
 * (minSdk 33 always has them); otherwise the app's own [Palette].
 */
@Composable
fun LiquidPocketTheme(dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> Palette.dark
        else -> Palette.light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
