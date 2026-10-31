package com.vanskis.liquidpocket.download

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vanskis.liquidpocket.core.ModelInstaller
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.core.ModelSpecData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

enum class NetworkKind { NONE, UNMETERED, METERED }

/** Starts, cancels and observes model downloads (one unique WorkManager job per model). */
class ModelDownloads(private val context: Context, private val installer: ModelInstaller) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Bumped when files change outside the worker (delete), so [status] re-reads the disk. */
    private val fileChanges = MutableStateFlow(0)

    /** Over Wi-Fi only unless [allowMetered]; an existing pending download is kept as is. */
    fun download(spec: ModelSpec, allowMetered: Boolean) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_MODEL_ID to spec.id,
                    ModelDownloadWorker.KEY_REPO to spec.repo,
                    *ModelSpecData.encode(spec).toList().toTypedArray(),
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(workName(spec), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(spec: ModelSpec) {
        workManager.cancelUniqueWork(workName(spec))
    }

    suspend fun delete(spec: ModelSpec) {
        withContext(Dispatchers.IO) { installer.delete(spec) }
        fileChanges.value++
    }

    fun status(spec: ModelSpec): Flow<DownloadStatus> =
        combine(workManager.getWorkInfosForUniqueWorkFlow(workName(spec)), fileChanges) { infos, _ ->
            downloadStatus(
                installed = installer.installedFile(spec) != null,
                partialBytes = installer.partialBytes(spec),
                work = infos.lastOrNull()?.let { snapshot(it) },
            )
        }.flowOn(Dispatchers.IO)

    /** Whether the model chosen in Settings, or [standard] that stands in for it, is installed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun usable(chosen: Flow<ModelSpec>, standard: ModelSpec): Flow<Boolean> =
        chosen.distinctUntilChanged().flatMapLatest { spec ->
            combine(status(spec), status(standard)) { a, b -> a == DownloadStatus.Ready || b == DownloadStatus.Ready }
        }

    fun currentNetwork(): NetworkKind {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.NONE
        return when {
            cm.activeNetwork == null -> NetworkKind.NONE
            cm.isActiveNetworkMetered -> NetworkKind.METERED
            else -> NetworkKind.UNMETERED
        }
    }

    private fun snapshot(info: WorkInfo): WorkSnapshot {
        val phase = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> WorkSnapshot.Phase.WAITING
            WorkInfo.State.RUNNING -> WorkSnapshot.Phase.RUNNING
            WorkInfo.State.SUCCEEDED -> WorkSnapshot.Phase.SUCCEEDED
            WorkInfo.State.FAILED -> WorkSnapshot.Phase.FAILED
            WorkInfo.State.CANCELLED -> WorkSnapshot.Phase.CANCELLED
        }
        return WorkSnapshot(
            phase = phase,
            progress = ModelDownloadWorker.decodeProgress(info.progress),
            error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR),
        )
    }

    private fun workName(spec: ModelSpec) = "download-${spec.id}"
}
