package com.vanskis.liquidpocket.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.LiquidPocketApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The model is reloaded with a new context size the next time the Chat screen opens. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as LiquidPocketApp).settings

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setContextSize(value: Int) {
        viewModelScope.launch { repo.setContextSize(value) }
    }

    fun setTemperature(value: Float) {
        viewModelScope.launch { repo.setTemperature(value) }
    }
}
