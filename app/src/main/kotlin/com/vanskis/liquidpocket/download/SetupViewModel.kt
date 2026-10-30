package com.vanskis.liquidpocket.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.MEDIA_MODEL
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.engine.EngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Setup downloads [model] (the chat opens once it is installed), then [mediaModel] for photos and
 * voice, which carries on in the background and can also be downloaded or deleted on its own.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LiquidPocketApp
    val model = ACTIVE_MODEL
    val mediaModel = MEDIA_MODEL

    val status: StateFlow<DownloadStatus?> = statusOf(model)
    val mediaStatus: StateFlow<DownloadStatus?> = statusOf(mediaModel)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun statusOf(spec: ModelSpec) =
        graph.downloads.status(spec).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun currentNetwork(): NetworkKind = graph.downloads.currentNetwork()

    /** Downloads [spec]; for [model], [mediaModel] follows once it is installed, on the same networks. */
    fun download(spec: ModelSpec, allowMetered: Boolean) {
        graph.downloads.download(spec, allowMetered)
        if (spec != model) return
        // Kept by the view model, which lives as long as the activity, so it outlasts leaving Setup;
        // if the app is closed first, the media model shows as a download of its own here.
        viewModelScope.launch {
            graph.downloads.status(model).first { it == DownloadStatus.Ready }
            graph.downloads.download(mediaModel, allowMetered)
        }
    }

    fun cancel(spec: ModelSpec) = graph.downloads.cancel(spec)

    fun delete(spec: ModelSpec) {
        viewModelScope.launch {
            try {
                // The engine memory-maps the file; release it before deleting.
                if (graph.engine.state.value !is EngineState.Idle) graph.engine.unload()
                graph.downloads.delete(spec)
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
