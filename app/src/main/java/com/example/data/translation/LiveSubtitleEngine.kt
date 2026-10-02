package com.example.data.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper
import android.media.projection.MediaProjection
import com.example.data.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class SubtitleCue(
    val index: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val originalText: String,
    var translatedText: String? = null
)

enum class TranslationSource { NONE, MICROPHONE, INTERNAL_AUDIO, FILE }
enum class TranslationDisplayMode { FAST, BALANCED, ACCURATE }

data class TranslationDisplaySettings(
    val mode: TranslationDisplayMode = TranslationDisplayMode.BALANCED,
    val minWordsForPartial: Int = 2,
    val pauseBeforeCommitMs: Long = 650L,
    val minDurationMs: Long = 2200L,
    val maxDurationMs: Long = 6500L,
    val fadeInMs: Long = 150L,
    val fadeOutMs: Long = 200L,
    val syncOffsetMs: Long = 0L
)

object LiveSubtitleEngine {
    private var appContext: Context? = null
    private val httpClient = OkHttpClient.Builder().build()
    private val translationCache = ConcurrentHashMap<String, String>()
    private val translationLimiter = Semaphore(2)
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechScope: CoroutineScope? = null
    private var speechTimeProvider: (() -> Long)? = null
    private val speechHandler = Handler(Looper.getMainLooper())
    private var speechRestartPending = false
    private var speechListening = false
    private var speechSessionActive = false
    private var voskRecognizer: VoskStreamingRecognizer? = null
    private var playbackRecognizer: VoskPlaybackRecognizer? = null
    private var lastRecognizedText = ""
    private var lastPartialText = ""
    private var partialSentenceContext = ""
    private var pendingFinalSentence = ""
    private var translationJob: Job? = null
    private var partialTranslationJob: Job? = null
    private var lastCommittedText = ""
    private var settings = TranslationDisplaySettings()
    private const val SETTINGS_PREFS = "live_translation_display"

    private val _currentSubtitleCues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    val currentSubtitleCues: StateFlow<List<SubtitleCue>> = _currentSubtitleCues.asStateFlow()
    private val _activeSubtitleText = MutableStateFlow("")
    val activeSubtitleText: StateFlow<String> = _activeSubtitleText.asStateFlow()
    private val _isLiveTranslating = MutableStateFlow(false)
    val isLiveTranslating: StateFlow<Boolean> = _isLiveTranslating.asStateFlow()
    private val _translationSource = MutableStateFlow(TranslationSource.NONE)
    val translationSource: StateFlow<TranslationSource> = _translationSource.asStateFlow()
    private val _displaySettings = MutableStateFlow(settings)
    val displaySettings: StateFlow<TranslationDisplaySettings> = _displaySettings.asStateFlow()

    var sourceLang: String = "auto"
    var targetLang: String = "ar"
    var isDualSubtitlesEnabled: Boolean = false

    fun formatSubtitleOutput(original: String, translated: String?): String {
        val trans = translated?.trim().orEmpty()
        val orig = original.trim()
        if (trans.isBlank()) return orig
        if (isDualSubtitlesEnabled && !trans.equals(orig, ignoreCase = true)) {
            return "$orig\n$trans"
        }
        return trans.ifBlank { orig }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        settings = TranslationDisplaySettings(
            mode = runCatching { TranslationDisplayMode.valueOf(prefs.getString("mode", settings.mode.name) ?: settings.mode.name) }.getOrDefault(settings.mode),
            minWordsForPartial = prefs.getInt("min_words", settings.minWordsForPartial),
            pauseBeforeCommitMs = prefs.getLong("pause_ms", settings.pauseBeforeCommitMs),
            minDurationMs = prefs.getLong("min_duration_ms", settings.minDurationMs),
            maxDurationMs = prefs.getLong("max_duration_ms", settings.maxDurationMs),
            fadeInMs = prefs.getLong("fade_in_ms", settings.fadeInMs),
            fadeOutMs = prefs.getLong("fade_out_ms", settings.fadeOutMs),
            syncOffsetMs = prefs.getLong("sync_offset_ms", settings.syncOffsetMs)
        )
        _displaySettings.value = settings
    }

