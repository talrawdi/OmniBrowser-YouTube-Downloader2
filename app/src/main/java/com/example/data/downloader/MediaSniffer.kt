package com.example.data.downloader

import android.net.Uri
import com.example.data.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SniffedSubtitle(
    val url: String,
    val label: String,
    val language: String = "ar",
    val format: String = "VTT" // VTT, SRT
)

data class SniffedQuality(
    val label: String, // 1080p, 720p, 480p, 360p, Audio Only
    val url: String,
    val sizeBytes: Long = 0L,
    val isAudioOnly: Boolean = false,
    val mimeType: String = "video/mp4",
    val isEstimatedSize: Boolean = false
)

data class SniffedMedia(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val originalUrl: String,
    val pageUrl: String,
    val mimeType: String,
    val estimatedSizeBytes: Long = 0L,
    val qualities: List<SniffedQuality> = emptyList(),
    val subtitles: List<SniffedSubtitle> = emptyList(),
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null
)

object MediaSniffer {
    private val _sniffedMediaList = MutableStateFlow<List<SniffedMedia>>(emptyList())
    val sniffedMediaList: StateFlow<List<SniffedMedia>> = _sniffedMediaList.asStateFlow()

    private val seenUrls = ConcurrentHashMap.newKeySet<String>()
    private val seenVideoIds = ConcurrentHashMap.newKeySet<String>()
    val capturedHeaders = ConcurrentHashMap<String, Map<String, String>>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val snifferScope = CoroutineScope(Dispatchers.IO)

