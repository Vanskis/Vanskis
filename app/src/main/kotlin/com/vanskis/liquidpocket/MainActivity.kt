package com.vanskis.liquidpocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vanskis.liquidpocket.ui.ChatScreen
import com.vanskis.liquidpocket.ui.LiquidPocketTheme
import com.vanskis.liquidpocket.ui.MemoryScreen
import com.vanskis.liquidpocket.ui.SettingsScreen
import com.vanskis.liquidpocket.ui.SetupScreen

enum class Screen { SETUP, CHAT, SETTINGS, MEMORY }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A few-byte metadata read and a stat; cheap enough to decide the first screen here.
        val app = application as LiquidPocketApp
        val installed = app.installer.installedFile(ACTIVE_MODEL) != null
        setContent {
            // Null until the settings are read; wallpaper colors (the default) meanwhile.
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            LiquidPocketTheme(dynamicColor = settings?.dynamicColor ?: true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LiquidPocketRoot(start = if (installed) Screen.CHAT else Screen.SETUP)
                }
            }
        }
    }
}

/** State-driven navigation: the screens share activity-scoped view models. */
@Composable
private fun LiquidPocketRoot(start: Screen) {
    var screen by rememberSaveable { mutableStateOf(start) }
    when (screen) {
        Screen.SETUP -> SetupScreen(onOpenChat = { screen = Screen.CHAT })
        Screen.CHAT -> ChatScreen(onOpenSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> SettingsScreen(
            onBack = { screen = Screen.CHAT },
            onManageModel = { screen = Screen.SETUP },
            onOpenMemory = { screen = Screen.MEMORY },
        )
        Screen.MEMORY -> MemoryScreen(onBack = { screen = Screen.SETTINGS })
    }
}
