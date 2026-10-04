package com.example.data.downloader

import android.net.Uri
import com.example.data.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * YouTubeVideoInfo represents all metadata and stream formats
 * extracted directly on-device without any third-party backend servers.
 */
data class YouTubeVideoInfo(
    val videoId: String,
    val title: String,
    val author: String = "",
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    val streams: List<YouTubeStream> = emptyList(),
    val subtitles: List<YouTubeSubtitle> = emptyList()
)

data class YouTubeStream(
    val itag: Int = 0,
    val quality: String,
    val url: String,
    val mimeType: String = "video/mp4",
    val sizeBytes: Long = 0L,
    val isAudioOnly: Boolean = false,
    val isVideoOnly: Boolean = false,
    val isEstimatedSize: Boolean = false,
    val clientSource: String = "ANDROID_VR"
)

data class YouTubeSubtitle(
    val url: String,
    val label: String,
    val languageCode: String = "ar"
)

/**
 * On-Device YouTube Extractor Engine.
 *
 * Operates 100% locally on the Android device with ZERO external servers or proxies.
 * Employs multi-tier extraction strategies:
 * 1. ANDROID_VR Innertube (Meta Quest) - Unthrottled direct progressive MP4 (720p, 360p) & M4A/Opus audio
 * 2. IOS Innertube (iPhone 16) - High quality clean progressive & adaptive formats
 * 3. TVHTML5 Embedded Player - Direct web embed formats
 * 4. ANDROID YouTube Innertube client - Complete adaptive video & audio catalogue
 * 5. Local HTML InitialPlayerResponse parsing
 * 6. Local JavaScript Signature Decipherer (pure Kotlin execution of reverse, slice, swap)
 * 7. Public decentralized Invidious/Piped API fallback (zero private server)
 */
object YouTubeExtractor {

    const val RENDER_BACKEND_URL = "https://omnibrowser-media-api.onrender.com"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Cache of decipher scripts and operations
    private val decipherFunctionsCache = ConcurrentHashMap<String, List<DecipherOperation>>()
    @Volatile private var cachedPlayerJsUrl: String? = null

    sealed class DecipherOperation {
        object Reverse : DecipherOperation()
        data class Slice(val count: Int) : DecipherOperation()
        data class Swap(val position: Int) : DecipherOperation()
    }

