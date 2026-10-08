package com.example.data.downloader

import com.example.data.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Resolves user-provided media URLs and web page links using the high-performance
 * Render Media Backend API (https://omnibrowser-media-api.onrender.com) and fallback extractors.
 */
data class ResolvedStream(
    val url: String,
    val quality: String,
    val mimeType: String = "video/mp4",
    val sizeBytes: Long = 0L,
    val isAudioOnly: Boolean = false,
    val isEstimatedSize: Boolean = false
)

object MediaResolver {
    const val DEFAULT_BACKEND_URL = "https://omnibrowser-media-api.onrender.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Resolves a media stream from any URL or web page.
     * Uses Render Backend API first for YouTube and video platforms, then on-device extractors.
     */
    suspend fun resolveDirectDownloadStream(source: String, isAudio: Boolean = false): ResolvedStream? =
        withContext(Dispatchers.IO) {
            val value = source.trim()
            val ytId = YouTubeExtractor.extractVideoId(value)
            if (ytId != null || (!value.startsWith("https://", true) && !value.startsWith("http://", true) && value.length in 10..12)) {
                val targetId = ytId ?: value
                DiagnosticLogger.i("MediaResolver", "تحويل رابط/معرف يوتيوب إلى المحلل المتقدم: $targetId")
                return@withContext YouTubeExtractor.resolveDirectStream(targetId, isAudio)
            }

            if (!value.startsWith("https://", true) && !value.startsWith("http://", true)) {
                DiagnosticLogger.w("MediaResolver", "الرابط ليس HTTP(S): $value")
                return@withContext null
            }

            // 1. Try resolving via the high-availability Render Backend API
            resolveViaBackend(value, isAudio)?.let {
                DiagnosticLogger.i("MediaResolver", "تم استخراج رابط الوسائط عبر الخادم الوسيط بنجاح: ${it.quality}")
                return@withContext it
            }

            // 2. Direct HTTP probe
            val request = Request.Builder()
                .url(value)
                .head()
                .header("Accept", "video/*,audio/*,application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type").orEmpty()
                        .substringBefore(';').trim().lowercase(Locale.US)
                    val finalUrl = response.request.url.toString()
                    val path = runCatching { response.request.url.encodedPath.lowercase(Locale.US) }.getOrDefault("")
                    val isHls = path.endsWith(".m3u8") || contentType.contains("mpegurl")
                    val isMedia = isMediaType(contentType) || isMediaPath(path) || isHls

                    if (response.isSuccessful && isMedia) {
                        val size = response.header("Content-Length")?.toLongOrNull() ?: 0L
                        val mime = when {
                            isHls -> "application/vnd.apple.mpegurl"
                            contentType.isNotBlank() && contentType != "application/octet-stream" -> contentType
                            else -> guessMimeType(finalUrl, isAudio)
                        }
                        val audio = isAudio || mime.startsWith("audio/")
                        return@withContext ResolvedStream(
                            url = finalUrl,
                            quality = if (isHls) "HLS مباشر" else if (audio) "صوت مباشر" else "فيديو مباشر",
                            mimeType = mime,
                            sizeBytes = size,
                            isAudioOnly = audio
                        )
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.w("MediaResolver", "تعذر فحص الرابط المباشر: ${e.message}")
            }

            DiagnosticLogger.w("MediaResolver", "الرابط لا يعلن عن ملف وسائط مباشر: $value")
            null
        }

    /**
     * Queries the Render backend API for media resolving.
     */
    private suspend fun resolveViaBackend(pageUrl: String, isAudio: Boolean): ResolvedStream? = withContext(Dispatchers.IO) {
        val encodedUrl = runCatching { URLEncoder.encode(pageUrl, "UTF-8") }.getOrDefault(pageUrl)
        val endpoints = listOf(
            "$DEFAULT_BACKEND_URL/resolve?url=$encodedUrl",
            "$DEFAULT_BACKEND_URL/extract?url=$encodedUrl",
            "$DEFAULT_BACKEND_URL/api/info?url=$encodedUrl"
        )

        for (endpoint in endpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json")
                    .header("User-Agent", "OmniBrowser/2.0")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            val json = JSONObject(body)
                            val streamUrl = json.optString("stream_url", "")
                                .ifBlank { json.optString("url", "") }
                                .ifBlank { json.optString("direct_url", "") }
                                .ifBlank { json.optString("download_url", "") }

                            if (streamUrl.isNotBlank() && (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))) {
                                val quality = json.optString("quality", if (isAudio) "صوت عالي الجودة" else "دقة عالية HD")
                                val mime = json.optString("mime_type", if (isAudio) "audio/mp4" else "video/mp4")
                                val size = json.optLong("size_bytes", 0L)
                                return@withContext ResolvedStream(
                                    url = streamUrl,
                                    quality = quality,
                                    mimeType = mime,
                                    sizeBytes = size,
                                    isAudioOnly = isAudio || mime.startsWith("audio/")
                                )
                            }

                            // Check formats array
                            val formats = json.optJSONArray("formats")
                            if (formats != null && formats.length() > 0) {
                                for (i in 0 until formats.length()) {
                                    val f = formats.optJSONObject(i) ?: continue
                                    val fUrl = f.optString("url", "")
                                    val isAud = f.optBoolean("is_audio", false) || f.optString("mime_type").startsWith("audio/")
                                    if (fUrl.isNotBlank() && (isAudio == isAud || !isAudio)) {
                                        return@withContext ResolvedStream(
                                            url = fUrl,
                                            quality = f.optString("quality", "دقة عالية"),
                                            mimeType = f.optString("mime_type", if (isAud) "audio/mp4" else "video/mp4"),
                                            sizeBytes = f.optLong("size_bytes", 0L),
                                            isAudioOnly = isAud
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.d("MediaResolver", "محاولة الاستعلام من السيرفر الوسيط: ${e.message}")
            }
        }
        null
    }

    private fun isMediaType(type: String): Boolean =
        type.startsWith("video/") || type.startsWith("audio/") ||
            type == "application/ogg" || type == "application/octet-stream"

    private fun isMediaPath(path: String): Boolean =
        listOf(".mp4", ".m4v", ".webm", ".mkv", ".mov", ".avi", ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg")
            .any(path::endsWith)

    private fun guessMimeType(url: String, audio: Boolean): String {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url).lowercase(Locale.US)
        return when {
            decoded.contains(".mp3") -> "audio/mpeg"
            decoded.contains(".m4a") -> "audio/mp4"
            decoded.contains(".aac") -> "audio/aac"
            decoded.contains(".wav") -> "audio/wav"
            decoded.contains(".webm") -> if (audio) "audio/webm" else "video/webm"
            decoded.contains(".m3u8") -> "application/vnd.apple.mpegurl"
            audio -> "audio/*"
            else -> "video/mp4"
        }
    }
}
