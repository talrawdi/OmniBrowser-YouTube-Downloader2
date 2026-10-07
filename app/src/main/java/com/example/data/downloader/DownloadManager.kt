package com.example.data.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.diagnostics.DiagnosticLogger
import com.example.data.local.AppDatabase
import com.example.data.local.entity.DownloadEntity
import com.example.data.privacy.SecureDnsManager
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class StorageDestination {
    INTERNAL_VAULT,
    PUBLIC_DOWNLOADS,
    CUSTOM
}

object DownloadManager {
    // v2 avoids inheriting the old LOW-importance channel created by earlier APKs.
    private const val CHANNEL_ID = "downloads_channel_v2"
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val downloadHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val inFlightFingerprints = ConcurrentHashMap<String, String>()
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var speedBoosterEnabled: Boolean = true
    var preferredDestination: StorageDestination = StorageDestination.PUBLIC_DOWNLOADS
    var customDirectoryPath: String? = null

    private lateinit var okHttpClient: OkHttpClient
    @Volatile private var initialized = false

    fun init(context: Context) {
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .dns(SecureDnsManager.getSecureDns { okHttpClient })
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        createNotificationChannel(context)
        initialized = true
    }

    private fun workName(downloadId: String) = "${DownloadWorker.WORK_PREFIX}$downloadId"

    private fun enqueueDownloadWork(context: Context, downloadId: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to downloadId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(workName(downloadId), ExistingWorkPolicy.KEEP, request)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "إشعارات التنزيل السريع",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "عرض تقدم وحالة تنزيل مقاطع الفيديو والملفات"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    fun startDownload(
        context: Context,
        url: String,
        title: String,
        quality: String = "720p HD",
        subtitleUrl: String? = null,
        mimeType: String = "video/mp4",
        pageUrl: String? = null,
        destination: StorageDestination = preferredDestination
    ): String {
        if (!initialized) init(context.applicationContext)
        val cleanDownloadUrl = MediaSniffer.cleanRangeParams(url)
        val fingerprint = downloadFingerprint(cleanDownloadUrl, quality, mimeType, pageUrl)
        val downloadId = java.util.UUID.randomUUID().toString()
        val existingId = inFlightFingerprints.putIfAbsent(fingerprint, downloadId)
        if (existingId != null) {
            DiagnosticLogger.i("Downloader", "تم تجاهل تنزيل مكرر لنفس فيديو/جودة YouTube: $existingId")
            return existingId
        }
        downloadHeaders[downloadId] = MediaSniffer.capturedHeaders[cleanDownloadUrl]
            ?: MediaSniffer.capturedHeaders[url]
            ?: emptyMap()
        val cleanName = sanitizeFileName(title)
        val ext = when {
            mimeType.contains("subrip", true) || mimeType.contains("srt", true) -> ".srt"
            mimeType.contains("vtt", true) || mimeType.contains("webvtt", true) -> ".vtt"
            mimeType.contains("webm") -> ".webm"
            mimeType.contains("audio") || mimeType.contains("mp3") || quality.contains("صوت") -> ".mp3"
            mimeType.contains("mpegurl") || mimeType.contains("m3u8") -> ".mp4"
            mimeType.startsWith("video/") -> ".mp4"
            else -> extensionFromUrl(url, mimeType)
        }
        val fileName = if (cleanName.lowercase().endsWith(ext.lowercase())) cleanName else "$cleanName$ext"

        val targetDir = getTargetDirectory(context, destination)
        val targetFile = File(targetDir, fileName)
        val targetPath = targetFile.absolutePath

        var subLocalPath: String? = null
        if (!subtitleUrl.isNullOrBlank()) {
            val subExt = if (subtitleUrl.endsWith(".srt")) ".srt" else ".vtt"
            subLocalPath = File(targetDir, "$cleanName$subExt").absolutePath
        }

        val entity = DownloadEntity(
            id = downloadId,
            url = cleanDownloadUrl,
            title = title,
            fileName = fileName,
            localPath = targetPath,
            subtitleUrl = subtitleUrl,
            subtitlePath = subLocalPath,
            mimeType = mimeType,
            quality = quality,
            totalBytes = 0L,
            downloadedBytes = 0L,
            status = "DOWNLOADING",
            destinationType = destination.name,
            pageUrl = pageUrl,
            createdAt = System.currentTimeMillis()
        )

        managerScope.launch {
            try {
            val appContext = context.applicationContext
            val db = AppDatabase.getInstance(appContext)
            val remoteName = uniqueFileName(
                targetDir,
                resolveRemoteFileName(cleanDownloadUrl, title, mimeType, pageUrl)
            )
            val resolvedSubPath = if (!subtitleUrl.isNullOrBlank()) {
                File(targetDir, "${remoteName.substringBeforeLast('.', remoteName)}${if (subtitleUrl.endsWith(".srt", true)) ".srt" else ".vtt"}").absolutePath
            } else null
            val effectiveEntity = entity.copy(
                fileName = remoteName,
                localPath = File(targetDir, remoteName).absolutePath,
                // Subtitle downloads are explicit and separate from the video job.
                subtitleUrl = null,
                subtitlePath = null
            )
            db.downloadDao().insertOrUpdate(effectiveEntity)
            postDownloadNotification(appContext, effectiveEntity, 0L, 0L, "بدء التنزيل", ongoing = true)
            DiagnosticLogger.i("Downloader", "بدء تنزيل: '$title' بجودة $quality إلى: $targetPath")

            enqueueDownloadWork(appContext, effectiveEntity.id)
            // Subtitle fetching must never delay the video start. The logs showed
            // the subtitle request blocking the actual video download for ~30s.
            if (!subtitleUrl.isNullOrBlank() && resolvedSubPath != null) {
                managerScope.launch {
                    downloadSubtitleTrack(subtitleUrl, resolvedSubPath)
                }
            }
            } catch (e: Exception) {
                inFlightFingerprints.remove(fingerprint, downloadId)
                downloadHeaders.remove(downloadId)
                DiagnosticLogger.e("Downloader", "تعذر تجهيز التنزيل: ${e.message}", e)
                runCatching { AppDatabase.getInstance(context.applicationContext).downloadDao().updateStatus(downloadId, "FAILED", e.localizedMessage ?: "تعذر بدء التنزيل") }
            }
        }

        return downloadId
    }

    fun pauseDownload(context: Context, downloadId: String) {
        val job = activeJobs.remove(downloadId)
        job?.cancel()
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(downloadId))
        managerScope.launch {
            val db = AppDatabase.getInstance(context)
            db.downloadDao().updateStatus(downloadId, "PAUSED", null)
            DiagnosticLogger.i("Downloader", "تم إيقاف التنزيل مؤقتاً: $downloadId")
        }
    }