    // Advanced JavaScript injection script for deep media and stream inspection
    val snifferInjectionScript: String = """
        (function() {
            function inspectPage() {
                try {
                    var pageTitle = document.title || '';
                    var ogTitle = document.querySelector('meta[property="og:title"]');
                    if (ogTitle && ogTitle.content) pageTitle = ogTitle.content;
                    var twitterTitle = document.querySelector('meta[name="twitter:title"]');
                    if (twitterTitle && twitterTitle.content && (!pageTitle || pageTitle === 'YouTube')) pageTitle = twitterTitle.content;
                    var h1 = document.querySelector('h1');
                    if ((!pageTitle || pageTitle === 'YouTube' || pageTitle === 'm.youtube.com') && h1 && h1.innerText) {
                        pageTitle = h1.innerText.trim();
                    }

                    // 1. YouTube Deep Extraction
                    if (window.location.hostname.indexOf('youtube.com') >= 0 || window.location.hostname.indexOf('youtu.be') >= 0) {
                        inspectYouTube(pageTitle);
                    }

                    // 2. Standard HTML5 Video elements & custom players
                    var videos = document.querySelectorAll('video');
                    videos.forEach(function(v) {
                        var src = v.currentSrc || v.src;
                        if (!src) {
                            var source = v.querySelector('source');
                            if (source) src = source.src || source.getAttribute('data-src') || source.getAttribute('src');
                        }
                        if (src && !src.startsWith('blob:') && !src.startsWith('data:')) {
                            var subs = [];
                            var tracks = v.querySelectorAll('track');
                            tracks.forEach(function(t) {
                                if (t.src) {
                                    subs.push({
                                        url: t.src,
                                        label: t.label || t.srclang || 'ترجمة',
                                        lang: t.srclang || 'ar'
                                    });
                                }
                            });

                            window.OmniBridge && window.OmniBridge.onMediaFound(
                                src,
                                pageTitle || 'فيديو مباشر',
                                v.videoWidth || 0,
                                v.videoHeight || 0,
                                Math.floor(v.duration || 0),
                                JSON.stringify(subs)
                            );
                        }
                    });

                    // 3. Audio elements
                    var audios = document.querySelectorAll('audio');
                    audios.forEach(function(a) {
                        var src = a.currentSrc || a.src;
                        if (src && !src.startsWith('blob:') && !src.startsWith('data:')) {
                            window.OmniBridge && window.OmniBridge.onMediaFound(
                                src,
                                pageTitle || 'مقطع صوتي',
                                0, 0, Math.floor(a.duration || 0), '[]'
                            );
                        }
                    });

                    // 4. OpenGraph and Twitter video tags
                    var ogVideo = document.querySelector('meta[property="og:video"]') ||
                                  document.querySelector('meta[property="og:video:url"]') ||
                                  document.querySelector('meta[property="og:video:secure_url"]') ||
                                  document.querySelector('meta[name="twitter:player:stream"]');
                    if (ogVideo && ogVideo.content && !ogVideo.content.startsWith('blob:') && !ogVideo.content.startsWith('data:')) {
                        window.OmniBridge && window.OmniBridge.onMediaFound(
                            ogVideo.content,
                            pageTitle || 'فيديو تم كشفه',
                            0, 0, 0, '[]'
                        );
                    }
                } catch(e) {}
            }

            function inspectYouTube(pageTitle) {
                try {
                    var pr = window.ytInitialPlayerResponse;
                    if (!pr && window.ytplayer && window.ytplayer.config && window.ytplayer.config.args) {
                        try { pr = JSON.parse(window.ytplayer.config.args.player_response); } catch(e){}
                    }
                    if (pr && pr.videoDetails) {
                        var ytTitle = pr.videoDetails.title || pageTitle;
                        var duration = parseInt(pr.videoDetails.lengthSeconds) || 0;
                        var thumb = '';
                        if (pr.videoDetails.thumbnail && pr.videoDetails.thumbnail.thumbnails && pr.videoDetails.thumbnail.thumbnails.length > 0) {
                            thumb = pr.videoDetails.thumbnail.thumbnails[pr.videoDetails.thumbnail.thumbnails.length - 1].url;
                        }

                        var formats = [];
                        if (pr.streamingData) {
                            if (pr.streamingData.formats) {
                                pr.streamingData.formats.forEach(function(f) {
                                    var fUrl = f.url;
                                    if (!fUrl && (f.signatureCipher || f.cipher)) {
                                        var rawCipher = f.signatureCipher || f.cipher;
                                        var params = new URLSearchParams(rawCipher);
                                        fUrl = params.get('url');
                                    }
                                    if (fUrl) {
                                        var cLen = parseInt(f.contentLength) || 0;
                                        if (cLen === 0 && f.bitrate && duration > 0) {
                                            cLen = Math.floor((parseInt(f.bitrate) * duration) / 8);
                                        }
                                        formats.push({
                                            quality: f.qualityLabel || (f.height ? f.height + 'p' : '720p HD'),
                                            url: fUrl,
                                            mimeType: f.mimeType || 'video/mp4',
                                            contentLength: cLen,
                                            isAudio: false
                                        });
                                    }
                                });
                            }
                            if (pr.streamingData.adaptiveFormats) {
                                pr.streamingData.adaptiveFormats.forEach(function(f) {
                                    var fUrl = f.url;
                                    if (!fUrl && (f.signatureCipher || f.cipher)) {
                                        var rawCipher = f.signatureCipher || f.cipher;
                                        var params = new URLSearchParams(rawCipher);
                                        fUrl = params.get('url');
                                    }
                                    if (fUrl) {
                                        var isAudio = (f.mimeType && f.mimeType.indexOf('audio') >= 0);
                                        var cLen = parseInt(f.contentLength) || 0;
                                        if (cLen === 0 && f.bitrate && duration > 0) {
                                            cLen = Math.floor((parseInt(f.bitrate) * duration) / 8);
                                        }
                                        formats.push({
                                            quality: isAudio ? 'صوت فقط MP3/M4A (عالي النقاء)' : (f.qualityLabel || (f.height ? f.height + 'p' : 'دقة عالية')),
                                            url: fUrl,
                                            mimeType: f.mimeType || (isAudio ? 'audio/mp4' : 'video/mp4'),
                                            contentLength: cLen,
                                            isAudio: isAudio
                                        });
                                    }
                                });
                            }
                        }

                        var subs = [];
                        if (pr.captions && pr.captions.playerCaptionsTracklistRenderer && pr.captions.playerCaptionsTracklistRenderer.captionTracks) {
                            pr.captions.playerCaptionsTracklistRenderer.captionTracks.forEach(function(c) {
                                subs.push({
                                    url: c.baseUrl,
                                    label: (c.name && c.name.simpleText) ? c.name.simpleText : (c.languageCode || 'ترجمة'),
                                    lang: c.languageCode || 'ar'
                                });
                            });
                        }

                        if (formats.length > 0) {
                            window.OmniBridge && window.OmniBridge.onYouTubeMediaFound(
                                ytTitle,
                                window.location.href,
                                thumb,
                                duration,
                                JSON.stringify(formats),
                                JSON.stringify(subs)
                            );
                        }
                    }
                } catch(e) {}
            }

            if (!window._omniSnifferInjected) {
                window._omniSnifferInjected = true;
                window.addEventListener('yt-navigate-finish', function() { inspectPage(); });
                window.addEventListener('popstate', function() { inspectPage(); });
                window.addEventListener('hashchange', function() { inspectPage(); });

                var observer = new MutationObserver(function() { inspectPage(); });
                observer.observe(document.documentElement, { childList: true, subtree: true });

                var origPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function() {
                    inspectPage();
                    return origPlay.apply(this, arguments);
                };
            }

            inspectPage();
            setTimeout(inspectPage, 500);
            setTimeout(inspectPage, 1500);
            setTimeout(inspectPage, 3000);
        })();
    """.trimIndent()