    fun updateDisplaySettings(newSettings: TranslationDisplaySettings) {
        settings = newSettings
        _displaySettings.value = newSettings
        appContext?.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("mode", newSettings.mode.name)
            ?.putInt("min_words", newSettings.minWordsForPartial)
            ?.putLong("pause_ms", newSettings.pauseBeforeCommitMs)
            ?.putLong("min_duration_ms", newSettings.minDurationMs)
            ?.putLong("max_duration_ms", newSettings.maxDurationMs)
            ?.putLong("fade_in_ms", newSettings.fadeInMs)
            ?.putLong("fade_out_ms", newSettings.fadeOutMs)
            ?.putLong("sync_offset_ms", newSettings.syncOffsetMs)
            ?.apply()
    }

    fun clear() {
        stopMicrophoneRecognition()
        fadeOutJob?.cancel()
        fadeOutJob = null
        _currentSubtitleCues.value = emptyList()
        _activeSubtitleText.value = ""
        _isLiveTranslating.value = false
        lastCommittedText = ""
    }

    fun startMicrophoneRecognition(context: Context, timeProvider: () -> Long) {
        if (_translationSource.value == TranslationSource.INTERNAL_AUDIO) stopMicrophoneRecognition()
        _translationSource.value = TranslationSource.MICROPHONE
        speechTimeProvider = timeProvider
        speechSessionActive = true
        lastRecognizedText = ""
        lastPartialText = ""
        lastCommittedText = ""
        translationJob?.cancel()
        speechScope = CoroutineScope(Dispatchers.Main)
        val modelLanguage = VoskModelManager.canonicalLanguage(sourceLang)
        val localModel = VoskModelManager.modelDirectory(context, modelLanguage)
        if (localModel.isDirectory) {
            val local = VoskStreamingRecognizer(
                onPartial = { text -> partialSentenceContext = normalizeSentence(text) },
                onFinal = { text -> commitFinalSentence(text) },
                onFailure = { message -> DiagnosticLogger.w("LiveTranslator", message) }
            )
            if (local.start(localModel)) { voskRecognizer = local; _isLiveTranslating.value = true; return }
            local.stop()
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            DiagnosticLogger.w("LiveTranslator", "التعرف الصوتي غير متاح على هذا الجهاز")
            speechSessionActive = false; _translationSource.value = TranslationSource.NONE; return
        }
        if (speechSessionActive && speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    speechListening = false; lastPartialText = ""
                    commitFinalSentence(text)
                    scheduleSpeechRestart(700L)
                }
                override fun onError(error: Int) { speechListening = false; if (speechSessionActive) scheduleSpeechRestart(if (error == SpeechRecognizer.ERROR_NO_MATCH) 1200L else 1800L) }
                override fun onReadyForSpeech(params: Bundle?) { _isLiveTranslating.value = true }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotBlank() && text != lastPartialText) {
                        lastPartialText = text
                        partialSentenceContext = normalizeSentence(text)
                        schedulePartialTranslation(partialSentenceContext)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        scheduleSpeechRestart(250L)
    }

    private var fadeOutJob: Job? = null

    private fun commitFinalSentence(text: String) {
        val normalized = normalizeSentence(text)
        if (normalized.isBlank() || normalized.equals(lastCommittedText, true)) return
        val generation = speechSessionGeneration
        pendingFinalSentence = when {
            pendingFinalSentence.isBlank() -> normalized
            hasBoundary(pendingFinalSentence) -> normalized
            else -> "$pendingFinalSentence $normalized"
        }
        translationJob?.cancel()
        translationJob = speechScope?.launch {
            kotlinx.coroutines.delay(100L)
            val sentence = pendingFinalSentence.trim()
            pendingFinalSentence = ""
            if (sentence.isBlank() || sentence.equals(lastCommittedText, true)) return@launch
            lastRecognizedText = sentence; lastCommittedText = sentence
            partialTranslationJob?.cancel()
            val start = (speechTimeProvider?.invoke() ?: 0L) + settings.syncOffsetMs
            val duration = displayDurationFor(sentence)
            addLiveSpeechCue(sentence, start, duration, generation)
            scheduleSubtitleFadeOut(duration)
        }
    }

    private fun scheduleSubtitleFadeOut(durationMs: Long) {
        fadeOutJob?.cancel()
        fadeOutJob = speechScope?.launch {
            kotlinx.coroutines.delay(durationMs)
            if (_activeSubtitleText.value.isNotBlank()) {
                _activeSubtitleText.value = ""
            }
        }
    }

