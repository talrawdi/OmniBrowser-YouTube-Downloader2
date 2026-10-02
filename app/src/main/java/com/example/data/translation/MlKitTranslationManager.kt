package com.example.data.translation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.diagnostics.DiagnosticLogger
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class MlKitLanguage(val code: String, val displayName: String)

object MlKitTranslationManager {
    private const val CHANNEL_ID = "translation_models_channel"
    private const val NOTIFICATION_ID = 7301
    private val translators = ConcurrentHashMap<String, Translator>()
    private val readyPairs = ConcurrentHashMap.newKeySet<String>()
    private val installedLanguages = ConcurrentHashMap.newKeySet<String>()
    private val modelManager = RemoteModelManager.getInstance()

    // Google ML Kit Translate currently supports these 59 language codes.
    val supportedLanguages: List<MlKitLanguage> = listOf(
        "af" to "Afrikaans", "ar" to "العربية", "be" to "Belarusian", "bg" to "Български",
        "bn" to "বাংলা", "ca" to "Català", "cs" to "Čeština", "cy" to "Welsh",
        "da" to "Dansk", "de" to "Deutsch", "el" to "Ελληνικά", "en" to "English",
        "eo" to "Esperanto", "es" to "Español", "et" to "Eesti", "eu" to "Euskara",
        "fa" to "فارسی", "fi" to "Suomi", "fr" to "Français", "ga" to "Gaeilge",
        "gl" to "Galego", "gu" to "ગુજરાતી", "he" to "עברית", "hi" to "हिन्दी",
        "hr" to "Hrvatski", "ht" to "Kreyòl Ayisyen", "hu" to "Magyar", "id" to "Bahasa Indonesia",
        "is" to "Íslenska", "it" to "Italiano", "ja" to "日本語", "ka" to "ქართული",
        "kk" to "Қазақша", "km" to "ខ្មែរ", "kn" to "ಕನ್ನಡ", "ko" to "한국어",
        "lo" to "ລາວ", "lt" to "Lietuvių", "lv" to "Latviešu", "mk" to "Македонски",
        "ml" to "മലയാളം", "mn" to "Монгол", "mr" to "मराठी", "ms" to "Bahasa Melayu",
        "my" to "မြန်မာ", "ne" to "नेपाली", "nl" to "Nederlands", "no" to "Norsk",
        "pa" to "ਪੰਜਾਬੀ", "pl" to "Polski", "pt" to "Português", "ro" to "Română",
        "ru" to "Русский", "sk" to "Slovenčina", "sl" to "Slovenščina", "sq" to "Shqip",
        "sr" to "Српски", "sv" to "Svenska", "sw" to "Kiswahili", "ta" to "தமிழ்",
        "te" to "తెలుగు", "th" to "ไทย", "tl" to "Filipino", "tr" to "Türkçe",
        "uk" to "Українська", "ur" to "اردو", "vi" to "Tiếng Việt", "zh" to "中文"
    ).map { MlKitLanguage(it.first, it.second) }

    suspend fun refreshInstalledModels(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            val models = Tasks.await(modelManager.getDownloadedModels(TranslateRemoteModel::class.java))
            models.mapTo(mutableSetOf()) { it.language }
        }.getOrDefault(emptySet()).also { installed ->
            installedLanguages.clear()
            installedLanguages.addAll(installed)
            readyPairs.clear()
            supportedLanguages.forEach { source ->
                supportedLanguages.forEach { target ->
                    if (installedLanguages.contains(source.code) && installedLanguages.contains(target.code)) {
                        readyPairs.add("${source.code}->${target.code}")
                    }
                }
            }
        }
    }

    suspend fun ensureModel(context: Context, source: String, target: String, requireWifi: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (source == target) return@withContext true
        val key = "$source->$target"
        if (readyPairs.contains(key)) return@withContext true
        val translator = translator(source, target)
        try {
            TranslationProviderManager.setModelState(TranslationModelState.DOWNLOADING)
            notify(context, "تنزيل نموذج الترجمة", "جارٍ تجهيز $source → $target", true)
            val builder = DownloadConditions.Builder()
            if (requireWifi) builder.requireWifi()
            Tasks.await(translator.downloadModelIfNeeded(builder.build()))
            TranslationProviderManager.setModelState(TranslationModelState.INSTALLED)
            readyPairs.add(key)
            installedLanguages.add(source)
            installedLanguages.add(target)
            notify(context, "اكتمل نموذج الترجمة", "النموذج $source → $target جاهز", false)
            true
        } catch (e: Exception) {
            TranslationProviderManager.setModelState(TranslationModelState.ERROR)
            notify(context, "فشل نموذج الترجمة", "تعذر تجهيز النموذج: ${e.message ?: "خطأ غير معروف"}", false)
            DiagnosticLogger.w("MLKit", "فشل تنزيل نموذج $key: ${e.message}")
            false
        }
    }

    suspend fun deleteModel(language: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val model = TranslateRemoteModel.Builder(language).build()
            Tasks.await(modelManager.deleteDownloadedModel(model))
            installedLanguages.remove(language)
            readyPairs.removeIf { it.startsWith("$language->") }
            true
        }.getOrDefault(false)
    }

    suspend fun translate(context: Context, text: String, source: String, target: String, requireWifi: Boolean = false): String = withContext(Dispatchers.IO) {
        if (text.isBlank() || source == target) return@withContext text
        val translator = translator(source, target)
        try {
            if (!ensureModel(context, source, target, requireWifi)) return@withContext ""
            Tasks.await(translator.translate(text))?.trim().orEmpty()
        } catch (e: Exception) {
            DiagnosticLogger.w("MLKit", "فشل ترجمة النص محليًا: ${e.message}")
            ""
        }
    }

    fun isModelInstalled(context: Context, source: String, target: String): Boolean = readyPairs.contains("$source->$target")

    fun closeAll() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear(); readyPairs.clear()
    }

    private fun translator(source: String, target: String): Translator = translators.getOrPut("$source->$target") {
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
    }

    private fun notify(context: Context, title: String, text: String, ongoing: Boolean) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "نماذج الترجمة", NotificationManager.IMPORTANCE_LOW))
            manager?.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(title).setContentText(text).setOngoing(ongoing).setOnlyAlertOnce(true).build())
        } catch (e: SecurityException) {
            DiagnosticLogger.w("MLKit", "إذن إشعارات النماذج غير متاح: ${e.message}")
        } catch (e: Exception) {
            DiagnosticLogger.w("MLKit", "تعذر عرض إشعار النموذج: ${e.message}")
        }
    }
}
