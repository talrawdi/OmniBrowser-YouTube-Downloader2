package com.example.data.translation

import android.content.Context
import com.example.data.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Optional offline speech models. Nothing is bundled in the APK; a model is downloaded only on user request. */
object VoskModelManager {
    private const val ROOT = "stt-models"
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class ModelInfo(val language: String, val displayName: String, val fileName: String, val sizeMb: Int, val url: String)
    data class Status(val language: String? = null, val running: Boolean = false, val progress: Int = 0, val installed: Boolean = false, val error: String? = null)

    // Official Vosk models suitable for mobile/desktop use. Large server models are intentionally not listed here.
    val availableModels: List<ModelInfo> = listOf(
        ModelInfo("en-us", "الإنجليزية الأمريكية", "vosk-model-small-en-us-0.15.zip", 40, "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
        ModelInfo("en-in", "الإنجليزية الهندية", "vosk-model-small-en-in-0.4.zip", 36, "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"),
        ModelInfo("ar", "العربية", "vosk-model-ar-mgb2-0.4.zip", 318, "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"),
        ModelInfo("ar-tn", "العربية التونسية", "vosk-model-small-ar-tn-0.1-linto.zip", 158, "https://alphacephei.com/vosk/models/vosk-model-small-ar-tn-0.1-linto.zip"),
        ModelInfo("ja", "اليابانية", "vosk-model-small-ja-0.22.zip", 48, "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"),
        ModelInfo("zh", "الصينية", "vosk-model-small-cn-0.22.zip", 42, "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"),
        ModelInfo("ko", "الكورية", "vosk-model-small-ko-0.22.zip", 82, "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip"),
        ModelInfo("fr", "الفرنسية", "vosk-model-small-fr-0.22.zip", 41, "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"),
        ModelInfo("de", "الألمانية", "vosk-model-small-de-0.15.zip", 45, "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"),
        ModelInfo("es", "الإسبانية", "vosk-model-small-es-0.42.zip", 39, "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"),
        ModelInfo("pt", "البرتغالية", "vosk-model-small-pt-0.3.zip", 31, "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"),
        ModelInfo("ru", "الروسية", "vosk-model-small-ru-0.22.zip", 45, "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"),
        ModelInfo("tr", "التركية", "vosk-model-small-tr-0.3.zip", 35, "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"),
        ModelInfo("it", "الإيطالية", "vosk-model-small-it-0.22.zip", 48, "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"),
        ModelInfo("nl", "الهولندية", "vosk-model-small-nl-0.22.zip", 39, "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip"),
        ModelInfo("hi", "الهندية", "vosk-model-small-hi-0.22.zip", 42, "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"),
        ModelInfo("uk", "الأوكرانية", "vosk-model-small-uk-v3-nano.zip", 73, "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip"),
        ModelInfo("kz", "الكازاخية", "vosk-model-small-kz-0.42.zip", 58, "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.42.zip"),
        ModelInfo("uz", "الأوزبكية", "vosk-model-small-uz-0.22.zip", 49, "https://alphacephei.com/vosk/models/vosk-model-small-uz-0.22.zip"),
        ModelInfo("pl", "البولندية", "vosk-model-small-pl-0.22.zip", 50, "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip"),
        ModelInfo("ca", "الكتالونية", "vosk-model-small-ca-0.4.zip", 42, "https://alphacephei.com/vosk/models/vosk-model-small-ca-0.4.zip"),
        ModelInfo("fa", "الفارسية", "vosk-model-small-fa-0.42.zip", 53, "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip"),
        ModelInfo("vi", "الفيتنامية", "vosk-model-small-vn-0.4.zip", 32, "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip"),
        ModelInfo("gu", "الغوجاراتية", "vosk-model-small-gu-0.42.zip", 100, "https://alphacephei.com/vosk/models/vosk-model-small-gu-0.42.zip"),
        ModelInfo("te", "التيلوغو", "vosk-model-small-te-0.42.zip", 58, "https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip"),
        ModelInfo("ka", "الجورجية", "vosk-model-small-ka-0.42.zip", 45, "https://alphacephei.com/vosk/models/vosk-model-small-ka-0.42.zip")
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun model(language: String): ModelInfo? = availableModels.firstOrNull { it.language == canonicalLanguage(language) }
    /** Converts UI/ML Kit/Android locale codes to the directory names used by Vosk. */
    fun canonicalLanguage(language: String): String {
        val normalized = language.trim().lowercase().replace('_', '-')
        return when (normalized) {
            "auto" -> "en-us"
            "en", "en-us", "en-gb", "english" -> "en-us"
            "en-in" -> "en-in"
            "zh-cn", "zh-hans", "zh" -> "zh"
            "ko-kr", "ko" -> "ko"
            "ja-jp", "ja" -> "ja"
            "fr-fr", "fr" -> "fr"
            "de-de", "de" -> "de"
            "es-es", "es" -> "es"
            "pt-br", "pt" -> "pt"
            "ru-ru", "ru" -> "ru"
            "tr-tr", "tr" -> "tr"
            "it-it", "it" -> "it"
            "nl-nl", "nl" -> "nl"
            "hi-in", "hi" -> "hi"
            else -> normalized
        }
    }

    fun modelDirectory(context: Context, language: String): File =
        File(context.filesDir, "$ROOT/${canonicalLanguage(language)}")

    fun isInstalled(context: Context, language: String = "en-us"): Boolean =
        modelDirectory(context, language).let { dir ->
            dir.isDirectory && File(dir, "am/final.mdl").exists() && File(dir, "conf/model.conf").exists()
        }

    fun installedModels(context: Context): List<ModelInfo> = availableModels.filter { isInstalled(context, it.language) }

    suspend fun downloadEnglish(context: Context) = downloadModel(context, "en-us")

    suspend fun downloadModel(context: Context, language: String) = withContext(Dispatchers.IO) {
        val canonical = canonicalLanguage(language)
        val info = model(canonical) ?: run { _status.value = Status(language, error = "نموذج اللغة غير متاح"); return@withContext }
        if (isInstalled(context, canonical)) { _status.value = Status(canonical, installed = true, progress = 100); return@withContext }
        _status.value = Status(language = canonical, running = true)
        val root = File(context.filesDir, ROOT).apply { mkdirs() }
        val partialZip = File(root, "${info.language}.zip.part")
        val staging = File(root, "${info.language}.installing")
        var archiveDownloaded = false
        try {
            downloadZipWithResume(info, partialZip)
            archiveDownloaded = true
            if (staging.exists()) staging.deleteRecursively(); staging.mkdirs(); unzipSafely(partialZip, staging)
            val extracted = findModelRoot(staging) ?: error("ملفات النموذج غير مكتملة")
            val required = listOf("am/final.mdl", "conf/model.conf")
            if (required.any { !File(extracted, it).exists() }) error("التحقق من ملفات نموذج ${info.displayName} فشل")
            val destination = File(root, info.language); if (destination.exists()) destination.deleteRecursively()
            extracted.copyRecursively(destination, overwrite = true); staging.deleteRecursively(); partialZip.delete()
            _status.value = Status(info.language, installed = isInstalled(context, info.language), progress = 100)
        } catch (e: Exception) {
            staging.deleteRecursively()
            if (archiveDownloaded) partialZip.delete()
            // On a network interruption the partial archive is intentionally kept for resume.
            _status.value = Status(info.language, error = e.message ?: "تعذر تنزيل النموذج")
            DiagnosticLogger.e("VoskModel", "فشل تنزيل نموذج ${info.language}: ${e.message}", e)
        }
    }

    fun deleteModel(context: Context, language: String) {
        val canonical = canonicalLanguage(language)
        File(context.filesDir, "$ROOT/$canonical").deleteRecursively()
        if (_status.value.language == canonical) _status.value = Status(canonical)
    }
    fun deleteEnglish(context: Context) = deleteModel(context, "en-us")

    /** Downloads in resumable chunks. A dropped connection keeps the partial ZIP for the next attempt. */
    private fun downloadZipWithResume(info: ModelInfo, partialZip: File) {
        var lastError: Exception? = null
        repeat(4) { attempt ->
            try {
                val existing = partialZip.length()
                val requestBuilder = Request.Builder()
                    .url(info.url)
                    .header("User-Agent", "OmniBrowser/1.0")
                    .header("Accept-Encoding", "identity")
                if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code != 200 && response.code != 206) error("فشل الاتصال بخادم النموذج: HTTP ${response.code}")
                    val body = response.body ?: error("استجابة النموذج فارغة")
                    val append = existing > 0L && response.code == 206
                    val startingBytes = if (append) existing else 0L
                    if (!append && existing > 0L) partialZip.delete()
                    val expected = body.contentLength().let { if (it > 0) startingBytes + it else -1L }
                    body.byteStream().use { input ->
                        FileOutputStream(partialZip, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = startingBytes
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                                copied += read
                                val percent = if (expected > 0) ((copied * 100) / expected).toInt().coerceIn(0, 99) else 0
                                _status.value = Status(info.language, true, percent)
                            }
                            output.fd.sync()
                        }
                    }
                    if (expected > 0 && partialZip.length() < expected) error("اكتمل الاتصال قبل نهاية النموذج (${partialZip.length()}/$expected)")
                }
                return
            } catch (e: Exception) {
                lastError = e
                DiagnosticLogger.w("VoskModel", "محاولة ${attempt + 1}/4 لتنزيل ${info.language} لم تكتمل: ${e.message}")
                if (attempt < 3) Thread.sleep((1000L shl attempt).coerceAtMost(8000L))
            }
        }
        throw IOException(lastError?.message ?: "تعذر تنزيل النموذج")
    }

    private fun findModelRoot(staging: File): File? {
        if (File(staging, "am/final.mdl").exists() && File(staging, "conf/model.conf").exists()) return staging
        return staging.walkTopDown().firstOrNull { it.isDirectory && File(it, "am/final.mdl").exists() && File(it, "conf/model.conf").exists() }
    }
    private fun unzipSafely(zipFile: File, destination: File) { ZipInputStream(FileInputStream(zipFile).buffered()).use { zip -> var entry = zip.nextEntry; val root = destination.canonicalFile; while (entry != null) { val output = File(destination, entry.name).canonicalFile; require(output.path == root.path || output.path.startsWith(root.path + File.separator)) { "مسار ZIP غير آمن" }; if (entry.isDirectory) output.mkdirs() else { output.parentFile?.mkdirs(); FileOutputStream(output).use { out -> zip.copyTo(out) } }; zip.closeEntry(); entry = zip.nextEntry } } }
}