    private fun schedulePartialTranslation(text: String) {
        if (text.isBlank() || text.length < 3) return
        partialTranslationJob?.cancel()
        partialTranslationJob = speechScope?.launch {
            kotlinx.coroutines.delay(120L)
            val translated = translateDirect(text, sourceLang, targetLang)
            if (translated.isNotBlank() && speechSessionActive) {
                _activeSubtitleText.value = formatSubtitleOutput(text, translated)
                scheduleSubtitleFadeOut(3000L)
            }
        }
    }

    fun stopMicrophoneRecognition() {
        speechSessionActive = false; speechSessionGeneration++; speechRestartPending = false
        speechHandler.removeCallbacksAndMessages(null); translationJob?.cancel(); translationJob = null
        partialTranslationJob?.cancel(); partialTranslationJob = null
        speechRecognizer?.cancel(); speechRecognizer?.destroy(); speechRecognizer = null
        voskRecognizer?.stop(); voskRecognizer = null; playbackRecognizer?.stop(); playbackRecognizer = null
        speechScope?.cancel(); speechScope = null; speechTimeProvider = null; speechListening = false
        _isLiveTranslating.value = false; _translationSource.value = TranslationSource.NONE; lastPartialText = ""; partialSentenceContext = ""; pendingFinalSentence = ""
    }

