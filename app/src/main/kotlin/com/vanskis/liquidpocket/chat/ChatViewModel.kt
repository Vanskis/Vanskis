package com.vanskis.liquidpocket.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the conversation for the Chat screen; it survives visits to Settings and Setup. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LiquidPocketApp
    private val controller = ChatController(graph.engine, viewModelScope, Dispatchers.IO)

    val state: StateFlow<ChatState> = controller.state
    val engineState: StateFlow<EngineState> = graph.engine.state

    private val settings: StateFlow<AppSettings> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Called each time the Chat screen opens; loads (or reloads, after a context change) the model. */
    fun onChatOpened() {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { graph.installer.installedFile(ACTIVE_MODEL) }
            if (file == null) {
                controller.showError("${ACTIVE_MODEL.displayName} is not downloaded. Open Settings → Manage model.")
                return@launch
            }
            controller.ensureLoaded(file.path, graph.settings.settings.first().contextSize)
        }
    }

    fun send(text: String) = controller.send(text, GenerationParams(temperature = settings.value.temperature))

    fun stop() = controller.stop()

    fun newChat() = controller.newChat()

    fun dismissError() = controller.dismissError()
}