    fun resetForNewPage(pageUrl: String) {
        seenUrls.clear()
        seenVideoIds.clear()
        _sniffedMediaList.value = emptyList()
        DiagnosticLogger.d("MediaSniffer", "إعادة ضبط كاشف الوسائط للصفحة: $pageUrl")

        checkAndSniffYouTubePage(pageUrl)
    }

    fun checkAndSniffYouTubePage(pageUrl: String) {
        val videoId = extractYouTubeVideoId(pageUrl) ?: return
        if (seenVideoIds.contains(videoId)) return
        seenVideoIds.add(videoId)

        snifferScope.launch {
            try {
                DiagnosticLogger.i("MediaSniffer", "تم كشف رابط يوتيوب: $videoId - بدء استخراج خيارات الجودة وحساب الحجم")
                fetchYouTubeStreams(videoId, pageUrl)
            } catch (e: Exception) {
                DiagnosticLogger.w("MediaSniffer", "تعذر استخراج بيانات يوتيوب: ${e.message}")
            }
        }
    }

    fun triggerResniff(pageUrl: String) {
        val videoId = extractYouTubeVideoId(pageUrl)
        if (videoId != null) {
            snifferScope.launch {
                fetchYouTubeStreams(videoId, pageUrl)
            }
        }
    }

    private suspend fun fetchYouTubeStreams(videoId: String, pageUrl: String) {
        val ytInfo = YouTubeExtractor.extract(videoId)
        if (ytInfo != null && ytInfo.streams.isNotEmpty()) {
            val sniffedQualities = ytInfo.streams.map { s ->
                val ua = YouTubeExtractor.getUserAgentForClient(s.clientSource)
                capturedHeaders[s.url] = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "https://www.youtube.com/watch?v=$videoId"
                )
                SniffedQuality(
                    label = s.quality,
                    url = s.url,
                    sizeBytes = s.sizeBytes,
                    isAudioOnly = s.isAudioOnly,
                    mimeType = s.mimeType,
                    isEstimatedSize = s.isEstimatedSize
                )
            }
            val subtitles = ytInfo.subtitles.map { sub ->
                SniffedSubtitle(url = sub.url, label = sub.label, language = sub.languageCode, format = "VTT")
            }
            val primary = sniffedQualities.first()
            val media = SniffedMedia(
                title = ytInfo.title,
                originalUrl = primary.url,
                pageUrl = pageUrl,
                mimeType = primary.mimeType,
                estimatedSizeBytes = primary.sizeBytes,
                qualities = sniffedQualities,
                subtitles = subtitles,
                durationSeconds = ytInfo.durationSeconds,
                thumbnailUrl = ytInfo.thumbnailUrl
            )
            addOrUpdateMedia(media)
            DiagnosticLogger.s("MediaSniffer", "تم استخراج فيديو يوتيوب عبر YouTubeExtractor بنجاح: '${ytInfo.title}'")
            return
        }

        // Multi-client extraction strategy fallback:
        // 1. ANDROID_VR: Returns direct, un-throttled, progressive MP4 streams (360p, 720p) + audio streams
        // 2. IOS: High quality progressive and adaptive streams
        // 3. ANDROID: Standard Android client
        // 4. TVHTML5_SIMPLY_EMBEDDED_PLAYER: Clean web embed streams
        val clientConfigs = listOf(
            Triple("ANDROID_VR", "1.60.19", JSONObject().apply {
                put("clientName", "ANDROID_VR")
                put("clientVersion", "1.60.19")
                put("deviceModel", "Quest 3")
                put("osName", "Android")
                put("osVersion", "12")
                put("hl", "ar")
                put("gl", "SA")
            }),
            Triple("IOS", "19.45.4", JSONObject().apply {
                put("clientName", "IOS")
                put("clientVersion", "19.45.4")
                put("deviceModel", "iPhone16,2")
                put("osName", "iOS")
                put("osVersion", "18.1.0.22B83")
                put("hl", "ar")
                put("gl", "SA")
            }),
            Triple("ANDROID", "19.44.38", JSONObject().apply {
                put("clientName", "ANDROID")
                put("clientVersion", "19.44.38")
                put("androidSdkVersion", 34)
                put("osName", "Android")
                put("osVersion", "14")
                put("hl", "ar")
                put("gl", "SA")
            }),
            Triple("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", JSONObject().apply {
                put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                put("clientVersion", "2.0")
                put("clientScreen", "EMBED")
                put("hl", "ar")
                put("gl", "SA")
            })
        )

