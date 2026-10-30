package com.vanskis.liquidpocket.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.MEDIA_MODEL
import com.vanskis.liquidpocket.chat.LiveVoiceModel
import com.vanskis.liquidpocket.chat.VoiceEngine
import com.vanskis.liquidpocket.download.DownloadStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The model is reloaded with a new context size the next time the Chat screen opens. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LiquidPocketApp
    private val repo = graph.settings

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Whether Omni is installed, which live voice needs to use it. */
    val omniInstalled: StateFlow<Boolean> =
        graph.downloads.status(MEDIA_MODEL).map { it == DownloadStatus.Ready }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setContextSize(value: Int) {
        viewModelScope.launch { repo.setContextSize(value) }
    }

    fun setTemperature(value: Float) {
        viewModelScope.launch { repo.setTemperature(value) }
    }

    fun setDynamicColor(value: Boolean) {
        viewModelScope.launch { repo.setDynamicColor(value) }
    }

    fun setSpeakReplies(value: Boolean) {
        viewModelScope.launch { repo.setSpeakReplies(value) }
    }

    fun setVoiceEngine(value: VoiceEngine) {
        viewModelScope.launch { repo.setVoiceEngine(value) }
    }

    fun setWebSearch(value: Boolean) {
        viewModelScope.launch { repo.setWebSearch(value) }
    }

    fun setLiveVoiceModel(value: LiveVoiceModel) {
        viewModelScope.launch { repo.setLiveVoiceModel(value) }
    }

    fun setCustomInstructions(value: String) {
        viewModelScope.launch { repo.setCustomInstructions(value) }
    }
}
