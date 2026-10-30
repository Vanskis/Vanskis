package com.vanskis.liquidpocket.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vanskis.liquidpocket.LiquidPocketApp
import com.vanskis.liquidpocket.MainActivity
import com.vanskis.liquidpocket.R
import com.vanskis.liquidpocket.core.ChecksumMismatchException
import com.vanskis.liquidpocket.core.DownloadCancelledException
import com.vanskis.liquidpocket.core.Heretic
import com.vanskis.liquidpocket.core.HttpStatusException
import com.vanskis.liquidpocket.core.InstallProgress
import com.vanskis.liquidpocket.core.ModelCatalog
import com.vanskis.liquidpocket.core.ModelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Downloads one model with [com.vanskis.liquidpocket.core.ModelInstaller] as a foreground
 * (dataSync) job. Cancelling the work stops the transfer; the `.part` file stays for a resume.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val spec = inputData.getString(KEY_MODEL_ID)?.let { ModelCatalog.byId(it) ?: Heretic.byId(it, inputData.getString(KEY_REPO)) }
            ?: return Result.failure(errorData("Unknown model"))
        val installer = (applicationContext as LiquidPocketApp).installer
        try {
            setForeground(foregroundInfo(spec, null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Starting a foreground service can be refused when the app is in the background
            // (e.g. a retry); the download still runs as a regular job.
        }

        var lastPhase = -1
        var lastPercent = -1
        return try {
            withContext(Dispatchers.IO) {
                installer.install(spec, isCancelled = { isStopped }) { progress ->
                    val (phase, bytes, total) = encode(progress)
                    val pct = percent(bytes, total)
                    if (!isStopped && (phase != lastPhase || pct != lastPercent)) {
                        lastPhase = phase
                        lastPercent = pct
                        setProgressAsync(workDataOf(KEY_PHASE to phase, KEY_BYTES to bytes, KEY_TOTAL to total))
                        setForegroundAsync(foregroundInfo(spec, progress))
                    }
                }
            }
            Result.success()
        } catch (e: DownloadCancelledException) {
            Result.failure()
        } catch (e: ChecksumMismatchException) {
            Result.failure(errorData("The download was corrupted (checksum mismatch) and was deleted. Try again."))
        } catch (e: HttpStatusException) {
            if (e.code >= 500 && runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(errorData(e.message ?: "HTTP ${e.code}"))
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(errorData("Network error: ${e.message ?: e.javaClass.simpleName}"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(errorData(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun foregroundInfo(spec: ModelSpec, progress: InstallProgress?): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(spec, progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(spec: ModelSpec, progress: InstallProgress?): Notification {
        applicationContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Downloading ${spec.displayName}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
        when (progress) {
            null, InstallProgress.Resolving -> builder.setProgress(0, 0, true)
            is InstallProgress.Downloading -> builder
                .setProgress(100, percent(progress.bytes, progress.total), false)
                .setContentText("${percent(progress.bytes, progress.total)}%")
            is InstallProgress.Verifying -> builder
                .setProgress(100, percent(progress.bytes, progress.total), false)
                .setContentText("Verifying")
        }
        return builder.build()
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        /** The repo of a Heretic build, which is not in the catalog. */
        const val KEY_REPO = "repo"
        const val KEY_ERROR = "error"
        private const val KEY_PHASE = "phase"
        private const val KEY_BYTES = "bytes"
        private const val KEY_TOTAL = "total"
        private const val PHASE_RESOLVING = 0
        private const val PHASE_DOWNLOADING = 1
        private const val PHASE_VERIFYING = 2
        private const val MAX_ATTEMPTS = 5
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001

        private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

        private fun encode(p: InstallProgress): Triple<Int, Long, Long> = when (p) {
            InstallProgress.Resolving -> Triple(PHASE_RESOLVING, 0L, 0L)
            is InstallProgress.Downloading -> Triple(PHASE_DOWNLOADING, p.bytes, p.total)
            is InstallProgress.Verifying -> Triple(PHASE_VERIFYING, p.bytes, p.total)
        }

        /** Reads what [doWork] published with setProgress; null before the first update. */
        fun decodeProgress(data: Data): InstallProgress? {
            if (!data.keyValueMap.containsKey(KEY_PHASE)) return null
            val bytes = data.getLong(KEY_BYTES, 0L)
            val total = data.getLong(KEY_TOTAL, 0L)
            return when (data.getInt(KEY_PHASE, PHASE_RESOLVING)) {
                PHASE_DOWNLOADING -> InstallProgress.Downloading(bytes, total)
                PHASE_VERIFYING -> InstallProgress.Verifying(bytes, total)
                else -> InstallProgress.Resolving
            }
        }
    }
}
