package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.EngineState

/** What the chat's top bar says about the model. */
sealed interface ModelStatus {
    data object NotLoaded : ModelStatus
    data object Loading : ModelStatus
    /** Loaded; also while it is generating. */
    data object Ready : ModelStatus
    data class Error(val message: String) : ModelStatus
}

fun modelStatus(engine: EngineState, chat: ChatState): ModelStatus = when {
    chat.loading || engine is EngineState.Loading -> ModelStatus.Loading
    engine is EngineState.Ready || engine is EngineState.Generating -> ModelStatus.Ready
    engine is EngineState.Error -> ModelStatus.Error(engine.message)
    else -> ModelStatus.NotLoaded
}