    fun resumeDownload(context: Context, downloadId: String) {
        managerScope.launch {
            val db = AppDatabase.getInstance(context)
            val entity = db.downloadDao().getDownloadById(downloadId) ?: return@launch
            if (activeJobs.containsKey(downloadId)) {
                DiagnosticLogger.d("Downloader", "تم تجاهل استئناف مكرر لمهمة نشطة: $downloadId")
                return@launch
            }
            DiagnosticLogger.i("Downloader", "استئناف التنزيل من الموضع المحفوظ: ${entity.downloadedBytes} بايت")
            db.downloadDao().updateStatus(downloadId, "DOWNLOADING", null)
            enqueueDownloadWork(context, downloadId)
        }
    }

    fun cancelDownload(context: Context, downloadId: String, deleteFile: Boolean = true) {
        val job = activeJobs.remove(downloadId)
        job?.cancel()
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(downloadId))
        managerScope.launch {
            val db = AppDatabase.getInstance(context)
            val entity = db.downloadDao().getDownloadById(downloadId)
            inFlightFingerprints.entries.removeIf { it.value == downloadId }
            downloadHeaders.remove(downloadId)
            if (entity != null) {
                if (deleteFile) {
                    deleteStoredFile(context, entity.localPath)
                    entity.subtitlePath?.let { deleteStoredFile(context, it) }
                }
                db.downloadDao().deleteById(downloadId)
                DiagnosticLogger.w("Downloader", "تم إلغاء التنزيل وحذف البيانات: $downloadId")
            }
        }
    }

    /** Moves a completed file without changing its real filename or MIME type. */
    fun moveDownload(context: Context, downloadId: String, destination: StorageDestination) {
        managerScope.launch {
            val db = AppDatabase.getInstance(context)
            val entity = db.downloadDao().getDownloadById(downloadId) ?: return@launch
            if (entity.status != "COMPLETED" || entity.destinationType == destination.name) return@launch
            val stagingFile = File(getTargetDirectory(context, destination), uniqueFileName(getTargetDirectory(context, destination), entity.fileName))
            try {
                copyStoredFile(context, entity.localPath, stagingFile)
                val visiblePath = if (destination == StorageDestination.PUBLIC_DOWNLOADS) {
                    publishToPublicDownloads(context, stagingFile, stagingFile.name, entity.mimeType)
                        ?: throw IllegalStateException("تعذر نشر الملف في مجلد التنزيلات")
                } else stagingFile.absolutePath
                deleteStoredFile(context, entity.localPath)
                db.downloadDao().insertOrUpdate(entity.copy(localPath = visiblePath, destinationType = destination.name))
                DiagnosticLogger.s("FileManager", "تم نقل الملف '${entity.fileName}' إلى ${destination.name}")
            } catch (e: Exception) {
                stagingFile.delete()
                DiagnosticLogger.e("FileManager", "فشل نقل الملف '${entity.fileName}': ${e.message}", e)
            }
        }
    }

    suspend fun runDownloadWork(context: Context, entity: DownloadEntity) {
        val job = synchronized(this) { executeDownloadJob(context, entity, resume = true) }
        try {
            job?.join()
        } finally {
            if (!coroutineContext.isActive) job?.cancelAndJoin()
        }
    }

    @Synchronized
    private fun executeDownloadJob(context: Context, entity: DownloadEntity, resume: Boolean): Job? {
        if (activeJobs.containsKey(entity.id)) {
            DiagnosticLogger.d("Downloader", "المهمة قيد التشغيل بالفعل: ${entity.id}")
            return activeJobs[entity.id]
        }
        val db = AppDatabase.getInstance(context)
        val job = managerScope.launch {
            var raf: RandomAccessFile? = null
            var lastNotificationAt = 0L
            try {
                val targetFile = File(entity.localPath)
                targetFile.parentFile?.mkdirs()

                // Local HLS playlist path: parse variants and concatenate permitted segments.
                if (entity.url.contains(".m3u8", true) || entity.mimeType.contains("mpegurl", true) || entity.mimeType.contains("m3u8", true)) {
                    downloadHlsStream(context, entity, targetFile, db)
                    return@launch
                }

                // If URL is a YouTube / GoogleVideo stream, use dedicated Chunked YouTube Downloader
                val isYouTube = entity.url.contains("googlevideo.com") || entity.url.contains("youtube.com") ||
                               (entity.pageUrl?.contains("youtube.com") == true)
                if (isYouTube) {
                    downloadYouTubeChunkedStream(context, entity, targetFile, db, resume)
                    return@launch
                }

                var currentDownloadUrl = MediaSniffer.cleanRangeParams(entity.url)
                val videoId = MediaSniffer.extractYouTubeVideoId(entity.pageUrl ?: entity.url)

                var downloadedBytes = if (resume && targetFile.exists()) targetFile.length() else 0L
                if (!resume && targetFile.exists()) {
                    targetFile.delete()
                    downloadedBytes = 0L
                }

                val isYouTubeStream = currentDownloadUrl.contains("googlevideo.com") || currentDownloadUrl.contains("youtube.com") || videoId != null
                val savedHeaders = MediaSniffer.capturedHeaders[currentDownloadUrl] ?: emptyMap()
                val refererUrl = entity.pageUrl ?: savedHeaders["Referer"] ?: extractReferer(currentDownloadUrl)

                var response = fetchStreamWithRetry(
                    currentDownloadUrl,
                    refererUrl,
                    savedHeaders,
                    downloadedBytes,
                    isYouTubeStream
                )

                // If YouTube stream response failed or link expired, automatically re-extract via on-device YouTubeExtractor
                var isInvalidResponse = response == null || !response.isSuccessful ||
                                       (response.header("Content-Type")?.contains("text/html") == true)

                if (isInvalidResponse && isYouTubeStream) {
                    val targetId = videoId ?: YouTubeExtractor.extractVideoId(currentDownloadUrl) ?: YouTubeExtractor.extractVideoId(entity.pageUrl.orEmpty())
                    if (targetId != null) {
                        DiagnosticLogger.i("Downloader", "رابط يوتيوب غير صالح أو منتهي الصلاحية، تجديد الرابط تلقائياً عبر المحلل المحلي (On-Device)...")
                        val isAudio = entity.mimeType.contains("audio") || entity.quality.contains("صوت")
                        val fresh = YouTubeExtractor.resolveDirectStream(targetId, isAudio)
                        if (fresh != null) {
                            currentDownloadUrl = fresh.url
                            val newHeaders = mapOf(
                                "User-Agent" to YouTubeExtractor.getUserAgentForClient("ANDROID_VR"),
                                "Referer" to "https://www.youtube.com/watch?v=$targetId"
                            )
                            response?.close()
                            response = fetchStreamWithRetry(
                                currentDownloadUrl,
                                "https://www.youtube.com/watch?v=$targetId",
                                newHeaders,
                                downloadedBytes,
                                isYouTubeStream = true
                            )
                            isInvalidResponse = response == null || !response.isSuccessful ||
                                               (response.header("Content-Type")?.contains("text/html") == true)
                        }
                    }
                }

                if (isInvalidResponse || response == null || (!response.isSuccessful && response.code != 206)) {
                    val code = response?.code ?: 0
                    val msg = response?.message ?: "لا يمكن الاتصال بالخادم"
                    throw IllegalStateException("فشل الاتصال بمصدر الفيديو (كود: $code $msg)")
                }

                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                if (contentType.contains("mpegurl") || contentType.contains("application/x-mpegurl")) {
                    response.close()
                    val updatedEntity = entity.copy(url = currentDownloadUrl)
                    downloadHlsStream(context, updatedEntity, targetFile, db)
                    return@launch
                }

                val body = response.body ?: throw IllegalStateException("محتوى الاستجابة فارغ")
                val contentLen = body.contentLength()

                if (downloadedBytes > 0 && response.code == 200) {
                    // The server ignored the resume Range request. Never append a full
                    // response to an existing partial file; restart from byte zero.
                    downloadedBytes = 0L
                    RandomAccessFile(targetFile, "rw").use { it.setLength(0L) }
                }

                val contentRangeTotal = response.header("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?: 0L
                val totalBytes = when {
                    contentRangeTotal > 0L -> contentRangeTotal
                    contentLen > 0L && response.code == 206 -> downloadedBytes + contentLen
                    contentLen > 0L -> contentLen
                    entity.totalBytes > 0L -> entity.totalBytes
                    else -> 0L
                }

                db.downloadDao().updateStatus(entity.id, "DOWNLOADING", null)
                db.downloadDao().updateProgress(entity.id, downloadedBytes, totalBytes, 0L)

                raf = RandomAccessFile(targetFile, "rw")
                raf.seek(downloadedBytes)

                val inputStream = body.byteStream()
                val bufferSize = if (speedBoosterEnabled) 64 * 1024 else 32 * 1024
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                var lastSpeedCheckTime = System.currentTimeMillis()
                var bytesSinceLastCheck = 0L
                var currentSpeed = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!isActive) break

                    raf.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    bytesSinceLastCheck += bytesRead

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastSpeedCheckTime
                    if (elapsed >= 250 || bytesSinceLastCheck >= 128 * 1024) {
                        currentSpeed = if (elapsed > 0) (bytesSinceLastCheck * 1000) / elapsed else 0L
                        lastSpeedCheckTime = now
                        bytesSinceLastCheck = 0L

                        db.downloadDao().updateProgress(entity.id, downloadedBytes, totalBytes, currentSpeed)
                        val notificationNow = System.currentTimeMillis()
                        if (notificationNow - lastNotificationAt >= 750L) {
                            postDownloadNotification(context, entity, downloadedBytes, totalBytes, "جارٍ التنزيل", ongoing = true, speedBytes = currentSpeed)
                            lastNotificationAt = notificationNow
                        }
                    }
                }

                if (isActive) {
                    raf.close()
                    raf = null

                    if (downloadedBytes < 10 * 1024) {
                        targetFile.delete()
                        throw IllegalStateException("حجم الملف المستلم صغير جداً (${downloadedBytes} بايت).")
                    }

                    try {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(targetFile.absolutePath),
                            arrayOf(entity.mimeType),
                            null
                        )
                    } catch (_: Exception) {}

                    val visiblePath = if (entity.destinationType == StorageDestination.PUBLIC_DOWNLOADS.name) {
                        publishToPublicDownloads(context, targetFile, entity.fileName, entity.mimeType)
                            ?: targetFile.absolutePath
                    } else targetFile.absolutePath
                    val visibleSubtitlePath = entity.subtitlePath?.let { subtitlePath ->
                        val subtitleFile = File(subtitlePath)
                        if (entity.destinationType == StorageDestination.PUBLIC_DOWNLOADS.name && subtitleFile.exists()) {
                            publishToPublicDownloads(context, subtitleFile, subtitleFile.name, "text/vtt") ?: subtitlePath
                        } else subtitlePath
                    }

                    db.downloadDao().insertOrUpdate(
                        entity.copy(
                            localPath = visiblePath,
                            subtitlePath = visibleSubtitlePath,
                            downloadedBytes = downloadedBytes,
                            totalBytes = downloadedBytes,
                            status = "COMPLETED",
                            speedBytesPerSec = 0L,
                            completedAt = System.currentTimeMillis(),
                            errorReason = null
                        )
                    )
                    postDownloadNotification(context, entity, downloadedBytes, downloadedBytes, "اكتمل التنزيل", ongoing = false)
                    DiagnosticLogger.s("Downloader", "اكتمل التنزيل بنجاح: '${entity.title}' (${MediaSniffer.formatFileSize(downloadedBytes)})")
                }
            } catch (e: CancellationException) {
                DiagnosticLogger.d("Downloader", "تم إيقاف عملية التنزيل")
            } catch (e: Exception) {
                DiagnosticLogger.e("Downloader", "فشل تنزيل '${entity.title}': ${e.message}", e)
                db.downloadDao().updateStatus(entity.id, "FAILED", e.localizedMessage ?: "حدث خطأ في الاتصال بالخادم")
                postDownloadNotification(context, entity, 0L, 0L, "فشل التنزيل: ${e.localizedMessage ?: "خطأ غير معروف"}", ongoing = false)
            } finally {
                try { raf?.close() } catch (_: Exception) {}
                activeJobs.remove(entity.id)
                inFlightFingerprints.entries.removeIf { it.value == entity.id }
                downloadHeaders.remove(entity.id)
            }
        }
        activeJobs[entity.id] = job
        return job
    }

    private fun postDownloadNotification(
        context: Context,
        entity: DownloadEntity,
        downloaded: Long,
        total: Long,
        statusText: String,
        ongoing: Boolean,
        speedBytes: Long = 0L
    ) {
        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        val speedStr = if (speedBytes > 0L) " • ${MediaSniffer.formatFileSize(speedBytes)}/s" else ""
        val progressDetails = if (total > 0L) {
            "${MediaSniffer.formatFileSize(downloaded)} / ${MediaSniffer.formatFileSize(total)}$speedStr"
        } else {
            "${MediaSniffer.formatFileSize(downloaded)}$speedStr"
        }
        val contentSub = if (ongoing) progressDetails else statusText

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(entity.title)
            .setContentText(contentSub)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (ongoing && total > 0L) {
            builder.setProgress(100, percent, false)
        } else if (!ongoing && statusText.startsWith("اكتمل")) {
            builder.setProgress(100, 100, false)
        }

        try {
            NotificationManagerCompat.from(context)
                .notify(entity.id.hashCode() and 0x7fffffff, builder.build())
        } catch (e: SecurityException) {
            DiagnosticLogger.w("Downloader", "إذن الإشعارات غير متاح: ${e.message}")
        }
    }

    fun createForegroundInfo(context: Context, entity: DownloadEntity): androidx.work.ForegroundInfo {
        createNotificationChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(entity.title)
            .setContentText("جارٍ تجهيز التنزيل في الخلفية")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, 0, true)
            .build()
        val id = entity.id.hashCode() and 0x7fffffff
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.work.ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            androidx.work.ForegroundInfo(id, notification)
        }
    }

    private fun tryFetchStream(
        url: String,
        referer: String?,
        savedHeaders: Map<String, String>,
        downloadedBytes: Long,
        isYouTubeStream: Boolean
    ): okhttp3.Response? {
        val userAgentsToTry = if (isYouTubeStream) {
            listOfNotNull(
                savedHeaders["User-Agent"],
                "com.google.android.youtube/1.60.19 (Linux; U; Android 12; Quest 3)",
                "com.google.android.youtube/19.44.38 (Linux; U; Android 14; ar_SA)",
                "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X; ar_SA)",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36"
            ).distinct()
        } else {
            listOf(
                savedHeaders["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
            )
        }

        for (ua in userAgentsToTry) {
            try {
                val req = Request.Builder().url(url)
                req.header("User-Agent", ua)
                req.header("Accept", "*/*")
                req.header("Connection", "keep-alive")

                if (!referer.isNullOrBlank()) {
                    req.header("Referer", referer)
                }

                try {
                    val cookieManager = CookieManager.getInstance()
                    val cookie1 = cookieManager.getCookie(url)
                    val cookie2 = if (!referer.isNullOrBlank()) cookieManager.getCookie(referer) else null
                    val merged = listOfNotNull(savedHeaders["Cookie"], cookie1, cookie2).distinct().joinToString("; ")
                    if (merged.isNotBlank()) req.header("Cookie", merged)
                } catch (_: Exception) {}

                // Send an explicit range even at byte zero. Some media CDNs return
                // an error or a tiny manifest when the initial Range header is absent.
                req.header("Range", "bytes=$downloadedBytes-")

                val resp = okHttpClient.newCall(req.build()).execute()
                if (resp.isSuccessful || resp.code == 206) {
                    return resp
                }
                resp.close()
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun fetchStreamWithRetry(
        url: String,
        referer: String?,
        savedHeaders: Map<String, String>,
        downloadedBytes: Long,
        isYouTubeStream: Boolean
    ): okhttp3.Response? {
        var response: okhttp3.Response? = null
        repeat(3) { attempt ->
            response = tryFetchStream(url, referer, savedHeaders, downloadedBytes, isYouTubeStream)
            val current = response
            val invalid = current == null || !current.isSuccessful ||
                current.header("Content-Type")?.contains("text/html", true) == true
            if (!invalid) return response
            current?.close()
            response = null
            if (attempt < 2) delay((500L shl attempt).coerceAtMost(2_000L))
        }
        return response
    }

    /** Downloads permitted, unencrypted MPEG-TS HLS streams conservatively. */
    private suspend fun downloadHlsStream(
        context: Context,
        entity: DownloadEntity,
        targetFile: File,
        db: AppDatabase
    ) = withContext(Dispatchers.IO) {
        DiagnosticLogger.i("Downloader", "بدء تنزيل تدفق HLS M3U8: '${entity.title}'")
        val baseUrl = entity.url
        val sessionHeaders = downloadHeaders[entity.id]
            ?: MediaSniffer.capturedHeaders[baseUrl]
            ?: emptyMap()

        val req = Request.Builder()
            .url(baseUrl)
            .header("User-Agent", sessionHeaders["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
            .apply {
                sessionHeaders["Cookie"]?.let { header("Cookie", it) }
                sessionHeaders["Referer"]?.let { header("Referer", it) }
                    ?: entity.pageUrl?.let { header("Referer", it) }
            }
            .build()

        val resp = okHttpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw IllegalStateException("فشل جلب قائمة تشغيل HLS: ${resp.code}")
        }

        val playlistContent = resp.body?.string() ?: throw IllegalStateException("قائمة التشغيل فارغة")
        if (playlistContent.contains("#EXT-X-KEY", ignoreCase = true)) {
            throw IllegalStateException("تدفق HLS مشفر؛ لا يمكن تنزيله دون مسار فك تشفير مسموح")
        }
        // fMP4 playlists are supported by writing the initialization map before
        // media fragments. Byte-range playlists still require a range-aware parser.
        if (playlistContent.contains("#EXT-X-BYTERANGE", ignoreCase = true)) {
            throw IllegalStateException("قائمة HLS تستخدم byte-range غير مدعوم بأمان")
        }
        var mediaPlaylistUrl = baseUrl
        var mediaPlaylistContent = playlistContent

        // Check if Master playlist with multiple variant streams
        if (playlistContent.contains("#EXT-X-STREAM-INF")) {
            val lines = playlistContent.lines()
            var selectedVariant: String? = null
            for (i in lines.indices) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.size) {
                    selectedVariant = lines[i + 1].trim()
                    if (lines[i].contains("RESOLUTION=") || lines[i].contains("BANDWIDTH=")) {
                        break
                    }
                }
            }
            if (selectedVariant != null) {
                mediaPlaylistUrl = resolveUrl(baseUrl, selectedVariant)
                val variantResp = okHttpClient.newCall(Request.Builder().url(mediaPlaylistUrl).build()).execute()
                if (variantResp.isSuccessful) {
                    mediaPlaylistContent = variantResp.body?.string() ?: playlistContent
                }
            }
        }

        // Extract optional fMP4 initialization map and media segment URLs.
        var initSegmentUrl: String? = null
        val mapLine = mediaPlaylistContent.lines().firstOrNull { it.startsWith("#EXT-X-MAP:", true) }
        if (mapLine != null) {
            val uriValue = Regex("URI=\"([^\"]+)\"").find(mapLine)?.groupValues?.getOrNull(1)
            if (!uriValue.isNullOrBlank()) initSegmentUrl = resolveUrl(mediaPlaylistUrl, uriValue)
        }
        val segmentUrls = mutableListOf<String>()
        val lines = mediaPlaylistContent.lines()
        if (mediaPlaylistContent.contains("#EXT-X-KEY", ignoreCase = true) ||
            mediaPlaylistContent.contains("#EXT-X-BYTERANGE", ignoreCase = true)) {
            throw IllegalStateException("صيغة HLS مشفرة أو byte-range وغير مدعومة")
        }
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                segmentUrls.add(resolveUrl(mediaPlaylistUrl, trimmed))
            }
        }

        if (segmentUrls.isEmpty()) {
            throw IllegalStateException("لم يتم العثور على أجزاء فيديو داخل قائمة M3U8")
        }

        DiagnosticLogger.i("Downloader", "تم العثور على ${segmentUrls.size} مقطعاً لتجميع الفيديو")

        var totalDownloadedBytes = 0L
        val totalSegments = segmentUrls.size
        // Segment sizes vary; keep the total unknown unless the caller supplied a confirmed value.
        val confirmedTotalBytes = entity.totalBytes.takeIf { it > 0L } ?: 0L
        var lastNotificationAt = 0L
        targetFile.delete()

        FileOutputStream(targetFile, true).use { output ->
            var lastSpeedCheck = System.currentTimeMillis()
            var bytesSinceSpeedCheck = 0L

            if (initSegmentUrl != null) {
                val initReq = Request.Builder()
                    .url(initSegmentUrl!!)
                    .header("User-Agent", sessionHeaders["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 14; Mobile)")
                    .apply {
                        sessionHeaders["Cookie"]?.let { header("Cookie", it) }
                        sessionHeaders["Referer"]?.let { header("Referer", it) }
                    }
                    .build()
                okHttpClient.newCall(initReq).execute().use { initResp ->
                    if (!initResp.isSuccessful) throw IllegalStateException("فشل تنزيل تهيئة HLS: ${initResp.code}")
                    val initBytes = initResp.body?.bytes() ?: ByteArray(0)
                    if (initBytes.isEmpty()) throw IllegalStateException("تهيئة HLS فارغة")
                    output.write(initBytes)
                    totalDownloadedBytes += initBytes.size
                }
            }

            for ((index, segUrl) in segmentUrls.withIndex()) {
                if (!coroutineContext.isActive) break

                val segReq = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", sessionHeaders["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                    .apply {
                        sessionHeaders["Cookie"]?.let { header("Cookie", it) }
                        sessionHeaders["Referer"]?.let { header("Referer", it) }
                            ?: entity.pageUrl?.let { header("Referer", it) }
                    }
                    .build()

                val segResp = okHttpClient.newCall(segReq).execute()
                if (!segResp.isSuccessful) {
                    val code = segResp.code
                    segResp.close()
                    throw IllegalStateException("فشل تنزيل مقطع HLS ${index + 1}/${segmentUrls.size}: $code")
                }
                segResp.use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) throw IllegalStateException("مقطع HLS فارغ: ${index + 1}")
                    output.write(bytes)
                    totalDownloadedBytes += bytes.size
                    bytesSinceSpeedCheck += bytes.size
                    // Do not infer a total from one segment. HLS segment durations
                    // and byte sizes vary substantially.

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastSpeedCheck
                    var speed = 0L
                    if (elapsed >= 400) {
                        speed = (bytesSinceSpeedCheck * 1000) / elapsed
                        lastSpeedCheck = now
                        bytesSinceSpeedCheck = 0L
                    }

                    db.downloadDao().updateProgress(entity.id, totalDownloadedBytes, confirmedTotalBytes, speed)
                    val notificationNow = System.currentTimeMillis()
                    if (notificationNow - lastNotificationAt >= 750L) {
                        postDownloadNotification(context, entity, totalDownloadedBytes, confirmedTotalBytes, "جارٍ تنزيل البث", ongoing = true)
                        lastNotificationAt = notificationNow
                    }
                }
            }
        }

        if (coroutineContext.isActive && totalDownloadedBytes > 10 * 1024) {
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            } catch (_: Exception) {}

            val visiblePath = if (entity.destinationType == StorageDestination.PUBLIC_DOWNLOADS.name) {
                publishToPublicDownloads(context, targetFile, entity.fileName, "video/mp4")
                    ?: targetFile.absolutePath
            } else targetFile.absolutePath
            db.downloadDao().insertOrUpdate(
                entity.copy(
                    localPath = visiblePath,
                    downloadedBytes = totalDownloadedBytes,
                    totalBytes = totalDownloadedBytes,
                    status = "COMPLETED",
                    speedBytesPerSec = 0L,
                    completedAt = System.currentTimeMillis(),
                    errorReason = null
                )
            )
            postDownloadNotification(context, entity, totalDownloadedBytes, totalDownloadedBytes, "اكتمل التنزيل", ongoing = false)
            DiagnosticLogger.s("Downloader", "اكتمل تنزيل ودمج أجزاء الفيديو بنجاح (${MediaSniffer.formatFileSize(totalDownloadedBytes)})")
        }
    }

    /**
     * Dedicated YouTube & GoogleVideo Stream Downloader.
     * Streams video directly with Range headers, and auto-resolves fresh proxy streams if expired or blocked.
     */
    private suspend fun downloadYouTubeChunkedStream(
        context: Context,
        entity: DownloadEntity,
        targetFile: File,
        db: AppDatabase,
        resume: Boolean
    ) = withContext(Dispatchers.IO) {
        DiagnosticLogger.i("Downloader", "بدء تنزيل مقطع يوتيوب: '${entity.title}'...")
        var currentUrl = entity.url
        val videoId = MediaSniffer.extractYouTubeVideoId(entity.pageUrl ?: currentUrl)
            ?: YouTubeExtractor.extractVideoId(currentUrl)
            ?: YouTubeExtractor.extractVideoId(entity.pageUrl.orEmpty())

        // Prefer the latest headers captured from the real WebView media request.
        val savedHeaders = MediaSniffer.capturedHeaders[currentUrl]
            ?: MediaSniffer.capturedHeaders[MediaSniffer.cleanRangeParams(currentUrl)]
            ?: downloadHeaders[entity.id]?.takeIf { it.isNotEmpty() }
            ?: emptyMap()

        val webUserAgent = savedHeaders["User-Agent"]
            ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        val referer = savedHeaders["Referer"] ?: entity.pageUrl ?: "https://www.youtube.com/"
        val cookies = savedHeaders["Cookie"] ?: runCatching {
            CookieManager.getInstance().getCookie("https://www.youtube.com")
                ?: CookieManager.getInstance().getCookie("https://m.youtube.com")
        }.getOrNull().orEmpty()
        DiagnosticLogger.d("Downloader", "رؤوس تنزيل YouTube: UA=${webUserAgent.take(42)}..., Cookie=${if (cookies.isBlank()) "غير موجودة" else "موجودة"}, Referer=$referer")

        var downloadedBytes = if (resume && targetFile.exists()) targetFile.length() else 0L
        if (!resume && targetFile.exists()) {
            targetFile.delete()
            downloadedBytes = 0L
        }

        var totalBytes = entity.totalBytes.takeIf { it > 0L }
            ?: runCatching { Uri.parse(currentUrl).getQueryParameter("clen")?.toLongOrNull() }.getOrNull()
            ?: 0L

        db.downloadDao().updateStatus(entity.id, "DOWNLOADING", null)
        // Keep an unknown size unknown; displaying a fabricated 15 MB total makes
        // the UI look stuck and causes misleading progress reports.
        db.downloadDao().updateProgress(entity.id, downloadedBytes, totalBytes, 0L)

        val isAudio = entity.mimeType.contains("audio") || entity.quality.contains("صوت")

        // Helper function to attempt streaming from a given URL
        suspend fun tryStreamUrl(urlToTry: String, attemptHeaders: Map<String, String> = savedHeaders): Boolean {
            var raf: RandomAccessFile? = null
            try {
                val attemptUserAgent = attemptHeaders["User-Agent"] ?: webUserAgent
                val attemptReferer = attemptHeaders["Referer"] ?: referer
                val attemptCookies = attemptHeaders["Cookie"] ?: cookies
                val reqBuilder = Request.Builder()
                    .url(urlToTry)
                    .header("User-Agent", attemptUserAgent)
                    .header("Accept", "*/*")
                    .header("Connection", "keep-alive")

                if (!urlToTry.contains("flokinet") && !urlToTry.contains("invidious")) {
                    // Media requests made by WebView do not carry a page Origin.
                    // Adding it to googlevideo requests can turn a valid signed URL into 403.
                    reqBuilder.header("Referer", attemptReferer)
                    if (attemptCookies.isNotBlank()) reqBuilder.header("Cookie", attemptCookies)
                }

                // Always request the byte range, including the initial bytes=0-
                // request. This makes the response semantics explicit.
                reqBuilder.header("Range", "bytes=$downloadedBytes-")

                val resp = okHttpClient.newCall(reqBuilder.build()).execute()
                val responseType = resp.header("Content-Type").orEmpty()
                val responseLength = resp.header("Content-Length") ?: "غير محدد"
                DiagnosticLogger.d("Downloader", "استجابة رابط YouTube: HTTP ${resp.code}, type=$responseType, length=$responseLength, range=${resp.header("Content-Range") ?: "غير محدد"}")
                if (!resp.isSuccessful && resp.code != 206) {
                    resp.close()
                    return false
                }

                val body = resp.body ?: return false
                if (downloadedBytes > 0L && resp.code == 200) {
                    // A server that ignores Range must not be appended to a partial file.
                    downloadedBytes = 0L
                    RandomAccessFile(targetFile, "rw").use { it.setLength(0L) }
                }
                val contentRangeTotal = resp.header("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?: 0L
                val contentLen = body.contentLength()
                if (contentRangeTotal > 0L) {
                    totalBytes = contentRangeTotal
                } else if (resp.code == 200 && contentLen > 0L) {
                    totalBytes = contentLen
                } else if (totalBytes <= 0L) {
                    // A 206 response without Content-Range is only an unknown fragment.
                    // Never promote it to a completed video.
                    resp.close()
                    return false
                }

                raf = RandomAccessFile(targetFile, "rw")
                raf.seek(downloadedBytes)

                val inputStream = body.byteStream()
                val bufferSize = if (speedBoosterEnabled) 64 * 1024 else 32 * 1024
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                var lastSpeedCheckTime = System.currentTimeMillis()
                var bytesSinceLastCheck = 0L
                var currentSpeed = 0L
                var lastNotificationAt = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!isActive) break
                    raf.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    bytesSinceLastCheck += bytesRead

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastSpeedCheckTime
                    if (elapsed >= 300 || bytesSinceLastCheck >= 128 * 1024) {
                        currentSpeed = if (elapsed > 0) (bytesSinceLastCheck * 1000) / elapsed else 0L
                        lastSpeedCheckTime = now
                        bytesSinceLastCheck = 0L

                        db.downloadDao().updateProgress(entity.id, downloadedBytes, totalBytes, currentSpeed)
                        if (now - lastNotificationAt >= 800L) {
                            postDownloadNotification(
                                context,
                                entity,
                                downloadedBytes,
                                totalBytes,
                                "جارٍ التنزيل",
                                ongoing = true,
                                speedBytes = currentSpeed
                            )
                            lastNotificationAt = now
                        }
                    }
                }

                raf.close()
                raf = null
                resp.close()
                return totalBytes > 0L && downloadedBytes >= totalBytes
            } catch (e: Exception) {
                DiagnosticLogger.d("Downloader", "خطأ أثناء الدفق من الرابط: ${e.message}")
                return false
            } finally {
                runCatching { raf?.close() }
            }
        }

        // 1. Primary path: stream the exact URL captured by WebView with its live headers.
        DiagnosticLogger.i("Downloader", "محاولة رابط WebView المباشر أولاً${if (savedHeaders.isNotEmpty()) " مع الرؤوس المحفوظة" else " بدون رؤوس محفوظة"}")
        var success = tryStreamUrl(currentUrl, savedHeaders)

        // 2. Only after the direct WebView URL fails, resolve a fresh fallback stream.
        if (!success && isActive && videoId != null) {
            DiagnosticLogger.i("Downloader", "تجديد رابط البث تلقائياً عبر المحلل الموزع...")
            val resolved = YouTubeExtractor.resolveDirectStream(videoId, isAudio)
            if (resolved != null && resolved.url.isNotBlank()) {
                currentUrl = resolved.url
                val freshHeaders = mapOf(
                    "User-Agent" to YouTubeExtractor.getUserAgentForClient("ANDROID_VR"),
                    "Referer" to "https://www.youtube.com/watch?v=$videoId"
                )
                success = tryStreamUrl(currentUrl, freshHeaders)
            }
        }

        if (!success || downloadedBytes < 15 * 1024) {
            targetFile.delete()
            throw IllegalStateException("تعذر إكمال تنزيل الفيديو بعد المحاولة من جميع المنافذ.")
        }

        // Publish file
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(entity.mimeType),
                null
            )
        } catch (_: Exception) {}

        val visiblePath = if (entity.destinationType == StorageDestination.PUBLIC_DOWNLOADS.name) {
            publishToPublicDownloads(context, targetFile, entity.fileName, entity.mimeType) ?: targetFile.absolutePath
        } else targetFile.absolutePath

        val visibleSubtitlePath = entity.subtitlePath?.let { subtitlePath ->
            val subtitleFile = File(subtitlePath)
            if (entity.destinationType == StorageDestination.PUBLIC_DOWNLOADS.name && subtitleFile.exists()) {
                publishToPublicDownloads(context, subtitleFile, subtitleFile.name, "text/vtt") ?: subtitlePath
            } else subtitlePath
        }

        db.downloadDao().insertOrUpdate(
            entity.copy(
                localPath = visiblePath,
                subtitlePath = visibleSubtitlePath,
                downloadedBytes = downloadedBytes,
                totalBytes = downloadedBytes,
                status = "COMPLETED",
                speedBytesPerSec = 0L,
                completedAt = System.currentTimeMillis(),
                errorReason = null
            )
        )
        postDownloadNotification(context, entity, downloadedBytes, downloadedBytes, "اكتمل التنزيل", ongoing = false)
        DiagnosticLogger.s("Downloader", "اكتمل تنزيل يوتيوب بنجاح: '${entity.title}' (${MediaSniffer.formatFileSize(downloadedBytes)})")
    }

    private fun isValidMediaContainer(file: File, mimeType: String): Boolean {
        if (!file.exists() || file.length() < 32L) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(32)
                val count = input.read(header)
                if (count < 12) return false
                val isMp4 = header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
                val isWebm = header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                    header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
                val isMpegAudio = header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte()
                isMp4 || isWebm || (mimeType.startsWith("audio/") && isMpegAudio)
            }
        } catch (_: Exception) { false }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
                relativeUrl
            } else {
                val base = URL(baseUrl)
                URL(base, relativeUrl).toString()
            }
        } catch (_: Exception) {
            relativeUrl
        }
    }

    private fun extractReferer(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            if (host.contains("googlevideo.com") || host.contains("youtube.com")) {
                "https://www.youtube.com/"
            } else {
                "${uri.scheme}://$host/"
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun downloadSubtitleTrack(subUrl: String, localPath: String) = withContext(Dispatchers.IO) {
        try {
            val fullSubUrl = when {
                subUrl.startsWith("http://") || subUrl.startsWith("https://") -> subUrl
                subUrl.startsWith("/") -> "https://www.youtube.com$subUrl"
                else -> "https://www.youtube.com/$subUrl"
            }
            val req = Request.Builder().url(fullSubUrl).build()
            val resp = okHttpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(localPath).use { output ->
                        input.copyTo(output)
                    }
                }
                DiagnosticLogger.s("Downloader", "تم تنزيل ملف الترجمة المرفق بنجاح: $localPath")
            }
        } catch (e: Exception) {
            DiagnosticLogger.w("Downloader", "تعذر تنزيل ملف الترجمة المرفق: ${e.message}")
        }
    }

    private fun getTargetDirectory(context: Context, destination: StorageDestination): File {
        val baseDir = when (destination) {
            StorageDestination.INTERNAL_VAULT -> {
                File(context.filesDir, "vault_videos")
            }
            StorageDestination.PUBLIC_DOWNLOADS -> {
                val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (external != null) {
                    File(external, "OmniBrowser")
                } else {
                    File(context.filesDir, "downloads")
                }
            }
            StorageDestination.CUSTOM -> {
                if (!customDirectoryPath.isNullOrBlank()) {
                    File(customDirectoryPath!!)
                } else {
                    val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    external ?: File(context.filesDir, "downloads")
                }
            }
        }
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return baseDir
    }

    /**
     * Makes a completed download visible in the system Downloads app.
     * On Android 10+ MediaStore avoids storage permissions; older devices use
     * the public Downloads/OmniBrowser directory.
     */
    private fun publishToPublicDownloads(context: Context, source: File, displayName: String, mimeType: String): String? {
        if (!source.exists() || source.length() == 0L) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniBrowser")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(source).use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("تعذر فتح ملف Downloads")
                    context.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null)
                    source.delete()
                    uri.toString()
                } catch (e: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmniBrowser")
                    .apply { mkdirs() }
                val destination = File(dir, displayName)
                FileInputStream(source).use { input -> FileOutputStream(destination).use { output -> input.copyTo(output) } }
                MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf(mimeType), null)
                source.delete()
                destination.absolutePath
            }
        } catch (e: Exception) {
            DiagnosticLogger.w("Downloader", "تعذر نشر الملف في Downloads: ${e.message}")
            null
        }
    }

    private fun deleteStoredFile(context: Context, path: String) {
        try {
            if (path.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(path), null, null)
            } else File(path).delete()
        } catch (_: Exception) {}
    }

    private fun copyStoredFile(context: Context, source: String, destination: File) {
        destination.parentFile?.mkdirs()
        val input = if (source.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(source))
                ?: throw IllegalStateException("تعذر قراءة الملف المصدر")
        } else FileInputStream(File(source))
        input.use { stream ->
            FileOutputStream(destination).use { output -> stream.copyTo(output) }
        }
    }

    private fun uniqueFileName(directory: File, requestedName: String): String {
        val safe = sanitizeFileName(requestedName)
        if (!File(directory, safe).exists()) return safe
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 1
        var candidate: File
        do {
            candidate = File(directory, "$base ($index)$ext")
            index++
        } while (candidate.exists() && index < 10_000)
        return candidate.name
    }

    private fun downloadFingerprint(url: String, quality: String, mimeType: String, pageUrl: String?): String {
        val videoId = YouTubeExtractor.extractVideoId(pageUrl.orEmpty())
            ?: YouTubeExtractor.extractVideoId(url)
        if (videoId != null) {
            val itag = runCatching { Uri.parse(url).getQueryParameter("itag").orEmpty() }.getOrDefault("")
            val audio = mimeType.contains("audio", true) || quality.contains("صوت")
            val variant = if (itag.isNotBlank()) itag else quality.trim()
            return "youtube:$videoId:$variant:$audio"
        }
        return "url:${url.lowercase()}:$mimeType"
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace("+", " ")
            .trim()
        if (cleaned.isEmpty()) return "file_${System.currentTimeMillis()}"
        val dot = cleaned.lastIndexOf('.')
        val extension = if (dot > 0 && dot < cleaned.length - 1) cleaned.substring(dot) else ""
        val base = if (extension.isNotEmpty()) cleaned.substring(0, dot) else cleaned
        return base.take(150).trimEnd() + extension.take(12)
    }

    private fun extensionFromUrl(url: String, mimeType: String): String {
        val fromMime = when {
            mimeType.contains("pdf") -> ".pdf"
            mimeType.contains("zip") -> ".zip"
            mimeType.contains("x-7z") -> ".7z"
            mimeType.contains("json") -> ".json"
            mimeType.contains("text/") -> ".txt"
            mimeType.contains("apk") -> ".apk"
            else -> ""
        }
        if (fromMime.isNotBlank()) return fromMime
        val path = try { Uri.parse(url).path.orEmpty() } catch (_: Exception) { "" }
        val suffix = path.substringAfterLast('.', "").lowercase()
        return if (suffix.matches(Regex("[a-z0-9]{1,5}"))) ".${suffix}" else ".bin"
    }

    private suspend fun resolveRemoteFileName(
        url: String,
        title: String,
        mimeType: String,
        pageUrl: String?
    ): String = withContext(Dispatchers.IO) {
        val fallback = friendlyFallbackName(title, url, mimeType)
        try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                .apply { pageUrl?.let { header("Referer", it) } }

            // HEAD is cheap, but many file hosts omit Content-Disposition on HEAD.
            // Retry with a one-byte GET so APKs, ZIPs and documents keep their real name.
            val head = okHttpClient.newCall(requestBuilder.head().build()).execute()
            val headName = fileNameFromResponse(head)
            val headUrlName = fileNameFromUrl(head.request.url.toString())
            val headType = head.header("Content-Type").orEmpty()
            head.close()
            if (!headName.isNullOrBlank()) return@withContext chooseRemoteName(headName, fallback, mimeType, headType)
            if (!headUrlName.isNullOrBlank()) return@withContext chooseRemoteName(headUrlName, fallback, mimeType, headType)

            val probe = requestBuilder.header("Range", "bytes=0-0").get().build()
            okHttpClient.newCall(probe).execute().use { response ->
                val candidate = fileNameFromResponse(response)
                    ?: fileNameFromUrl(response.request.url.toString())
                if (!candidate.isNullOrBlank()) {
                    return@withContext chooseRemoteName(candidate, fallback, mimeType, response.header("Content-Type").orEmpty())
                }
                fallback
            }
        } catch (e: Exception) {
            DiagnosticLogger.d("Downloader", "تعذر قراءة اسم الملف من الخادم: ${e.message}")
            fallback
        }
    }

    private fun fileNameFromResponse(response: okhttp3.Response): String? {
        val value = response.header("Content-Disposition").orEmpty()
        return Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.getOrNull(1)
            ?.let { URLDecoder.decode(it.trim().trim('"'), StandardCharsets.UTF_8.name()) }
            ?: Regex("filename\\s*=\\s*\"?([^;\"]+)", RegexOption.IGNORE_CASE)
                .find(value)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun fileNameFromUrl(url: String): String? = try {
        Uri.parse(url).path?.substringAfterLast('/')?.takeIf { it.contains('.') && it.length < 240 }
    } catch (_: Exception) { null }

    private fun ensureExtension(name: String, requestedMime: String, responseMime: String): String {
        if (name.substringAfterLast('.', "").length in 1..8) return name
        return name + extensionFromUrl("", responseMime.ifBlank { requestedMime })
    }

    private fun chooseRemoteName(candidate: String, fallback: String, requestedMime: String, responseMime: String): String {
        val safe = ensureExtension(sanitizeFileName(candidate), requestedMime, responseMime)
        return if (isTechnicalName(safe)) fallback else safe
    }

    private fun friendlyFallbackName(title: String, url: String, mimeType: String): String {
        val raw = sanitizeFileName(title)
        val titleExt = raw.substringAfterLast('.', "").takeIf { it.length in 1..8 }
        val ext = titleExt?.let { ".${it.lowercase()}" } ?: extensionFromUrl(url, mimeType)
        val base = raw.removeSuffix(titleExt?.let { ".${it}" } ?: "")
        val name = if (base.isBlank() || isTechnicalName(raw)) "download" else base
        return if (name.lowercase().endsWith(ext.lowercase())) name else "$name$ext"
    }

    private fun isTechnicalName(name: String): Boolean {
        val base = name.substringBeforeLast('.')
        return base.length > 100 ||
            Regex("[0-9a-f]{24,}", RegexOption.IGNORE_CASE).matches(base) ||
            Regex("[a-f0-9]{8}-[a-f0-9-]{20,}", RegexOption.IGNORE_CASE).matches(base) ||
            (base.length >= 32 && base.matches(Regex("[A-Za-z0-9_-]+")))
    }
}
