package com.example.data.downloader

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.local.AppDatabase

/** Runs one persisted download outside the UI process and survives normal app closure. */
class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val db = AppDatabase.getInstance(applicationContext)
        val entity = db.downloadDao().getDownloadById(downloadId) ?: return Result.failure()
        if (entity.status == "COMPLETED") return Result.success()

        try {
            DownloadManager.init(applicationContext)
            setForeground(createForegroundInfo(entity))
            DownloadManager.runDownloadWork(applicationContext, entity)
        } catch (e: Exception) {
            com.example.data.diagnostics.DiagnosticLogger.e(
                "DownloadWorker",
                "فشل تشغيل مهمة التنزيل دون إغلاق التطبيق: ${e.message}",
                e
            )
            db.downloadDao().updateStatus(downloadId, "FAILED", e.localizedMessage ?: "تعذر بدء التنزيل")
            // A failed download is surfaced to the user. Automatic retry here used
            // to restart the same expired media URL repeatedly; the user can retry
            // explicitly after a fresh URL is captured.
            return Result.failure(workDataOf("error" to (e.localizedMessage ?: "فشل بدء التنزيل")))
        }

        val result = db.downloadDao().getDownloadById(downloadId)
        return when {
            result?.status == "COMPLETED" -> Result.success()
            result?.status == "PAUSED" || result?.status == "CANCELLED" -> Result.failure()
            else -> Result.failure(workDataOf("error" to (result?.errorReason ?: "فشل التنزيل بعد عدة محاولات")))
        }
    }

    private fun createForegroundInfo(entity: com.example.data.local.entity.DownloadEntity): ForegroundInfo =
        DownloadManager.createForegroundInfo(applicationContext, entity)

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val WORK_PREFIX = "omnibrowser-download-"
        const val MAX_RETRIES = 3
    }
}
