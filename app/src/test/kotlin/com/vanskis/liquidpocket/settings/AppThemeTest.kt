package com.vanskis.liquidpocket.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {
    @Test fun `a stored theme wins over the old wallpaper switch`() {
        assertEquals(AppTheme.CLAY, AppTheme.from("CLAY", dynamicColor = true))
        assertEquals(AppTheme.MONO, AppTheme.from("MONO", dynamicColor = null))
    }

    @Test fun `without a theme the old wallpaper switch picks one`() {
        assertEquals(AppTheme.WALLPAPER, AppTheme.from(null, dynamicColor = null))
        assertEquals(AppTheme.WALLPAPER, AppTheme.from(null, dynamicColor = true))
        assertEquals(AppTheme.AURORA, AppTheme.from(null, dynamicColor = false))
        assertEquals(AppTheme.AURORA, AppTheme.from("GONE", dynamicColor = false))
    }
}
