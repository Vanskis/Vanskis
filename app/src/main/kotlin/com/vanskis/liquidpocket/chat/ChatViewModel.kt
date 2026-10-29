package com.vanskis.liquidpocket.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.engine.EngineState
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.settings.AppSettings
import com.vanskis.liquidpocket.ui.components.ChatActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the open conversation for the Chat screen; it survives visits to Settings and Setup. */
class ChatViewModel(app: Application) : AndroidViewModel(app), ChatActions {
    private val graph = app as LiquidPocketApp
    private val controller = ChatController(graph.engine, graph.chats, viewModelScope, Dispatchers.IO)

    val state: StateFlow<ChatState> = controller.state
    val engineState: StateFlow<EngineState> = graph.engine.state

    /** Saved conversations for the drawer, newest first. */
    val conversations: StateFlow<List<Conversation>> =
        graph.chats.conversations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val settings: StateFlow<AppSettings> =
        graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private fun params() = GenerationParams(temperature = settings.value.temperature)

    /** Called each time the Chat screen opens; loads (or reloads, after a context change) the model. */
    fun onChatOpened() {
        viewModelScope.launch {
            val (file, mmproj) = withContext(Dispatchers.IO) {
                graph.installer.installedFile(ACTIVE_MODEL) to graph.installer.installedMmproj(ACTIVE_MODEL)
            }
            if (file == null) {
                controller.showError("${ACTIVE_MODEL.displayName} is not downloaded. Open Settings → Manage model.")
                return@launch
            }
            controller.ensureLoaded(file.path, graph.settings.settings.first().contextSize, mmproj?.path)
        }
    }

    override fun send(text: String) = controller.send(text, params())

    override fun stop() = controller.stop()

    override fun regenerate() = controller.regenerate(params())

    override fun editAndResend(text: String) = controller.editAndResend(text, params())

    override fun newChat() = controller.newChat()

    override fun openConversation(id: Long) = controller.open(id)

    override fun renameConversation(id: Long, title: String) {
        viewModelScope.launch {
            runCatching { graph.chats.rename(id, title) }
                .onFailure { controller.showError("Could not rename the chat: ${it.message}") }
        }
    }

    override fun deleteConversation(id: Long) = controller.deleteConversation(id)

    override fun dismissError() = controller.dismissError()
}
