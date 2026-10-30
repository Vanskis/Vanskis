package com.vanskis.liquidpocket.settings

/** The app's look: colors, heading font and chat bubbles. Each follows the system's light or dark mode. */
enum class AppTheme(val label: String, val detail: String) {
    AURORA("Aurora", "Blue to pink gradient accents on cool greys."),
    CLAY("Clay", "Warm cream and terracotta, with serif headings."),
    MONO("Mono", "Plain black and white."),
    WALLPAPER("Wallpaper", "Material You colors from your wallpaper."),
    ;

    companion object {
        /**
         * The stored theme by [name]. Before themes existed only the wallpaper switch was stored, so
         * without one [dynamicColor] (absent: on, the old default) picks Wallpaper or Aurora.
         */
        fun from(name: String?, dynamicColor: Boolean?): AppTheme =
            entries.firstOrNull { it.name == name } ?: if (dynamicColor != false) WALLPAPER else AURORA
    }
}
