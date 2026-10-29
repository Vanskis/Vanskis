package com.vanskis.liquidpocket.download

import com.vanskis.liquidpocket.core.InstallProgress

/** What the Setup screen shows for a model. */
sealed interface DownloadStatus {
    /** [partialBytes] > 0 means a cancelled or interrupted download that will resume. */
    data class NotDownloaded(val partialBytes: Long) : DownloadStatus
    /** Enqueued, waiting for a (by default unmetered) network or a retry. */
    data object Waiting : DownloadStatus
    data object Resolving : DownloadStatus
    data class Downloading(val bytes: Long, val total: Long) : DownloadStatus
    data class Verifying(val bytes: Long, val total: Long) : DownloadStatus
    data object Ready : DownloadStatus
    data class Failed(val message: String) : DownloadStatus

    /** True while there is live work for this model, so Download and Delete are not offered. */
    val active: Boolean get() = this is Waiting || this is Resolving || this is Downloading || this is Verifying
}

/** The parts of a WorkManager `WorkInfo` the status depends on, so the mapping is plain Kotlin. */
data class WorkSnapshot(val phase: Phase, val progress: InstallProgress?, val error: String?) {
    enum class Phase { WAITING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}

fun downloadStatus(installed: Boolean, partialBytes: Long, work: WorkSnapshot?): DownloadStatus {
    if (installed) return DownloadStatus.Ready
    return when (work?.phase) {
        null, WorkSnapshot.Phase.SUCCEEDED, WorkSnapshot.Phase.CANCELLED -> DownloadStatus.NotDownloaded(partialBytes)
        WorkSnapshot.Phase.WAITING -> DownloadStatus.Waiting
        WorkSnapshot.Phase.FAILED -> DownloadStatus.Failed(work.error ?: "Download failed")
        WorkSnapshot.Phase.RUNNING -> when (val p = work.progress) {
            null, InstallProgress.Resolving -> DownloadStatus.Resolving
            is InstallProgress.Downloading -> DownloadStatus.Downloading(p.bytes, p.total)
            is InstallProgress.Verifying -> DownloadStatus.Verifying(p.bytes, p.total)
        }
    }
}

/** Whole percent of [bytes] out of [total], 0 when the total is unknown. */
fun percent(bytes: Long, total: Long): Int =
    if (total <= 0) 0 else (bytes * 100 / total).toInt().coerceIn(0, 100)
