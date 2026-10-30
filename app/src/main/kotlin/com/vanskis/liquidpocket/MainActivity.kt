package com.vanskis.liquidpocket

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.vanskis.liquidpocket.access.LaunchIntents
import com.vanskis.liquidpocket.access.SharedDocuments
import com.vanskis.liquidpocket.chat.ChatViewModel
import com.vanskis.liquidpocket.ui.ChatScreen
import com.vanskis.liquidpocket.ui.LiquidPocketTheme
import com.vanskis.liquidpocket.ui.MemoryScreen
import com.vanskis.liquidpocket.ui.SettingsScreen
import com.vanskis.liquidpocket.ui.SetupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { SETUP, CHAT, SETTINGS, MEMORY }

class MainActivity : ComponentActivity() {
    /** The same instance the Chat screen gets from viewModel(). */
    private val chat: ChatViewModel by viewModels()

    /** Counts shares and widget and tile taps, so the screen switches to the chat for each. */
    private var launches by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not after a recreation: the request was handled when the intent first arrived.
        if (savedInstanceState == null) handleLaunch(intent)
        // A few-byte metadata read and a stat; cheap enough to decide the first screen here.
        val app = application as LiquidPocketApp
        val installed = app.installer.installedFile(ACTIVE_MODEL) != null
        setContent {
            // Null until the settings are read; wallpaper colors (the default) meanwhile.
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            LiquidPocketTheme(dynamicColor = settings?.dynamicColor ?: true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LiquidPocketRoot(start = if (installed) Screen.CHAT else Screen.SETUP, launches = launches)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    /** A share, the widget or the tile: a new chat with what was shared (access/LaunchRequest.kt). */
    private fun handleLaunch(intent: Intent) {
        val request = LaunchIntents.parse(this, intent) ?: return
        chat.openLaunch(request)
        // Copied now, while the sender's URI grant lasts; the chat picks them up from SharedInbox.
        for (uri in request.documents) {
            lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { SharedDocuments.import(applicationContext, uri) } }
                    .onFailure { chat.showError("Could not add the shared document: ${it.message ?: it.javaClass.simpleName}") }
            }
        }
        launches++
    }
}

/** State-driven navigation: the screens share activity-scoped view models. */
@Composable
private fun LiquidPocketRoot(start: Screen, launches: Int) {
    var screen by rememberSaveable { mutableStateOf(start) }
    // Each share or widget tap shows its new chat; during setup it waits there until the model is in.
    LaunchedEffect(launches) {
        if (launches > 0 && screen != Screen.SETUP) screen = Screen.CHAT
    }
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
