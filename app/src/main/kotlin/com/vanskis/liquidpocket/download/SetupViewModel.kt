package com.vanskis.liquidpocket.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.engine.EngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LiquidPocketApp
    val model = ACTIVE_MODEL

    val status: StateFlow<DownloadStatus?> =
        graph.downloads.status(model).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun currentNetwork(): NetworkKind = graph.downloads.currentNetwork()

    fun download(allowMetered: Boolean) = graph.downloads.download(model, allowMetered)

    fun cancel() = graph.downloads.cancel(model)

    fun delete() {
        viewModelScope.launch {
            try {
                // The engine memory-maps the file; release it before deleting.
                if (graph.engine.state.value !is EngineState.Idle) graph.engine.unload()
                graph.downloads.delete(model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Could not delete the model: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }
}
