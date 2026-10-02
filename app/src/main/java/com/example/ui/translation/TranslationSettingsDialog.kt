package com.example.ui.translation

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.translation.LiveSubtitleEngine
import com.example.data.translation.MlKitLanguage
import com.example.data.translation.MlKitTranslationManager
import com.example.data.translation.TranslationDisplayMode
import com.example.data.translation.TranslationModelState
import com.example.data.translation.TranslationProvider
import com.example.data.translation.TranslationProviderManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider by TranslationProviderManager.provider.collectAsState()
    val savedLanguages by TranslationProviderManager.languages.collectAsState()
    val modelState by TranslationProviderManager.modelState.collectAsState()
    val currentDisplay by LiveSubtitleEngine.displaySettings.collectAsState()
    val languages = remember { listOf(MlKitLanguage("auto", "تعرّف تلقائي")) + MlKitTranslationManager.supportedLanguages }
    var source by remember(savedLanguages.source) { mutableStateOf(savedLanguages.source) }
    var target by remember(savedLanguages.target) { mutableStateOf(savedLanguages.target) }
    var sourceMenu by remember { mutableStateOf(false) }
    var targetMenu by remember { mutableStateOf(false) }
    var cellular by remember { mutableStateOf(TranslationProviderManager.allowCellularDownload) }
    var busy by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var displayMode by remember(currentDisplay.mode) { mutableStateOf(currentDisplay.mode) }
    var minWords by remember(currentDisplay.minWordsForPartial) { mutableIntStateOf(currentDisplay.minWordsForPartial) }
    var syncOffset by remember(currentDisplay.syncOffsetMs) { mutableFloatStateOf(currentDisplay.syncOffsetMs.toFloat()) }

    LaunchedEffect(Unit) { installed = MlKitTranslationManager.refreshInstalledModels() }
    LaunchedEffect(source, target) { TranslationProviderManager.setLanguages(context, source, target) }

    fun languageLabel(code: String): String = languages.firstOrNull { it.code == code }?.displayName ?: code
    val pairReady = source != "auto" && source != target
    val modelInstalled = source != "auto" && installed.contains(source)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("إدارة الترجمة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("المزود واللغات والنماذج والعرض", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "إغلاق") }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("يتم استخدام المزود الذي تختاره فقط. لن يحدث تحويل تلقائي بين المحلي والإنترنت.", style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionTitle("مزود الترجمة", Icons.Default.CloudDownload)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = provider == TranslationProvider.ML_KIT_LOCAL, onClick = { TranslationProviderManager.setProvider(context, TranslationProvider.ML_KIT_LOCAL) }, label = { Text("ML Kit محلي") })
                FilterChip(selected = provider == TranslationProvider.ONLINE, onClick = { TranslationProviderManager.setProvider(context, TranslationProvider.ONLINE) }, label = { Text("عبر الإنترنت") })
            }
            Text(if (provider == TranslationProvider.ML_KIT_LOCAL) "يعمل بعد تثبيت النموذج المطلوب على الجهاز." else "يحتاج اتصالًا بالإنترنت أثناء الترجمة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("اللغة والزوج المطلوب", Icons.Default.Language)
            LanguageSelector("لغة المصدر", source, sourceMenu, languages, { sourceMenu = true }) { source = it; sourceMenu = false }
            LanguageSelector("لغة الترجمة", target, targetMenu, languages, { targetMenu = true }) { target = it; targetMenu = false }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("الزوج الحالي: ${languageLabel(source)} → ${languageLabel(target)}", fontWeight = FontWeight.SemiBold)
                    Text(modelStatusText(modelState, modelInstalled, source), style = MaterialTheme.typography.bodySmall, color = statusColor(modelState, modelInstalled))
                    if (source == "auto") Text("التعرف التلقائي مناسب للنصوص؛ للتعرف الصوتي المحلي يجب تثبيت نموذج Vosk مطابق للغة الصوت.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && provider == TranslationProvider.ML_KIT_LOCAL && pairReady, onClick = {
                    busy = true
                    scope.launch {
                        val ok = MlKitTranslationManager.ensureModel(context, source, target, !cellular)
                        installed = MlKitTranslationManager.refreshInstalledModels()
                        busy = false
                        Toast.makeText(context, if (ok) "تم تجهيز نموذج ${languageLabel(source)}" else "تعذر تجهيز النموذج", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.weight(1f)) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) } else Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(6.dp)); Text(if (busy) "جارٍ التجهيز" else "تجهيز النموذج")
                }
                OutlinedButton(enabled = !busy && modelInstalled, onClick = {
                    busy = true
                    scope.launch {
                        val ok = MlKitTranslationManager.deleteModel(source)
                        installed = MlKitTranslationManager.refreshInstalledModels()
                        busy = false
                        Toast.makeText(context, if (ok) "تم حذف النموذج" else "تعذر حذف النموذج", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.weight(.75f)) { Icon(Icons.Default.Delete, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("حذف") }
            }
            if (installed.isNotEmpty()) Text("النماذج المثبتة (${installed.size}): ${installed.sorted().joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("السماح ببيانات الهاتف", fontWeight = FontWeight.SemiBold); Text("أوقفه لفرض استخدام Wi‑Fi عند تنزيل النماذج.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(checked = cellular, onCheckedChange = { cellular = it; TranslationProviderManager.setAllowCellular(context, it) })
            }

            SectionTitle("عرض الترجمة والمزامنة", Icons.Default.CheckCircle)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(TranslationDisplayMode.FAST to "سريعة", TranslationDisplayMode.BALANCED to "متوازنة", TranslationDisplayMode.ACCURATE to "دقيقة").forEach { (mode, label) ->
                    FilterChip(selected = displayMode == mode, onClick = { displayMode = mode; LiveSubtitleEngine.updateDisplaySettings(currentDisplay.copy(mode = mode)) }, label = { Text(label) })
                }
            }
            Text("الحد الأدنى للنتيجة المرحلية: $minWords كلمات", style = MaterialTheme.typography.bodySmall)
            Slider(value = minWords.toFloat(), onValueChange = { minWords = it.toInt().coerceIn(1, 4); LiveSubtitleEngine.updateDisplaySettings(currentDisplay.copy(minWordsForPartial = minWords)) }, valueRange = 1f..4f, steps = 2)
            Text("إزاحة المزامنة: ${syncOffset.toInt()} مللي ثانية", style = MaterialTheme.typography.bodySmall)
            Slider(value = syncOffset, onValueChange = { syncOffset = it; LiveSubtitleEngine.updateDisplaySettings(currentDisplay.copy(syncOffsetMs = it.toLong())) }, valueRange = -1000f..1500f, steps = 24)
            Text("لا يتم تجاهل الجمل النهائية القصيرة؛ يؤثر الحد الأدنى فقط على النتائج المرحلية.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LanguageSelector(title: String, selected: String, expanded: Boolean, languages: List<MlKitLanguage>, onExpand: () -> Unit, onSelect: (String) -> Unit) {
    val label = languages.firstOrNull { it.code == selected }?.displayName ?: selected
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onExpand, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("$title: $label", modifier = Modifier.fillMaxWidth()) }
        DropdownMenu(expanded = expanded, onDismissRequest = { onSelect(selected) }, modifier = Modifier.heightIn(max = 360.dp)) {
            languages.forEach { language -> DropdownMenuItem(text = { Text("${language.displayName} (${language.code})") }, onClick = { onSelect(language.code) }) }
        }
    }
}

private fun modelStatusText(state: TranslationModelState, installed: Boolean, source: String): String = when {
    source == "auto" -> "النموذج: يُحدد تلقائيًا حسب النص"
    installed -> "النموذج: مثبت وجاهز للاستخدام"
    state == TranslationModelState.DOWNLOADING -> "النموذج: جارٍ التجهيز…"
    state == TranslationModelState.ERROR -> "النموذج: تعذر التجهيز، حاول مرة أخرى"
    else -> "النموذج: غير مثبت"
}

private fun statusColor(state: TranslationModelState, installed: Boolean): Color = when {
    installed -> Color(0xFF2E7D32)
    state == TranslationModelState.ERROR -> Color(0xFFB3261E)
    else -> Color.Unspecified
}