        for ((clientName, clientVersion, clientJson) in clientConfigs) {
            try {
                val url = "https://www.youtube.com/youtubei/v1/player"
                val jsonBody = JSONObject().apply {
                    put("videoId", videoId)
                    val context = JSONObject().apply {
                        put("client", clientJson)
                        if (clientName.contains("TVHTML5")) {
                            put("thirdParty", JSONObject().apply {
                                put("embedUrl", "https://www.youtube.com")
                            })
                        }
                    }
                    put("context", context)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("User-Agent", "com.google.android.youtube/$clientVersion (Linux; U; Android 14; ar_SA)")
                    .header("X-YouTube-Client-Name", if (clientName == "ANDROID" || clientName == "ANDROID_VR") "3" else "1")
                    .header("X-YouTube-Client-Version", clientVersion)
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/watch?v=$videoId")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) continue

                val responseText = response.body?.string() ?: continue
                val json = JSONObject(responseText)
                val videoDetails = json.optJSONObject("videoDetails") ?: continue
                val streamingData = json.optJSONObject("streamingData") ?: continue

                val rawTitle = videoDetails.optString("title", "فيديو يوتيوب")
                val cleanTitle = rawTitle.replace("+", " ").trim()
                val durationSeconds = videoDetails.optLong("lengthSeconds", 0L).let {
                    if (it > 0) it else videoDetails.optString("lengthSeconds").toLongOrNull() ?: 0L
                }
                var thumbUrl: String? = null
                videoDetails.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { arr ->
                    if (arr.length() > 0) {
                        thumbUrl = arr.getJSONObject(arr.length() - 1).optString("url")
                    }
                }

                val qualitiesList = mutableListOf<SniffedQuality>()

                // 1. Progressive Formats (Audio + Video combined in MP4)
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.getJSONObject(i)
                        var streamUrl = f.optString("url")
                        if (streamUrl.isBlank() && (f.has("signatureCipher") || f.has("cipher"))) {
                            streamUrl = extractUrlFromCipher(f.optString("signatureCipher", f.optString("cipher")))
                        }

                        if (streamUrl.isNotBlank()) {
                            val qualityLabel = f.optString("qualityLabel").ifBlank {
                                val h = f.optInt("height", 0)
                                if (h > 0) "${h}p HD" else "720p HD"
                            }
                            val cleanUrl = cleanRangeParams(streamUrl)
                            val rawContentLength = f.optString("contentLength")
                            val parsedSize = rawContentLength.toLongOrNull() ?: f.optLong("contentLength", 0L)
                            val bitrate = f.optString("bitrate").toLongOrNull() ?: f.optLong("bitrate", 0L)

                            val finalSize = when {
                                parsedSize > 0L -> parsedSize
                                bitrate > 0L && durationSeconds > 0L -> (bitrate * durationSeconds) / 8
                                else -> calculateEstimatedSize(f.optInt("height", 720), durationSeconds, isAudio = false)
                            }

                            val mime = f.optString("mimeType", "video/mp4")
                            qualitiesList.add(
                                SniffedQuality(
                                    label = "$qualityLabel (فيديو وصوت كامل MP4)",
                                    url = cleanUrl,
                                    sizeBytes = finalSize,
                                    mimeType = mime,
                                    isEstimatedSize = (parsedSize == 0L)
                                )
                            )
                        }
                    }
                }

                // 2. Adaptive Formats (High resolutions: 1080p, 480p, Audio MP3/M4A)
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                if (adaptiveFormats != null) {
                    for (i in 0 until adaptiveFormats.length()) {
                        val f = adaptiveFormats.getJSONObject(i)
                        var streamUrl = f.optString("url")
                        if (streamUrl.isBlank() && (f.has("signatureCipher") || f.has("cipher"))) {
                            streamUrl = extractUrlFromCipher(f.optString("signatureCipher", f.optString("cipher")))
                        }

                        if (streamUrl.isNotBlank()) {
                            val mime = f.optString("mimeType", "")
                            val isAudio = mime.contains("audio")
                            val cleanUrl = cleanRangeParams(streamUrl)
                            val rawContentLength = f.optString("contentLength")
                            val parsedSize = rawContentLength.toLongOrNull() ?: f.optLong("contentLength", 0L)
                            val bitrate = f.optString("bitrate").toLongOrNull() ?: f.optLong("bitrate", 0L)
                            val qualityLabel = f.optString("qualityLabel")

                            val finalSize = when {
                                parsedSize > 0L -> parsedSize
                                bitrate > 0L && durationSeconds > 0L -> (bitrate * durationSeconds) / 8
                                else -> calculateEstimatedSize(f.optInt("height", 0), durationSeconds, isAudio = isAudio)
                            }

                            if (isAudio && !qualitiesList.any { it.isAudioOnly }) {
                                qualitiesList.add(
                                    SniffedQuality(
                                        label = "صوت فقط MP3/M4A (عالي النقاء)",
                                        url = cleanUrl,
                                        sizeBytes = finalSize,
                                        isAudioOnly = true,
                                        mimeType = "audio/mp4",
                                        isEstimatedSize = (parsedSize == 0L)
                                    )
                                )
                            } else if (!isAudio && qualityLabel.contains("1080p") && !qualitiesList.any { it.label.contains("1080p") }) {
                                qualitiesList.add(
                                    SniffedQuality(
                                        label = "1080p Full HD فائقة الدقة",
                                        url = cleanUrl,
                                        sizeBytes = finalSize,
                                        mimeType = mime,
                                        isEstimatedSize = (parsedSize == 0L)
                                    )
                                )
                            } else if (!isAudio && qualityLabel.contains("480p") && !qualitiesList.any { it.label.contains("480p") }) {
                                qualitiesList.add(
                                    SniffedQuality(
                                        label = "480p SD دقة متوسطة (توفير بيانات)",
                                        url = cleanUrl,
                                        sizeBytes = finalSize,
                                        mimeType = mime,
                                        isEstimatedSize = (parsedSize == 0L)
                                    )
                                )
                            }
                        }
                    }
                }

                // Captions
                val subsList = mutableListOf<SniffedSubtitle>()
                json.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")?.let { tracks ->
                    for (i in 0 until tracks.length()) {
                        val tr = tracks.getJSONObject(i)
                        val subUrl = tr.optString("baseUrl")
                        val name = tr.optJSONObject("name")?.optString("simpleText") ?: tr.optString("languageCode", "ترجمة")
                        val lang = tr.optString("languageCode", "ar")
                        if (subUrl.isNotBlank()) {
                            subsList.add(SniffedSubtitle(subUrl, name, lang, "VTT"))
                        }
                    }
                }

