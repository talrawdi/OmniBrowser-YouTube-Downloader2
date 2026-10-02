package com.example.data.translation

import android.content.Context
import com.example.data.diagnostics.DiagnosticLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TranslationProvider { ML_KIT_LOCAL, ONLINE }
enum class TranslationModelState { NOT_INSTALLED, DOWNLOADING, INSTALLED, ERROR }

data class TranslationLanguageSettings(val source: String = "auto", val target: String = "ar")

object TranslationProviderManager {
    private const val PREFS = "translation_provider_settings"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_SOURCE = "source_language"
    private const val KEY_TARGET = "target_language"
    private const val KEY_CELLULAR = "allow_cellular"
    private val _provider = MutableStateFlow(TranslationProvider.ONLINE)
    val provider: StateFlow<TranslationProvider> = _provider.asStateFlow()
    private val _languages = MutableStateFlow(TranslationLanguageSettings())
    val languages: StateFlow<TranslationLanguageSettings> = _languages.asStateFlow()
    private val _modelState = MutableStateFlow(TranslationModelState.NOT_INSTALLED)
    val modelState: StateFlow<TranslationModelState> = _modelState.asStateFlow()
    var allowCellularDownload: Boolean = true
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _provider.value = runCatching { TranslationProvider.valueOf(prefs.getString(KEY_PROVIDER, TranslationProvider.ONLINE.name) ?: "") }.getOrDefault(TranslationProvider.ONLINE)
        _languages.value = TranslationLanguageSettings(prefs.getString(KEY_SOURCE, "auto") ?: "auto", prefs.getString(KEY_TARGET, "ar") ?: "ar")
        allowCellularDownload = prefs.getBoolean(KEY_CELLULAR, true)
        LiveSubtitleEngine.sourceLang = _languages.value.source
        LiveSubtitleEngine.targetLang = _languages.value.target
    }

    fun setProvider(context: Context, provider: TranslationProvider) {
        _provider.value = provider
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROVIDER, provider.name).apply()
        DiagnosticLogger.i("TranslationProvider", "تم اختيار مزود الترجمة: $provider")
    }

    fun setLanguages(context: Context, source: String, target: String) {
        val normalized = TranslationLanguageSettings(source.ifBlank { "auto" }, target.ifBlank { "ar" })
        _languages.value = normalized
        LiveSubtitleEngine.sourceLang = normalized.source
        LiveSubtitleEngine.targetLang = normalized.target
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, normalized.source).putString(KEY_TARGET, normalized.target).apply()
    }

    fun setAllowCellular(context: Context, allowed: Boolean) {
        allowCellularDownload = allowed
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CELLULAR, allowed).apply()
    }

    fun setModelState(state: TranslationModelState) { _modelState.value = state }
}