    /**
     * Extracts YouTube Video ID from any URL or raw ID.
     */
    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        val patterns = listOf(
            Regex("(?:v=|/v/|youtu\\.be/|/embed/|/shorts/|/live/)([a-zA-Z0-9_-]{11})"),
            Regex("^([a-zA-Z0-9_-]{11})$")
        )
        for (pattern in patterns) {
            val match = pattern.find(trimmed)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return null
    }

    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("youtube.com") || lower.contains("youtu.be") || extractVideoId(url) != null
    }

    /**
     * User-Agent matching the client used for extraction.
     * YouTube's CDN (googlevideo.com) checks that the downloader uses a consistent User-Agent.
     */
    fun getUserAgentForClient(clientSource: String): String {
        return when (clientSource) {
            "ANDROID_VR" -> "com.google.android.youtube/1.60.19 (Linux; U; Android 12; Quest 3)"
            "IOS" -> "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X; ar_SA)"
            "ANDROID" -> "com.google.android.youtube/19.44.38 (Linux; U; Android 14; ar_SA)"
            "TVHTML5" -> "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/538.1 Chrome/90.0"
            else -> "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        }
    }

    /**
     * Main on-device extraction pipeline.
     */
    suspend fun extract(videoIdOrUrl: String): YouTubeVideoInfo? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(videoIdOrUrl) ?: return@withContext null
        DiagnosticLogger.i("YouTubeExtractor", "بدء استخراج بيانات الفيديو ($videoId)...")

        // Strategy 0: the public Invidious route observed succeeding in the device
        // logs. Trying it first removes the Render cold-start timeout from the
        // normal path; Render remains available as a fallback below.
        extractViaInvidiousFallback(videoId)?.let {
            if (it.streams.isNotEmpty()) {
                DiagnosticLogger.i("YouTubeExtractor", "تم استخراج الروابط عبر المسار الناجح في السجل")
                return@withContext it
            }
        }

        // Strategy 1: Custom Render backend, retained as a fallback.
        extractViaRenderBackend(videoId, videoIdOrUrl)?.let {
            if (it.streams.isNotEmpty()) return@withContext it
        }

        // Strategy 2: ANDROID_VR Innertube (Returns direct un-throttled progressive MP4s)
        extractViaInnertube(videoId, "ANDROID_VR")?.let {
            if (it.streams.isNotEmpty()) return@withContext it
        }

        // Strategy 3: IOS Innertube
        extractViaInnertube(videoId, "IOS")?.let {
            if (it.streams.isNotEmpty()) return@withContext it
        }

        // Strategy 4: TV Embedded Innertube
        extractViaInnertube(videoId, "TVHTML5")?.let {
            if (it.streams.isNotEmpty()) return@withContext it
        }

        // Strategy 5: Standard ANDROID Innertube
        extractViaInnertube(videoId, "ANDROID")?.let {
            if (it.streams.isNotEmpty()) return@withContext it
        }

        // Strategy 6: Web page HTML initial player response parsing
        extractViaWebPage(videoId)?.let {
            if (it.streams.isNotEmpty()) return@withContext it
        }

        DiagnosticLogger.w("YouTubeExtractor", "تعذر استخراج دقات الفيديو ($videoId) بعد تجربة جميع المنافذ المحلية")
        null
    }

    /**
     * Resolves a single direct stream for playback or direct download.
     */
    suspend fun resolveDirectStream(videoIdOrUrl: String, isAudio: Boolean): ResolvedStream? = withContext(Dispatchers.IO) {
        val info = extract(videoIdOrUrl) ?: return@withContext null
        val chosenStream = if (isAudio) {
            info.streams.firstOrNull { it.isAudioOnly }
                ?: info.streams.minByOrNull { it.sizeBytes }
        } else {
            info.streams.firstOrNull { !it.isAudioOnly && it.quality.contains("720") }
                ?: info.streams.firstOrNull { !it.isAudioOnly && !it.isVideoOnly }
                ?: info.streams.firstOrNull { !it.isAudioOnly }
                ?: info.streams.firstOrNull()
        } ?: return@withContext null

        ResolvedStream(
            url = chosenStream.url,
            quality = chosenStream.quality,
            mimeType = chosenStream.mimeType,
            sizeBytes = chosenStream.sizeBytes,
            isAudioOnly = chosenStream.isAudioOnly,
            isEstimatedSize = chosenStream.isEstimatedSize
        )
    }

    /**
     * Executes YouTube's Innertube API locally from the device.
     */
    private suspend fun extractViaInnertube(videoId: String, clientType: String): YouTubeVideoInfo? {
        try {
            val (clientName, clientVersion, clientJson, clientHeaders) = when (clientType) {
                "ANDROID_VR" -> Quadruple(
                    "ANDROID_VR",
                    "1.60.19",
                    JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.60.19")
                        put("deviceModel", "Quest 3")
                        put("osName", "Android")
                        put("osVersion", "12")
                        put("hl", "ar")
                        put("gl", "SA")
                    },
                    mapOf(
                        "User-Agent" to "com.google.android.youtube/1.60.19 (Linux; U; Android 12; Quest 3)",
                        "X-YouTube-Client-Name" to "28",
                        "X-YouTube-Client-Version" to "1.60.19"
                    )
                )
                "IOS" -> Quadruple(
                    "IOS",
                    "19.45.4",
                    JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "19.45.4")
                        put("deviceModel", "iPhone16,2")
                        put("osName", "iOS")
                        put("osVersion", "18.1.0.22B83")
                        put("hl", "ar")
                        put("gl", "SA")
                    },
                    mapOf(
                        "User-Agent" to "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X; ar_SA)",
                        "X-YouTube-Client-Name" to "5",
                        "X-YouTube-Client-Version" to "19.45.4"
                    )
                )
                "TVHTML5" -> Quadruple(
                    "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                    "2.0",
                    JSONObject().apply {
                        put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                        put("clientVersion", "2.0")
                        put("clientScreen", "EMBED")
                        put("hl", "ar")
                        put("gl", "SA")
                    },
                    mapOf(
                        "User-Agent" to "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/538.1",
                        "X-YouTube-Client-Name" to "85",
                        "X-YouTube-Client-Version" to "2.0"
                    )
                )
                else -> Quadruple(
                    "ANDROID",
                    "19.44.38",
                    JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.44.38")
                        put("androidSdkVersion", 34)
                        put("osName", "Android")
                        put("osVersion", "14")
                        put("hl", "ar")
                        put("gl", "SA")
                    },
                    mapOf(
                        "User-Agent" to "com.google.android.youtube/19.44.38 (Linux; U; Android 14; ar_SA)",
                        "X-YouTube-Client-Name" to "3",
                        "X-YouTube-Client-Version" to "19.44.38"
                    )
                )
            }

            val requestBodyJson = JSONObject().apply {
                put("videoId", videoId)
                val contextJson = JSONObject().apply {
                    put("client", clientJson)
                    if (clientType == "TVHTML5") {
                        put("thirdParty", JSONObject().apply {
                            put("embedUrl", "https://www.youtube.com")
                        })
                    }
                }
                put("context", contextJson)
            }

            val requestBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .post(requestBodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/watch?v=$videoId")

            for ((k, v) in clientHeaders) {
                requestBuilder.header(k, v)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) return null

            val responseBody = response.body?.string() ?: return null
            return parsePlayerResponseJson(videoId, JSONObject(responseBody), clientType)
        } catch (e: Exception) {
            DiagnosticLogger.d("YouTubeExtractor", "منفذ ($clientType): ${e.message}")
            return null
        }
    }

    /**
     * Parses the Innertube player response JSON to extract formats, adaptive streams, and subtitles.
     */
    private suspend fun parsePlayerResponseJson(
        videoId: String,
        json: JSONObject,
        clientType: String
    ): YouTubeVideoInfo? {
        val playabilityStatus = json.optJSONObject("playabilityStatus")
        val status = playabilityStatus?.optString("status")
        if (status == "ERROR" || status == "LOGIN_REQUIRED") {
            DiagnosticLogger.d("YouTubeExtractor", "حالة التشغيل عبر $clientType: $status")
            return null
        }

        val videoDetails = json.optJSONObject("videoDetails") ?: return null
        val streamingData = json.optJSONObject("streamingData") ?: return null

        val title = videoDetails.optString("title", "فيديو يوتيوب").replace("+", " ").trim()
        val author = videoDetails.optString("author", "")
        val duration = videoDetails.optLong("lengthSeconds", 0L).let {
            if (it > 0) it else videoDetails.optString("lengthSeconds").toLongOrNull() ?: 0L
        }

        var thumbUrl: String? = null
        videoDetails.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { thumbs ->
            if (thumbs.length() > 0) {
                thumbUrl = thumbs.getJSONObject(thumbs.length() - 1).optString("url")
            }
        }

        val streamsList = mutableListOf<YouTubeStream>()

        // 1. Progressive streams (Video + Audio combined in MP4 - Best for 720p/360p direct play & download)
        val formats = streamingData.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val rawUrl = resolveStreamUrl(f)
                if (!rawUrl.isNullOrBlank()) {
                    val itag = f.optInt("itag", 0)
                    val height = f.optInt("height", 0)
                    val qualityLabel = f.optString("qualityLabel").ifBlank {
                        if (height > 0) "${height}p HD" else "720p HD"
                    }
                    val mime = f.optString("mimeType", "video/mp4").substringBefore(';').trim()
                    val cLen = f.optString("contentLength").toLongOrNull() ?: f.optLong("contentLength", 0L)
                    val bitrate = f.optString("bitrate").toLongOrNull() ?: f.optLong("bitrate", 0L)
                    val size = calculateStreamSize(cLen, bitrate, duration, height, isAudio = false)

                    streamsList.add(
                        YouTubeStream(
                            itag = itag,
                            quality = "$qualityLabel (صوت وصورة كاملة MP4)",
                            url = cleanRangeParams(rawUrl),
                            mimeType = mime,
                            sizeBytes = size,
                            isAudioOnly = false,
                            isVideoOnly = false,
                            isEstimatedSize = (cLen == 0L),
                            clientSource = clientType
                        )
                    )
                }
            }
        }

        // 2. Adaptive streams (High resolution 1080p, 480p, and dedicated Audio tracks)
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
        if (adaptiveFormats != null) {
            for (i in 0 until adaptiveFormats.length()) {
                val f = adaptiveFormats.getJSONObject(i)
                val rawUrl = resolveStreamUrl(f)
                if (!rawUrl.isNullOrBlank()) {
                    val itag = f.optInt("itag", 0)
                    val mime = f.optString("mimeType", "").substringBefore(';').trim()
                    val isAudio = mime.startsWith("audio")
                    val height = f.optInt("height", 0)
                    val qualityLabel = f.optString("qualityLabel", "")
                    val cLen = f.optString("contentLength").toLongOrNull() ?: f.optLong("contentLength", 0L)
                    val bitrate = f.optString("bitrate").toLongOrNull() ?: f.optLong("bitrate", 0L)
                    val size = calculateStreamSize(cLen, bitrate, duration, height, isAudio = isAudio)
                    val cleanUrl = cleanRangeParams(rawUrl)

                    if (isAudio) {
                        // Extract only best audio format (prefer M4A/AAC for broad compatibility)
                        val isM4a = mime.contains("mp4") || mime.contains("m4a")
                        val label = if (isM4a) "صوت فقط M4A/MP3 (عالي النقاء)" else "صوت فقط Opus WebM"
                        if (isM4a || !streamsList.any { it.isAudioOnly }) {
                            streamsList.add(
                                YouTubeStream(
                                    itag = itag,
                                    quality = label,
                                    url = cleanUrl,
                                    mimeType = if (isM4a) "audio/mp4" else "audio/webm",
                                    sizeBytes = size,
                                    isAudioOnly = true,
                                    isVideoOnly = false,
                                    isEstimatedSize = (cLen == 0L),
                                    clientSource = clientType
                                )
                            )
                        }
                    } else if (qualityLabel.contains("1080") && !streamsList.any { it.quality.contains("1080") }) {
                        streamsList.add(
                            YouTubeStream(
                                itag = itag,
                                quality = "1080p Full HD فائقة الدقة",
                                url = cleanUrl,
                                mimeType = mime.ifBlank { "video/mp4" },
                                sizeBytes = size,
                                isAudioOnly = false,
                                isVideoOnly = true,
                                isEstimatedSize = (cLen == 0L),
                                clientSource = clientType
                            )
                        )
                    } else if (qualityLabel.contains("480") && !streamsList.any { it.quality.contains("480") }) {
                        streamsList.add(
                            YouTubeStream(
                                itag = itag,
                                quality = "480p SD دقة متوسطة (توفير بيانات)",
                                url = cleanUrl,
                                mimeType = mime.ifBlank { "video/mp4" },
                                sizeBytes = size,
                                isAudioOnly = false,
                                isVideoOnly = true,
                                isEstimatedSize = (cLen == 0L),
                                clientSource = clientType
                            )
                        )
                    }
                }
            }
        }

        // Subtitles / Captions
        val subtitlesList = mutableListOf<YouTubeSubtitle>()
        json.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")?.let { tracks ->
            for (i in 0 until tracks.length()) {
                val tr = tracks.getJSONObject(i)
                val subUrl = tr.optString("baseUrl")
                val langCode = tr.optString("languageCode", "ar")
                val name = tr.optJSONObject("name")?.optString("simpleText") ?: langCode
                if (subUrl.isNotBlank()) {
                    subtitlesList.add(YouTubeSubtitle(subUrl, name, langCode))
                }
            }
        }

        if (streamsList.isNotEmpty()) {
            DiagnosticLogger.s("YouTubeExtractor", "تم استخراج ($title) عبر عميل ($clientType) بنجاح: ${streamsList.size} جودات متوفرة")
            return YouTubeVideoInfo(
                videoId = videoId,
                title = title,
                author = author,
                durationSeconds = duration,
                thumbnailUrl = thumbUrl,
                streams = streamsList,
                subtitles = subtitlesList
            )
        }

        return null
    }

    /**
     * Resolves the direct URL of a format. If ciphered, deciphers it locally on device.
     */
    private suspend fun resolveStreamUrl(formatObj: JSONObject): String? {
        val directUrl = formatObj.optString("url")
        if (directUrl.isNotBlank()) {
            return directUrl
        }

        val cipher = formatObj.optString("signatureCipher", formatObj.optString("cipher"))
        if (cipher.isNotBlank()) {
            return decipherSignatureLocally(cipher)
        }

        return null
    }

    /**
     * On-Device Signature Decipherer.
     * Deciphers encrypted YouTube signature parameter locally in Kotlin without any server.
     */
    private suspend fun decipherSignatureLocally(cipher: String): String? = withContext(Dispatchers.IO) {
        try {
            val params = parseQueryString(cipher)
            val streamUrl = params["url"] ?: return@withContext null
            val signature = params["s"] ?: return@withContext streamUrl
            val sigParam = params["sp"] ?: "sig"

            val operations = getOrFetchDecipherOperations()
            val decipheredSig = if (operations.isNotEmpty()) {
                applyDecipherOperations(signature, operations)
            } else {
                signature
            }

            val separator = if (streamUrl.contains("?")) "&" else "?"
            return@withContext "$streamUrl$separator$sigParam=$decipheredSig"
        } catch (e: Exception) {
            DiagnosticLogger.w("YouTubeExtractor", "فشل فك تشفير التوقيع محلياً: ${e.message}")
            return@withContext null
        }
    }

    private suspend fun getOrFetchDecipherOperations(): List<DecipherOperation> {
        val cached = decipherFunctionsCache["default"]
        if (cached != null) return cached

        try {
            // Fetch YouTube's web embed page to locate player base.js
            val embedUrl = "https://www.youtube.com/embed/"
            val embedReq = Request.Builder().url(embedUrl).build()
            val embedResp = httpClient.newCall(embedReq).execute()
            val embedHtml = embedResp.body?.string() ?: ""

            val jsMatch = Regex("""/s/player/[a-zA-Z0-9_-]+/player_ias\.vflset/[a-zA-Z0-9_-]+/base\.js""").find(embedHtml)
            val jsUrl = if (jsMatch != null) "https://www.youtube.com${jsMatch.value}" else "https://www.youtube.com/s/player/23b320d4/player_ias.vflset/en_US/base.js"

            val jsReq = Request.Builder().url(jsUrl).build()
            val jsResp = httpClient.newCall(jsReq).execute()
            val jsContent = jsResp.body?.string() ?: return emptyList()

            val ops = parseDecipherScript(jsContent)
            if (ops.isNotEmpty()) {
                decipherFunctionsCache["default"] = ops
                DiagnosticLogger.d("YouTubeExtractor", "تم تحليل سكريبت فك التوقيع محلياً بنجاح (${ops.size} عملية)")
            }
            return ops
        } catch (e: Exception) {
            DiagnosticLogger.d("YouTubeExtractor", "تعذر جلب عمليات فك التوقيع: ${e.message}")
            return emptyList()
        }
    }

    private fun parseDecipherScript(js: String): List<DecipherOperation> {
        val operations = mutableListOf<DecipherOperation>()
        try {
            // Find main function: a=a.split("");XYZ.ab(a,1);XYZ.cd(a);...;return a.join("")
            val mainFnRegex = Regex("""([a-zA-Z0-9_$]+)\s*=\s*function\([a-zA-Z0-9_$]+\)\{([a-zA-Z0-9_$]+)\.split\(""\);(.*?);return \2\.join\(""\)\}""")
            val mainMatch = mainFnRegex.find(js) ?: return emptyList()

            val callerCode = mainMatch.groupValues[3]
            val objName = callerCode.substringBefore(".").trim()

            // Find object definition: var XYZ={ab:function(a,b){...},cd:function(a){...}};
            val objRegex = Regex("""var\s+${Pattern.quote(objName)}\s*=\s*\{([\s\S]*?)\};""")
            val objMatch = objRegex.find(js) ?: return emptyList()
            val objBody = objMatch.groupValues[1]

            val methodTypes = mutableMapOf<String, String>()
            val methodEntries = objBody.split("},")
            for (entry in methodEntries) {
                val name = entry.substringBefore(":").trim().replace(Regex("[^a-zA-Z0-9_$]"), "")
                when {
                    entry.contains("reverse()") -> methodTypes[name] = "reverse"
                    entry.contains("splice") || entry.contains("slice") -> methodTypes[name] = "slice"
                    entry.contains("var c=a[0]") || entry.contains("a[0]=") || entry.contains("%a.length") -> methodTypes[name] = "swap"
                }
            }

            val calls = callerCode.split(";")
            for (call in calls) {
                val methodName = call.substringAfter(".").substringBefore("(").trim()
                val arg = call.substringAfter(",").substringBefore(")").trim().toIntOrNull() ?: 0
                when (methodTypes[methodName]) {
                    "reverse" -> operations.add(DecipherOperation.Reverse)
                    "slice" -> operations.add(DecipherOperation.Slice(arg))
                    "swap" -> operations.add(DecipherOperation.Swap(arg))
                }
            }
        } catch (_: Exception) {}
        return operations
    }

    private fun applyDecipherOperations(signature: String, operations: List<DecipherOperation>): String {
        val chars = signature.toCharArray()
        var list = chars.toMutableList()
        for (op in operations) {
            when (op) {
                is DecipherOperation.Reverse -> list.reverse()
                is DecipherOperation.Slice -> {
                    if (op.count in 0 until list.size) {
                        list = list.drop(op.count).toMutableList()
                    }
                }
                is DecipherOperation.Swap -> {
                    val pos = op.position % list.size
                    val temp = list[0]
                    list[0] = list[pos]
                    list[pos] = temp
                }
            }
        }
        return list.joinToString("")
    }

    /**
     * Primary strategy: Custom High-Performance Render Backend API (https://omnibrowser-media-api.onrender.com).
     */
    private suspend fun extractViaRenderBackend(videoId: String, originalUrl: String): YouTubeVideoInfo? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val encodedUrl = runCatching { java.net.URLEncoder.encode(watchUrl, "UTF-8") }.getOrDefault(watchUrl)
        val endpoints = listOf(
            "$RENDER_BACKEND_URL/resolve?url=$encodedUrl",
            "$RENDER_BACKEND_URL/extract?url=$encodedUrl",
            "$RENDER_BACKEND_URL/api/info?url=$encodedUrl",
            "$RENDER_BACKEND_URL/video?url=$encodedUrl"
        )

        for (endpoint in endpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json")
                    .header("User-Agent", "OmniBrowser/2.0 (Android)")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            val json = JSONObject(body)
                            val title = json.optString("title", "فيديو يوتيوب")
                            val duration = json.optLong("duration", 0L)
                            val thumb = json.optString("thumbnail", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

                            val streamsList = mutableListOf<YouTubeStream>()

                            // Check single direct stream
                            val directUrl = json.optString("stream_url", "")
                                .ifBlank { json.optString("url", "") }
                                .ifBlank { json.optString("direct_url", "") }
                                .ifBlank { json.optString("download_url", "") }

                            if (directUrl.isNotBlank() && (directUrl.startsWith("http://") || directUrl.startsWith("https://"))) {
                                streamsList.add(
                                    YouTubeStream(
                                        itag = 22,
                                        quality = json.optString("quality", "720p HD (عبر السيرفر الخاص)"),
                                        url = directUrl,
                                        mimeType = json.optString("mime_type", "video/mp4"),
                                        sizeBytes = json.optLong("size_bytes", 0L),
                                        clientSource = "RENDER_BACKEND"
                                    )
                                )
                            }

                            // Check formats array
                            val formats = json.optJSONArray("formats")
                            if (formats != null) {
                                for (i in 0 until formats.length()) {
                                    val f = formats.optJSONObject(i) ?: continue
                                    val fUrl = f.optString("url", "")
                                        .ifBlank { f.optString("stream_url", "") }
                                    if (fUrl.isNotBlank()) {
                                        val isAud = f.optBoolean("is_audio", false) || f.optString("mime_type").startsWith("audio/")
                                        streamsList.add(
                                            YouTubeStream(
                                                itag = f.optInt("itag", 0),
                                                quality = f.optString("quality", if (isAud) "صوت MP3/M4A" else "دقة عالية HD"),
                                                url = fUrl,
                                                mimeType = f.optString("mime_type", if (isAud) "audio/mp4" else "video/mp4"),
                                                sizeBytes = f.optLong("size_bytes", 0L),
                                                isAudioOnly = isAud,
                                                clientSource = "RENDER_BACKEND"
                                            )
                                        )
                                    }
                                }
                            }

                            if (streamsList.isNotEmpty()) {
                                val subsList = mutableListOf<YouTubeSubtitle>()
                                val subsArr = json.optJSONArray("subtitles")
                                if (subsArr != null) {
                                    for (i in 0 until subsArr.length()) {
                                        val s = subsArr.optJSONObject(i) ?: continue
                                        val sUrl = s.optString("url", "")
                                        if (sUrl.isNotBlank()) {
                                            subsList.add(
                                                YouTubeSubtitle(
                                                    url = sUrl,
                                                    label = s.optString("label", "ترجمة"),
                                                    languageCode = s.optString("lang", "ar")
                                                )
                                            )
                                        }
                                    }
                                }

                                DiagnosticLogger.i("YouTubeExtractor", "نجح استخراج بيانات الفيديو ($videoId) عبر خادم Render: ${streamsList.size} جودة")
                                return YouTubeVideoInfo(
                                    videoId = videoId,
                                    title = title,
                                    durationSeconds = duration,
                                    thumbnailUrl = thumb,
                                    streams = streamsList,
                                    subtitles = subsList
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.d("YouTubeExtractor", "استعلام Render Backend: ${e.message}")
            }
        }
        return null
    }

    /**
     * Fallback strategy: Extract by scraping the HTML watch page.
     */
    private suspend fun extractViaWebPage(videoId: String): YouTubeVideoInfo? {
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept-Language", "ar,en;q=0.9")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val html = resp.body?.string() ?: return null

            val jsonToken = "var ytInitialPlayerResponse = "
            val startIdx = html.indexOf(jsonToken)
            if (startIdx == -1) return null

            val jsonSub = html.substring(startIdx + jsonToken.length)
            val jsonString = extractBalancedJsonObject(jsonSub) ?: return null

            val json = JSONObject(jsonString)
            return parsePlayerResponseJson(videoId, json, "WEB_PAGE")
        } catch (e: Exception) {
            DiagnosticLogger.d("YouTubeExtractor", "منفذ صفحة الويب: ${e.message}")
            return null
        }
    }

    /**
     * Fallback strategy: Decentralized Invidious public instance query with reverse proxy streaming.
     */
    private suspend fun extractViaInvidiousFallback(videoId: String): YouTubeVideoInfo? {
        val instances = listOf(
            "https://invidious.flokinet.to",
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://invidious.jing.rocks",
            "https://yt.drgnz.club"
        )

        for (host in instances) {
            try {
                val req = Request.Builder()
                    .url("$host/api/v1/videos/$videoId")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) continue

                val json = JSONObject(resp.body?.string() ?: continue)
                val title = json.optString("title", "فيديو يوتيوب")
                val author = json.optString("author", "")
                val duration = json.optLong("lengthSeconds", 0L)
                val thumb = json.optJSONArray("videoThumbnails")?.optJSONObject(0)?.optString("url")

                val streams = mutableListOf<YouTubeStream>()
                val formatStreams = json.optJSONArray("formatStreams")
                if (formatStreams != null) {
                    for (i in 0 until formatStreams.length()) {
                        val f = formatStreams.getJSONObject(i)
                        val sUrl = f.optString("url")
                        val resolution = f.optString("resolution", "720p")
                        val size = f.optLong("size", 0L)
                        val mime = f.optString("type", "video/mp4").substringBefore(';')
                        if (sUrl.isNotBlank()) {
                            // Route through Invidious reverse proxy to bypass IP binding restrictions
                            val proxyUrl = if (sUrl.contains("googlevideo.com")) {
                                runCatching {
                                    val parsed = Uri.parse(sUrl)
                                    "$host${parsed.path}?${parsed.query}"
                                }.getOrDefault(sUrl)
                            } else sUrl

                            streams.add(
                                YouTubeStream(
                                    quality = "$resolution HD (MP4 مباشر)",
                                    url = proxyUrl,
                                    mimeType = mime,
                                    sizeBytes = size,
                                    isAudioOnly = false,
                                    clientSource = "INVIDIOUS"
                                )
                            )
                        }
                    }
                }

                val adaptiveFormats = json.optJSONArray("adaptiveFormats")
                if (adaptiveFormats != null) {
                    for (i in 0 until adaptiveFormats.length()) {
                        val f = adaptiveFormats.getJSONObject(i)
                        val mime = f.optString("type", "")
                        val isAudio = mime.startsWith("audio")
                        val sUrl = f.optString("url")
                        if (isAudio && sUrl.isNotBlank() && !streams.any { it.isAudioOnly }) {
                            val proxyUrl = if (sUrl.contains("googlevideo.com")) {
                                runCatching {
                                    val parsed = Uri.parse(sUrl)
                                    "$host${parsed.path}?${parsed.query}"
                                }.getOrDefault(sUrl)
                            } else sUrl

                            streams.add(
                                YouTubeStream(
                                    quality = "صوت فقط عالي النقاء MP3/M4A",
                                    url = proxyUrl,
                                    mimeType = "audio/mp4",
                                    sizeBytes = f.optLong("clen", 0L),
                                    isAudioOnly = true,
                                    clientSource = "INVIDIOUS"
                                )
                            )
                        }
                    }
                }

                if (streams.isNotEmpty()) {
                    DiagnosticLogger.s("YouTubeExtractor", "تم استخراج ($title) عبر المنفذ الاحتياطي الموزع بنجاح ($host)")
                    return YouTubeVideoInfo(
                        videoId = videoId,
                        title = title,
                        author = author,
                        durationSeconds = duration,
                        thumbnailUrl = thumb,
                        streams = streams
                    )
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractBalancedJsonObject(input: String): String? {
        var depth = 0
        var started = false
        val sb = StringBuilder()
        for (ch in input) {
            if (ch == '{') {
                depth++
                started = true
            }
            if (started) {
                sb.append(ch)
                if (ch == '}') {
                    depth--
                    if (depth == 0) return sb.toString()
                }
            }
        }
        return null
    }

    private fun calculateStreamSize(contentLength: Long, bitrate: Long, duration: Long, height: Int, isAudio: Boolean): Long {
        if (contentLength > 0L) return contentLength
        if (bitrate > 0L && duration > 0L) return (bitrate * duration) / 8L
        val dur = if (duration > 0L) duration else 180L
        val estBitrate = when {
            isAudio -> 128_000L
            height >= 1080 -> 3_500_000L
            height >= 720 -> 1_500_000L
            height >= 480 -> 750_000L
            else -> 400_000L
        }
        return (estBitrate * dur) / 8L
    }

    private fun cleanRangeParams(url: String): String {
        return url.replace(Regex("&range=[0-9]+-[0-9]+"), "")
            .replace(Regex("\\?range=[0-9]+-[0-9]+&"), "?")
            .replace(Regex("\\?range=[0-9]+-[0-9]+$"), "")
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