                if (qualitiesList.isNotEmpty()) {
                    val primaryQuality = qualitiesList.first()
                    val media = SniffedMedia(
                        title = cleanTitle,
                        originalUrl = primaryQuality.url,
                        pageUrl = pageUrl,
                        mimeType = primaryQuality.mimeType,
                        estimatedSizeBytes = primaryQuality.sizeBytes,
                        qualities = qualitiesList,
                        subtitles = subsList,
                        durationSeconds = durationSeconds,
                        thumbnailUrl = thumbUrl
                    )

                    addOrUpdateMedia(media)
                    DiagnosticLogger.s("MediaSniffer", "تم استخراج فيديو يوتيوب بنجاح عبر ($clientName): '$cleanTitle' (${qualitiesList.size} جودات مع الحجم)")
                    return // Successfully extracted
                }
            } catch (e: Exception) {
                DiagnosticLogger.d("MediaSniffer", "عميل يوتيوب ($clientName): ${e.message}")
            }
        }

        // If direct clients failed or were throttled, use Multi-Instance MediaResolver
        try {
            val videoResolved = MediaResolver.resolveDirectDownloadStream(videoId, isAudio = false)
            val audioResolved = MediaResolver.resolveDirectDownloadStream(videoId, isAudio = true)
            val fallbackQualities = mutableListOf<SniffedQuality>()
            if (videoResolved != null) {
                fallbackQualities.add(
                    SniffedQuality(
                        label = videoResolved.quality,
                        url = videoResolved.url,
                        sizeBytes = videoResolved.sizeBytes,
                        mimeType = videoResolved.mimeType
                    )
                )
            }
            if (audioResolved != null) {
                fallbackQualities.add(
                    SniffedQuality(
                        label = audioResolved.quality,
                        url = audioResolved.url,
                        sizeBytes = audioResolved.sizeBytes,
                        isAudioOnly = true,
                        mimeType = audioResolved.mimeType
                    )
                )
            }
            if (fallbackQualities.isNotEmpty()) {
                val primary = fallbackQualities.first()
                val media = SniffedMedia(
                    title = "فيديو ($videoId)",
                    originalUrl = primary.url,
                    pageUrl = pageUrl,
                    mimeType = primary.mimeType,
                    estimatedSizeBytes = primary.sizeBytes,
                    qualities = fallbackQualities,
                    durationSeconds = 0L
                )
                addOrUpdateMedia(media)
                DiagnosticLogger.s("MediaSniffer", "تم استخراج فيديو يوتيوب عبر MediaResolver بنجاح")
            }
        } catch (e: Exception) {
            DiagnosticLogger.w("MediaSniffer", "تعذر الاستخراج عبر MediaResolver: ${e.message}")
        }
    }

    private fun calculateEstimatedSize(height: Int, durationSeconds: Long, isAudio: Boolean): Long {
        val dur = if (durationSeconds > 0) durationSeconds else 180L
        val bitrate = when {
            isAudio -> 128_000L
            height >= 1080 -> 3_500_000L
            height >= 720 -> 1_500_000L
            height >= 480 -> 750_000L
            height >= 360 -> 400_000L
            else -> 1_200_000L
        }
        return (bitrate * dur) / 8
    }

    private fun extractUrlFromCipher(cipher: String): String {
        try {
            val pairs = cipher.split("&")
            for (p in pairs) {
                if (p.startsWith("url=")) {
                    return URLDecoder.decode(p.substring(4), "UTF-8")
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    fun onYouTubeMediaReceivedFromJS(
        title: String,
        pageUrl: String,
        thumbnail: String,
        duration: Long,
        formatsJson: String,
        subsJson: String
    ) {
        snifferScope.launch {
            try {
                val cleanTitle = cleanMediaTitle(title, pageUrl)
                val formatsArray = JSONArray(formatsJson)
                val qualities = mutableListOf<SniffedQuality>()

                for (i in 0 until formatsArray.length()) {
                    val obj = formatsArray.getJSONObject(i)
                    val rawUrl = obj.optString("url")
                    val url = cleanRangeParams(rawUrl)
                    val q = obj.optString("quality", "720p HD")
                    var size = obj.optLong("contentLength", 0L)
                    val isAudio = obj.optBoolean("isAudio", false)
                    val mime = obj.optString("mimeType", if (isAudio) "audio/mp4" else "video/mp4")

                    if (size == 0L) {
                        size = calculateEstimatedSize(if (q.contains("1080")) 1080 else if (q.contains("480")) 480 else 720, duration, isAudio)
                    }

                    if (url.isNotBlank()) {
                        capturedHeaders[url] = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
                            "Referer" to pageUrl
                        )
                        qualities.add(SniffedQuality(q, url, size, isAudio, mime, isEstimatedSize = (obj.optLong("contentLength", 0L) == 0L)))
                    }
                }

                val subsArray = JSONArray(subsJson)
                val subtitles = mutableListOf<SniffedSubtitle>()
                for (i in 0 until subsArray.length()) {
                    val obj = subsArray.getJSONObject(i)
                    val subUrl = obj.optString("url")
                    val label = obj.optString("label", "ترجمة")
                    val lang = obj.optString("lang", "ar")
                    if (subUrl.isNotBlank()) {
                        subtitles.add(SniffedSubtitle(subUrl, label, lang, "VTT"))
                    }
                }

                if (qualities.isNotEmpty()) {
                    val primary = qualities.first()
                    val media = SniffedMedia(
                        title = cleanTitle,
                        originalUrl = primary.url,
                        pageUrl = pageUrl,
                        mimeType = primary.mimeType,
                        estimatedSizeBytes = primary.sizeBytes,
                        qualities = qualities,
                        subtitles = subtitles,
                        durationSeconds = duration,
                        thumbnailUrl = thumbnail.ifBlank { null }
                    )
                    addOrUpdateMedia(media)
                    DiagnosticLogger.s("MediaSniffer", "تم التقاط فيديو يوتيوب من مشغل الصفحة: '$cleanTitle'")
                }
            } catch (e: Exception) {
                DiagnosticLogger.w("MediaSniffer", "تعذر معالجة وسائط يوتيوب: ${e.message}")
            }
        }
    }

    fun onMediaUrlIntercepted(
        url: String,
        pageUrl: String,
        pageTitle: String,
        headers: Map<String, String> = emptyMap(),
        width: Int = 0,
        height: Int = 0,
        duration: Long = 0,
        subtitlesJson: String = "[]"
    ) {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return

        val cleanUrl = cleanRangeParams(url)
        if (seenUrls.contains(cleanUrl)) return
        seenUrls.add(cleanUrl)

        if (headers.isNotEmpty()) {
            capturedHeaders[cleanUrl] = headers
            capturedHeaders[url] = headers
        }

        // Ignore small ad beacons & tracking analytics
        if (url.contains("googleads") || url.contains("doubleclick") || url.contains("pagead") || url.contains("generate_204") || url.contains("analytics")) {
            return
        }

        snifferScope.launch {
            try {
                val cleanTitle = cleanMediaTitle(pageTitle, cleanUrl)
                val mimeType = getMimeType(cleanUrl)

                // Query HEAD for real content size
                var contentSize = 0L
                try {
                    val requestBuilder = Request.Builder()
                        .url(cleanUrl)
                        .head()
                        .header("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")

                    headers["Cookie"]?.let { requestBuilder.header("Cookie", it) }
                    headers["Referer"]?.let { requestBuilder.header("Referer", it) } ?: run {
                        if (pageUrl.isNotBlank()) requestBuilder.header("Referer", pageUrl)
                    }

                    val response = httpClient.newCall(requestBuilder.build()).execute()
                    val cLen = response.header("Content-Length")?.toLongOrNull() ?: 0L
                    if (cLen > 0) contentSize = cLen
                } catch (_: Exception) {}

                if (contentSize == 0L) {
                    contentSize = calculateEstimatedSize(height, duration, isAudio = mimeType.contains("audio"))
                }

                // Parse subtitles
                val subtitlesList = mutableListOf<SniffedSubtitle>()
                try {
                    val jsonArray = JSONArray(subtitlesJson)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val subUrl = obj.optString("url")
                        val label = obj.optString("label", "ترجمة ${i + 1}")
                        val lang = obj.optString("lang", "ar")
                        if (subUrl.isNotEmpty()) {
                            subtitlesList.add(SniffedSubtitle(subUrl, label, lang, if (subUrl.endsWith(".srt")) "SRT" else "VTT"))
                        }
                    }
                } catch (_: Exception) {}

                val qualities = buildQualityOptions(cleanUrl, contentSize, height, duration)

                val media = SniffedMedia(
                    title = cleanTitle,
                    originalUrl = cleanUrl,
                    pageUrl = pageUrl,
                    mimeType = mimeType,
                    estimatedSizeBytes = contentSize,
                    qualities = qualities,
                    subtitles = subtitlesList,
                    durationSeconds = duration
                )

                addOrUpdateMedia(media)
                DiagnosticLogger.s("MediaSniffer", "تم كشف فيديو جديد: '$cleanTitle' (${formatFileSize(contentSize)})")

                // Some providers (including BoxMovies' UGC links) expose one
                // file per resolution, e.g. ...-720p-h264. Discover only
                // sibling URLs that really exist; never fabricate choices.
                if (Regex("(?i)\\d{3,4}p").containsMatchIn(cleanUrl)) {
                    snifferScope.launch {
                        val variants = discoverQualityVariants(cleanUrl, mimeType, headers, pageUrl)
                        if (variants.size > 1) {
                            val current = _sniffedMediaList.value.firstOrNull { it.originalUrl == cleanUrl }
                            if (current != null) {
                                addOrUpdateMedia(current.copy(qualities = variants))
                                DiagnosticLogger.s("MediaSniffer", "تم العثور على ${variants.size} دقات فعلية للرابط")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.w("MediaSniffer", "فشل كشف بيانات الفيديو: ${e.message}")
            }
        }
    }

    private fun addOrUpdateMedia(media: SniffedMedia) {
        val current = _sniffedMediaList.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.title == media.title || it.originalUrl == media.originalUrl }
        if (existingIndex >= 0) {
            current[existingIndex] = media
        } else {
            current.add(0, media)
        }
        _sniffedMediaList.value = current
    }

    private suspend fun discoverQualityVariants(
        sourceUrl: String,
        mimeType: String,
        headers: Map<String, String>,
        pageUrl: String
    ): List<SniffedQuality> {
        val token = Regex("(?i)(\\d{3,4})p").find(sourceUrl) ?: return emptyList()
        val candidates = listOf(360, 480, 720, 1080)
        val result = mutableListOf<SniffedQuality>()
        for (height in candidates) {
            val candidateUrl = sourceUrl.replaceRange(token.range, "${height}p")
            try {
                val builder = Request.Builder()
                    .url(candidateUrl)
                    .get()
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 14; Mobile)")
                headers["Cookie"]?.let { builder.header("Cookie", it) }
                headers["Referer"]?.let { builder.header("Referer", it) } ?: run {
                    if (pageUrl.isNotBlank()) builder.header("Referer", pageUrl)
                }
                httpClient.newCall(builder.build()).execute().use { response ->
                    val type = response.header("Content-Type").orEmpty().lowercase()
                    if (response.isSuccessful && (type.startsWith("video/") || type.contains("mp4") || type.contains("webm"))) {
                        val length = response.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                            ?: response.header("Content-Length")?.toLongOrNull() ?: 0L
                        result.add(
                            SniffedQuality(
                                label = "${height}p",
                                url = candidateUrl,
                                sizeBytes = length,
                                mimeType = if (type.startsWith("video/")) type.substringBefore(';') else mimeType,
                                isEstimatedSize = length == 0L
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // A missing resolution is expected; continue with other candidates.
            }
        }
        return result.sortedBy { it.label.substringBefore('p').toIntOrNull() ?: 0 }
    }

    fun isMediaResource(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("googleads") || lower.contains("doubleclick") || lower.contains("pagead") ||
            lower.contains("generate_204") || lower.contains("analytics") || lower.contains("adserver") ||
            lower.contains("advertisement") || lower.contains("/ads/") || lower.contains("/ad/") ||
            lower.contains("banner") || lower.contains("vast") || lower.contains("vpaid")) {
            return false
        }
        return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".m3u8") ||
               lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".mp3") ||
               lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".vtt") ||
               lower.endsWith(".srt") || lower.contains(".mp4?") || lower.contains(".m3u8?") ||
               lower.contains(".webm?") || lower.contains("videoplayback") || lower.contains("/video/") ||
               lower.contains("/stream/") || lower.contains("mime=video") || lower.contains("mime=audio") ||
               lower.contains("googlevideo.com") ||
               (lower.contains("ugc-edu.com/") && Regex("\\d{3,4}p(-|$)").containsMatchIn(lower))
    }

    fun cleanRangeParams(url: String): String {
        return url.replace(Regex("&range=[0-9]+-[0-9]+"), "")
            .replace(Regex("\\?range=[0-9]+-[0-9]+&"), "?")
            .replace(Regex("\\?range=[0-9]+-[0-9]+$"), "")
    }

    private fun buildQualityOptions(url: String, sizeBytes: Long, detectedHeight: Int, durationSeconds: Long): List<SniffedQuality> {
        val lower = url.lowercase()
        val isYt = lower.contains("googlevideo.com") || lower.contains("videoplayback")

        if (isYt) {
            val itag = runCatching { Uri.parse(url).getQueryParameter("itag")?.toIntOrNull() }.getOrNull() ?: 0
            val mimeParam = runCatching { Uri.parse(url).getQueryParameter("mime") }.getOrNull()?.lowercase() ?: ""
            val isAudio = mimeParam.startsWith("audio") || itag in listOf(140, 141, 251, 250, 249, 171)
            val parsedClen = runCatching { Uri.parse(url).getQueryParameter("clen")?.toLongOrNull() }.getOrNull() ?: 0L
            val finalSize = if (parsedClen > 0L) parsedClen else if (sizeBytes > 0L) sizeBytes else calculateEstimatedSize(720, durationSeconds, isAudio)

            val ytLabel = when (itag) {
                22 -> "720p HD (فيديو وصوت كامل MP4)"
                18 -> "360p SD (فيديو وصوت كامل MP4)"
                137 -> "1080p Full HD"
                136 -> "720p HD"
                135 -> "480p SD"
                134 -> "360p SD"
                140 -> "صوت عالي النقاء M4A/MP3"
                251 -> "صوت نقي Opus WebM"
                else -> if (isAudio) "مقطع صوتي (نقي)" else if (detectedHeight > 0) "${detectedHeight}p" else "فيديو يوتيوب"
            }

            return listOf(
                SniffedQuality(
                    label = ytLabel,
                    url = url,
                    sizeBytes = finalSize,
                    isAudioOnly = isAudio,
                    mimeType = if (isAudio) "audio/mp4" else "video/mp4",
                    isEstimatedSize = (parsedClen == 0L && sizeBytes == 0L)
                )
            )
        }

        val mainLabel = when {
            detectedHeight >= 1080 -> "1080p Full HD"
            detectedHeight >= 720 -> "720p HD (عالية)"
            detectedHeight >= 480 -> "480p SD (متوسطة)"
            detectedHeight > 0 -> "${detectedHeight}p"
            else -> "جودة المصدر"
        }
        val isEstimated = (sizeBytes == 0L)
        val baseSize = if (sizeBytes > 0) sizeBytes else calculateEstimatedSize(720, durationSeconds, false)

        return listOf(SniffedQuality(mainLabel, url, baseSize, isEstimatedSize = isEstimated))
    }

    private fun cleanMediaTitle(rawTitle: String, url: String): String {
        var title = rawTitle.replace(Regex("(?i)\\s*-\\s*youtube$"), "").trim()
        if (title.isBlank() || title == "undefined" || title == "فيديو تم كشفه" || title == "فيديو مباشر" || title == "YouTube" || title == "m.youtube.com") {
            try {
                val path = Uri.parse(url).lastPathSegment
                if (!path.isNullOrBlank() && path != "videoplayback") {
                    title = path.substringBefore("?").substringBefore("&")
                }
            } catch (_: Exception) {}
        }
        if (title.isBlank() || title == "videoplayback") {
            title = "فيديو رقمي (${System.currentTimeMillis() % 10000})"
        }
        return title.take(90)
    }

    private fun getMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> "application/x-mpegURL"
            lower.contains(".webm") -> "video/webm"
            lower.contains(".mp3") -> "audio/mpeg"
            lower.contains(".m4a") -> "audio/mp4"
            lower.contains(".aac") -> "audio/aac"
            lower.contains(".vtt") -> "text/vtt"
            lower.contains(".srt") -> "application/x-subrip"
            else -> "video/mp4"
        }
    }

    fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("(?:v=|/v/|youtu\\.be/|/embed/|/shorts/)([a-zA-Z0-9_-]{11})"),
            Regex("^[a-zA-Z0-9_-]{11}$")
        )
        for (p in patterns) {
            val match = p.find(url)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return null
    }

    fun formatFileSize(bytes: Long, isEstimated: Boolean = false): String {
        if (bytes <= 0L) return "~ 15 ميجابايت"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        val prefix = if (isEstimated) "~ " else ""
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "$prefix%.2f جيجابايت", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "$prefix%.1f ميجابايت", mb)
            else -> String.format(java.util.Locale.US, "$prefix%.0f كيلوبايت", kb)
        }
    }
}