    fun startPlaybackCapture(context: Context, projection: MediaProjection, timeProvider: () -> Long, onResult: (Boolean) -> Unit = {}) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) { projection.stop(); onResult(false); return }
        val requestedSource = sourceLang
        val modelLanguage = VoskModelManager.canonicalLanguage(requestedSource)
        val localModel = VoskModelManager.modelDirectory(context, modelLanguage)
        if (!localModel.isDirectory) {
            DiagnosticLogger.w("LiveTranslator", "لا يوجد نموذج Vosk للتعرف على اللغة $modelLanguage؛ لن يبدأ الالتقاط بنموذج لغة خاطئ")
            projection.stop(); onResult(false); return
        }
        stopMicrophoneRecognition(); _translationSource.value = TranslationSource.INTERNAL_AUDIO
        speechTimeProvider = timeProvider; speechSessionActive = true; lastCommittedText = ""; speechScope = CoroutineScope(Dispatchers.Main)
        val capture = VoskPlaybackRecognizer(
            onPartial = { text -> partialSentenceContext = normalizeSentence(text) },
            onFinal = { text -> commitFinalSentence(text) },
            onFailure = { message -> DiagnosticLogger.w("LiveTranslator", message) }
        )
        speechScope?.launch(Dispatchers.IO) {
            val started = capture.start(context, projection, localModel)
            withContext(Dispatchers.Main) {
                if (started && speechSessionActive) { playbackRecognizer = capture; _isLiveTranslating.value = true; _translationSource.value = TranslationSource.INTERNAL_AUDIO; onResult(true) }
                else { capture.stop(); speechSessionActive = false; _isLiveTranslating.value = false; _translationSource.value = TranslationSource.NONE; onResult(false) }
            }
        }
    }

    private var speechSessionGeneration: Long = 0L
    private fun normalizeSentence(text: String): String = text.replace(Regex("\\s+"), " ").trim()
    private fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    private fun hasBoundary(text: String): Boolean = Regex("[.!?؟！。]$").containsMatchIn(text.trim())

    private fun displayDurationFor(text: String): Long {
        val words = wordCount(text)
        return (settings.minDurationMs + words * 260L).coerceIn(settings.minDurationMs, settings.maxDurationMs)
    }

    private fun scheduleSpeechRestart(delayMs: Long) {
        if (!speechSessionActive || speechRestartPending) return
        speechRestartPending = true
        speechHandler.postDelayed({ speechRestartPending = false; restartSpeechRecognition() }, delayMs)
    }

    private fun restartSpeechRecognition() {
        val recognizer = speechRecognizer ?: return
        if (!speechSessionActive || speechListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (sourceLang == "auto") "en-US" else sourceLang.replace('_', '-'))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        }
        try { speechListening = true; recognizer.startListening(intent) } catch (e: Exception) { speechListening = false; DiagnosticLogger.w("LiveTranslator", "تعذر بدء التعرف الصوتي: ${e.message}") }
    }

    suspend fun loadAndTranslateFile(file: File, targetLanguage: String = "ar"): Boolean = withContext(Dispatchers.IO) { try { if (!file.exists()) false else loadAndTranslateText(file.readText(), file.name, targetLanguage) } catch (e: Exception) { DiagnosticLogger.e("LiveTranslator", "خطأ في معالجة ملف الترجمة: ${e.message}", e); false } }
    suspend fun loadAndTranslateUri(context: Context, uriString: String, targetLanguage: String = "ar"): Boolean = withContext(Dispatchers.IO) { try { val text = context.contentResolver.openInputStream(Uri.parse(uriString))?.bufferedReader()?.use { it.readText() } ?: return@withContext false; loadAndTranslateText(text, Uri.parse(uriString).lastPathSegment ?: "subtitle.vtt", targetLanguage) } catch (e: Exception) { DiagnosticLogger.e("LiveTranslator", "خطأ في قراءة ترجمة content URI: ${e.message}", e); false } }

    private suspend fun loadAndTranslateText(text: String, sourceName: String, targetLanguage: String): Boolean {
        return try {
            val parsedCues = parseVttOrSrt(text); DiagnosticLogger.i("LiveTranslator", "تم قراءة ${parsedCues.size} مقطع ترجمة من الملف: $sourceName")
            val preTranslateCount = minOf(30, parsedCues.size)
            for (i in 0 until preTranslateCount) parsedCues[i].translatedText = translateDirect(parsedCues[i].originalText, "auto", targetLanguage)
            _currentSubtitleCues.value = parsedCues; _isLiveTranslating.value = true
            for (i in preTranslateCount until parsedCues.size) if (parsedCues[i].translatedText == null) parsedCues[i].translatedText = translateDirect(parsedCues[i].originalText, "auto", targetLanguage)
            _currentSubtitleCues.value = parsedCues; true
        } catch (e: Exception) { DiagnosticLogger.e("LiveTranslator", "خطأ في معالجة ملف الترجمة: ${e.message}", e); false }
    }

    fun updatePlaybackPosition(currentPositionMs: Long) {
        if (_translationSource.value == TranslationSource.INTERNAL_AUDIO ||
            _translationSource.value == TranslationSource.MICROPHONE) {
            // In live audio capture or microphone mode, subtitle is pushed directly by real-time speech recognizer
            return
        }
        val cues = _currentSubtitleCues.value
        if (cues.isEmpty()) return
        val active = cues.firstOrNull { it.startTimeMs <= currentPositionMs && currentPositionMs <= it.endTimeMs }
        val newText = active?.let { formatSubtitleOutput(it.originalText, it.translatedText) }.orEmpty()
        if (_activeSubtitleText.value != newText) {
            _activeSubtitleText.value = newText
        }
    }

    suspend fun addLiveSpeechCue(originalText: String, timeOffsetMs: Long, durationMs: Long = 3000, expectedGeneration: Long? = null): SubtitleCue = withContext(Dispatchers.IO) {
        val translatedRaw = translateDirect(originalText, sourceLang, targetLang)
        val translated = translatedRaw.takeUnless { it.trim().equals(originalText.trim(), ignoreCase = true) }.orEmpty()
        if (expectedGeneration != null && (expectedGeneration != speechSessionGeneration || !speechSessionActive)) return@withContext SubtitleCue(-1, timeOffsetMs, timeOffsetMs, originalText, null)
        val current = _currentSubtitleCues.value.toMutableList()
        val cue = SubtitleCue(current.size + 1, timeOffsetMs, timeOffsetMs + durationMs, originalText, translated)
        current.add(cue)
        _currentSubtitleCues.value = current
        _activeSubtitleText.value = formatSubtitleOutput(originalText, translated)
        cue
    }

    suspend fun translateDirect(text: String, source: String = "auto", target: String = "ar"): String {
        if (text.isBlank()) return ""
        return translationLimiter.withPermit {
            val provider = TranslationProviderManager.provider.value
            val normalizedSource = if (source == "auto") detectLanguageFast(text) else source
            if (normalizedSource == target) return@withPermit text
            val cacheKey = "${provider.name}:$normalizedSource->$target:$text"
            translationCache[cacheKey]?.let { return@withPermit it }
            if (provider == TranslationProvider.ML_KIT_LOCAL) {
                val context = appContext ?: return@withPermit ""
                val result = MlKitTranslationManager.translate(context, text, normalizedSource, target, !TranslationProviderManager.allowCellularDownload)
                if (result.isNotBlank()) translationCache[cacheKey] = result
                return@withPermit result
            }
            translateOnline(text, normalizedSource, target)
        }
    }

    /** Fast Unicode-script detection; it avoids a network call and is used only for auto source selection. */
    private fun detectLanguageFast(text: String): String {
        val counts = mapOf(
            "ja" to Regex("[\\u3040-\\u30ff]").findAll(text).count(),
            "ko" to Regex("[\\uac00-\\ud7af]").findAll(text).count(),
            "ar" to Regex("[\\u0600-\\u06ff]").findAll(text).count(),
            "ru" to Regex("[\\u0400-\\u04ff]").findAll(text).count(),
            "zh" to Regex("[\\u4e00-\\u9fff]").findAll(text).count(),
            "th" to Regex("[\\u0e00-\\u0e7f]").findAll(text).count(),
            "hi" to Regex("[\\u0900-\\u097f]").findAll(text).count()
        )
        val winner = counts.maxByOrNull { it.value }
        return if (winner != null && winner.value >= 2) winner.key else "en"
    }

    private suspend fun translateOnline(text: String, source: String, target: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        val cacheKey = "ONLINE:$source->$target:$text"; translationCache[cacheKey]?.let { return@withContext it }
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8"); val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=$target&dt=t&q=$encodedText"
            val response = httpClient.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute(); val body = response.body?.string() ?: ""
            if (response.isSuccessful && body.isNotEmpty()) { val sentences = JSONArray(body).optJSONArray(0); val sb = StringBuilder(); if (sentences != null) for (i in 0 until sentences.length()) sb.append(sentences.optJSONArray(i)?.optString(0, "")); val result = sb.toString().trim().ifEmpty { text }; translationCache[cacheKey] = result; result } else ""
        } catch (e: Exception) { DiagnosticLogger.d("LiveTranslator", "تعذر الوصول إلى خدمة الترجمة عبر الإنترنت: ${e.message}"); "" }
    }

    private fun parseVttOrSrt(text: String): MutableList<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        text.replace("\r", "").split(Regex("\\n\\s*\\n")).forEach { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            val timeIndex = lines.indexOfFirst { it.contains(" --> ") }
            if (timeIndex >= 0 && timeIndex + 1 < lines.size) {
                val times = lines[timeIndex].split(" --> ")
                if (times.size >= 2) {
                    val original = lines.drop(timeIndex + 1).joinToString(" ")
                    if (original.isNotBlank()) cues.add(SubtitleCue(cues.size + 1, parseSubtitleTime(times[0]), parseSubtitleTime(times[1]), original))
                }
            }
        }
        return cues
    }

    private fun parseSubtitleTime(value: String): Long {
        val parts = value.trim().replace(',', '.').split(':')
        return try {
            when (parts.size) {
                3 -> ((parts[0].toLong() * 3600 + parts[1].toLong()) * 1000) + (parts[2].toDouble() * 1000).toLong()
                2 -> parts[0].toLong() * 60_000 + (parts[1].toDouble() * 1000).toLong()
                else -> (parts[0].toDouble() * 1000).toLong()
            }
        } catch (_: Exception) { 0L }
    }

    private fun formatSrtTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        return "%02d:%02d:%02d,%03d".format(Locale.US, safe / 3_600_000, (safe % 3_600_000) / 60_000, (safe % 60_000) / 1000, safe % 1000)
    }

    fun exportToSrtFile(context: Context, videoTitle: String): File? { val cues = _currentSubtitleCues.value; if (cues.isEmpty()) return null; return try { val dir = File(context.filesDir, "subtitles").apply { mkdirs() }; val cleanTitle = videoTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_"); val file = File(dir, "${cleanTitle}_ar_${System.currentTimeMillis()}.srt"); val sb = StringBuilder(); cues.forEachIndexed { i, cue -> sb.appendLine("${i + 1}"); sb.appendLine("${formatSrtTime(cue.startTimeMs)} --> ${formatSrtTime(cue.endTimeMs)}"); sb.appendLine(cue.translatedText ?: cue.originalText); sb.appendLine() }; file.writeText(sb.toString()); file } catch (_: Exception) { null } }
}
