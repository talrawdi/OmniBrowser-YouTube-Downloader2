package com.example.ui.browser

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.view.View
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.diagnostics.DiagnosticLogger
import com.example.data.downloader.DownloadManager
import com.example.data.downloader.MediaSniffer
import com.example.data.downloader.SniffedMedia
import com.example.data.downloader.StorageDestination
import com.example.data.local.entity.DownloadEntity
import com.example.data.privacy.AdBlockManager
import com.example.data.privacy.SecureDnsManager
import com.example.data.translation.LiveSubtitleEngine
import com.example.data.translation.PlaybackCaptureService
import com.example.data.translation.TranslationSource
import com.example.data.translation.VoskModelManager
import com.example.ui.analytics.AnalyticsDashboardDialog
import com.example.ui.bookmarks.BookmarksHistoryDialog
import com.example.ui.downloads.DownloadsScreen
import com.example.ui.offline.OfflinePagesDialog
import com.example.ui.player.VideoPlayerDialog
import com.example.ui.settings.DiagnosticsDialog
import com.example.ui.settings.SettingsDialog
import com.example.ui.translation.TranslationSettingsDialog
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.launch
import java.io.File

data class QuickShortcut(val title: String, val url: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

val POPULAR_SHORTCUTS = listOf(
    QuickShortcut("Google", "https://www.google.com", Icons.Default.Search),
    QuickShortcut("YouTube", "https://m.youtube.com", Icons.Default.PlayCircle),
    QuickShortcut("الجزيرة", "https://www.aljazeera.net", Icons.Default.Newspaper),
    QuickShortcut("ويكيبيديا", "https://ar.wikipedia.org", Icons.Default.MenuBook),
    QuickShortcut("أرشيف الإنترنت", "https://archive.org", Icons.Default.VideoLibrary),
    QuickShortcut("GitHub", "https://github.com", Icons.Default.Code),
    QuickShortcut("Reddit", "https://reddit.com", Icons.Default.Forum),
    QuickShortcut("BBC عربي", "https://www.bbc.com/arabic", Icons.Default.Public)
)

private val PAGE_TEXT_SCAN_SCRIPT = """
    (function(){
      var a=[], w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
      while(w.nextNode() && a.length<30){var n=w.currentNode,p=n.parentElement;
        if(!p||/^(SCRIPT|STYLE|NOSCRIPT|INPUT|TEXTAREA|SELECT|BUTTON)$/.test(p.tagName)) continue;
        var t=(n.nodeValue||'').trim(); if(t.length>=3 && t.length<=180) a.push(t);
      }
      window.OmniBridge&&window.OmniBridge.onPageText(JSON.stringify(a));
    })();
""".trimIndent()

private val PAGE_TRANSLATION_RESET_SCRIPT = """
    (function(){document.querySelectorAll('[data-omni-original]').forEach(function(n){n.textContent=n.getAttribute('data-omni-original');n.removeAttribute('data-omni-original');});})();
""".trimIndent()

class WebAppInterface(
    private val onMediaDetected: (url: String, title: String, width: Int, height: Int, duration: Long, subsJson: String) -> Unit,
    private val onYouTubeMediaDetected: (title: String, pageUrl: String, thumb: String, duration: Long, formatsJson: String, subsJson: String) -> Unit,
    private val onSubtitleCue: (text: String, startMs: Long, endMs: Long, cueId: Long) -> Unit = { _, _, _, _ -> },
    private val onPageText: (textJson: String) -> Unit = {}
) {
    @JavascriptInterface
    fun onMediaFound(url: String, title: String, width: Int, height: Int, duration: Long, subsJson: String) {
        onMediaDetected(url, title, width, height, duration, subsJson)
    }

    @JavascriptInterface
    fun onYouTubeMediaFound(title: String, pageUrl: String, thumb: String, duration: Long, formatsJson: String, subsJson: String) {
        onYouTubeMediaDetected(title, pageUrl, thumb, duration, formatsJson, subsJson)
    }

    @JavascriptInterface
    fun onSubtitleCue(text: String, startMs: Long, endMs: Long, cueId: Long) {
        onSubtitleCue.invoke(text, startMs, endMs, cueId)
    }

    @JavascriptInterface
    fun onPageText(textJson: String) = onPageText.invoke(textJson)
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(viewModel: BrowserViewModel) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val allDownloads by viewModel.allDownloads.collectAsState()
    val allBookmarks by viewModel.allBookmarks.collectAsState()
    val recentHistory by viewModel.recentHistory.collectAsState()
    val offlinePages by viewModel.offlinePages.collectAsState()
    val browsingStats by viewModel.browsingStats.collectAsState()
    val sniffedMedia by viewModel.sniffedMedia.collectAsState()
    val currentPageMedia = remember(sniffedMedia, activeTab?.url) {
        val pageUrl = activeTab?.url.orEmpty()
        sniffedMedia.filter { MediaSniffer.mediaBelongsToPage(it, pageUrl) }
    }

    val isAdBlock by viewModel.isAdBlockEnabled.collectAsState()
    val isDataSaver by viewModel.isDataSaverEnabled.collectAsState()
    val isNightReading by viewModel.isNightReadingMode.collectAsState()
    val searchEngine by viewModel.searchEngine.collectAsState()
    val downloadDest by viewModel.downloadDestination.collectAsState()

    var urlInputText by remember { mutableStateOf(activeTab?.url ?: "") }
    var currentWebView by remember { mutableStateOf<WebView?>(null) }
    val webViewCache = remember { mutableStateMapOf<String, WebView>() }
    var fullScreenCustomView by remember { mutableStateOf<View?>(null) }
    var fullScreenSubtitleView by remember { mutableStateOf<TextView?>(null) }
    var fullScreenCaptureButton by remember { mutableStateOf<TextView?>(null) }
    var pendingFileChooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pendingFileChooser?.onReceiveValue(uris.toTypedArray())
        pendingFileChooser = null
    }
    // Dialog sheets states
    var showTabSheet by remember { mutableStateOf(false) }
    var showMediaSheet by remember { mutableStateOf(false) }
    var showDownloadsScreen by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showOfflineSheet by remember { mutableStateOf(false) }
    var showAnalyticsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTranslationSettings by remember { mutableStateOf(false) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    var showVoiceModelDialog by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showReaderModeDialog by remember { mutableStateOf(false) }
    var readerModeTitle by remember { mutableStateOf("") }
    var readerModeParagraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var webSubtitleEnabled by remember { mutableStateOf(false) }
    var pageTranslationEnabled by remember { mutableStateOf(false) }
    var webLiveMicEnabled by remember { mutableStateOf(false) }
    var longPressHitResult by remember { mutableStateOf<WebView.HitTestResult?>(null) }
    var showLongPressDialog by remember { mutableStateOf(false) }
    val liveSubtitleText by LiveSubtitleEngine.activeSubtitleText.collectAsState()
    val translationSource by LiveSubtitleEngine.translationSource.collectAsState()
    val voiceModelStatus by VoskModelManager.status.collectAsState()
    val voiceModelInstalled = voiceModelStatus.installed || VoskModelManager.isInstalled(context)

    val projectionManager = remember(context) {
        context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val playbackCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            try {
                val serviceIntent = Intent(context, PlaybackCaptureService::class.java).apply {
                    putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                webLiveMicEnabled = true
                webSubtitleEnabled = true
                Toast.makeText(context, "جارٍ تشغيل التقاط الصوت الداخلي…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                DiagnosticLogger.e("PlaybackCapture", "فشل بدء الالتقاط: ${e.message}", e)
                Toast.makeText(context, "تعذر تشغيل الصوت الداخلي؛ استخدم الميكروفون", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Video Player state
    var activePlayingVideoTitle by remember { mutableStateOf<String?>(null) }
    var activePlayingVideoUri by remember { mutableStateOf<String?>(null) }
    var activePlayingHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activePlayingMimeType by remember { mutableStateOf("video/mp4") }
    var activePlayingSubtitlePath by remember { mutableStateOf<String?>(null) }
    var activePlayingIsPartial by remember { mutableStateOf(false) }
    var activePlayingProgressPercent by remember { mutableIntStateOf(0) }
    val localVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val title = it.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "فيديو من الهاتف" }
            activePlayingVideoTitle = title
            activePlayingVideoUri = it.toString()
            activePlayingHeaders = emptyMap()
            activePlayingMimeType = context.contentResolver.getType(it) ?: "video/*"
            activePlayingSubtitlePath = null
            activePlayingIsPartial = false
            Toast.makeText(context, "تم فتح الفيديو في المشغل الداخلي", Toast.LENGTH_SHORT).show()
        }
    }

    val activeDownloadsCount = allDownloads.count { it.status == "DOWNLOADING" }

    // Keep each normal/ private tab's WebView alive while the tab exists.
    LaunchedEffect(tabs) {
        val validIds = tabs.map { it.id }.toSet()
        webViewCache.keys.toList().filter { it !in validIds }.forEach { id ->
            webViewCache.remove(id)?.apply {
                stopLoading()
                clearHistory()
                clearCache(true)
                clearFormData()
                destroy()
            }
        }
    }

    // Sync input text when active tab changes
    LaunchedEffect(activeTab?.url) {
        val currentUrl = activeTab?.url ?: ""
        if (currentUrl != "about:blank") {
            urlInputText = currentUrl
        } else {
            urlInputText = ""
        }
    }

    LaunchedEffect(liveSubtitleText, webLiveMicEnabled, webSubtitleEnabled) {
        currentWebView?.evaluateJavascript(
            "window.OmniSubtitleSetEnabled && window.OmniSubtitleSetEnabled(${webSubtitleEnabled});window.OmniSubtitleSetLive && window.OmniSubtitleSetLive(${JSONObject.quote(if (webLiveMicEnabled) liveSubtitleText else "")});",
            null
        )
        fullScreenSubtitleView?.apply {
            text = if (webLiveMicEnabled && webSubtitleEnabled) liveSubtitleText else ""
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        fullScreenCaptureButton?.text = if (translationSource == TranslationSource.INTERNAL_AUDIO) "إيقاف ترجمة الصوت" else "ترجمة الصوت"
    }

    val translationSourceLabel = when (translationSource) {
        TranslationSource.INTERNAL_AUDIO -> "التقاط صوت الفيديو الداخلي"
        TranslationSource.MICROPHONE -> "الترجمة عبر الميكروفون"
        TranslationSource.FILE -> "الترجمة من ملف"
        TranslationSource.NONE -> ""
    }

    // Android Hardware Back button handling
    BackHandler(enabled = fullScreenCustomView != null) {
        val custom = fullScreenCustomView
        (custom?.parent as? ViewGroup)?.removeView(custom)
        (fullScreenSubtitleView?.parent as? ViewGroup)?.removeView(fullScreenSubtitleView)
        (fullScreenCaptureButton?.parent as? ViewGroup)?.removeView(fullScreenCaptureButton)
        fullScreenCustomView = null
        fullScreenSubtitleView = null
        fullScreenCaptureButton = null
        (context as? android.app.Activity)?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }
    BackHandler(enabled = currentWebView?.canGoBack() == true) {
        currentWebView?.goBack()
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (activeTab?.isIncognito == true) Color(0xFF1E1E1E) else MaterialTheme.colorScheme.surface
                    )
                    .statusBarsPadding()
            ) {
                // Top URL Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Security or Incognito Badge
                    IconButton(
                        onClick = {
                            Toast.makeText(
                                context,
                                if (activeTab?.isIncognito == true) "تصفح خفي فائق الخصوصية (لا يتم تسجيل أي سجل أو ملفات تعريف)"
                                else if (activeTab?.url?.startsWith("https://") == true) "اتصال مشفر وآمن (HTTPS)"
                                else "اتصال غير مشفر (HTTP)",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (activeTab?.isIncognito == true) {
                            Icon(Icons.Default.Security, contentDescription = "تصفح خفي", tint = Color.LightGray)
                        } else if (activeTab?.url?.startsWith("https://") == true) {
                            Icon(Icons.Default.Lock, contentDescription = "آمن", tint = Color(0xFF2E7D32))
                        } else {
                            Icon(Icons.Default.LockOpen, contentDescription = "غير آمن", tint = MaterialTheme.colorScheme.outline)
                        }
                    }

                    // URL / Search Input
                    OutlinedTextField(
                        value = urlInputText,
                        onValueChange = { urlInputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(26.dp),
                        placeholder = {
                            Text(
                                text = "ابحث أو اكتب عنوان موقع...",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                                val query = urlInputText.trim()
                                val target = if (query.startsWith("http://") || query.startsWith("https://")) {
                                    query
                                } else if (query.contains(".") && !query.contains(" ")) {
                                    "https://$query"
                                } else {
                                    "${searchEngine.searchUrl}${Uri.encode(query)}"
                                }
                                viewModel.updateCurrentTab(url = target, title = target, isLoading = true, isSecure = target.startsWith("https://"))
                                currentWebView?.loadUrl(target)
                            }
                        ),
                        trailingIcon = {
                            if (activeTab?.isLoading == true) {
                                IconButton(onClick = { currentWebView?.stopLoading() }) {
                                    Icon(Icons.Default.Close, contentDescription = "إيقاف", modifier = Modifier.size(20.dp))
                                }
                            } else {
                                IconButton(onClick = { currentWebView?.reload() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "تحديث", modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Tab Count Switcher Button
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .clickable { showTabSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${tabs.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // More Menu Button
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "المزيد")
                        }

                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (allBookmarks.any { it.url == activeTab?.url }) "إزالة الإشارة المرجعية" else "حفظ كإشارة مرجعية") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (allBookmarks.any { it.url == activeTab?.url }) Icons.Default.BookmarkAdded else Icons.Default.BookmarkBorder,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    activeTab?.let { viewModel.toggleBookmark(it.url, it.title) }
                                    showOptionsMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("وضع القراءة بدون تشتيت (Reader Mode)") },
                                leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    val script = """
                                    (function() {
                                      var article = document.querySelector('article') || document.querySelector('[role="main"]') || document.querySelector('main') || document.body;
                                      var title = document.querySelector('h1')?.innerText || document.title || 'مقال';
                                      var list = [];
                                      var ps = article.querySelectorAll('p, h2, h3, blockquote');
                                      for (var i = 0; i < ps.length; i++) {
                                        var t = ps[i].innerText ? ps[i].innerText.trim() : '';
                                        if (t.length > 25 && list.indexOf(t) === -1) list.push(t);
                                      }
                                      return JSON.stringify({ title: title, paragraphs: list });
                                    })()
                                    """.trimIndent()
                                    currentWebView?.evaluateJavascript(script) { result ->
                                        try {
                                            if (result == null || result == "null") {
                                                Toast.makeText(context, "لم يتم العثور على مقال في الصفحة", Toast.LENGTH_SHORT).show()
                                                return@evaluateJavascript
                                            }
                                            val unescaped = org.json.JSONTokener(result).nextValue().toString()
                                            val json = org.json.JSONObject(unescaped)
                                            val title = json.optString("title", "مقال")
                                            val array = json.optJSONArray("paragraphs")
                                            val list = mutableListOf<String>()
                                            if (array != null) {
                                                for (i in 0 until array.length()) list.add(array.getString(i))
                                            }
                                            if (list.isNotEmpty()) {
                                                readerModeTitle = title
                                                readerModeParagraphs = list
                                                showReaderModeDialog = true
                                            } else {
                                                Toast.makeText(context, "لم يتم العثور على نص مقال كافٍ في الصفحة", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "تعذر تجهيز وضع القراءة: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("حفظ الصفحة دون إنترنت") },
                                leadingIcon = { Icon(Icons.Default.OfflinePin, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    currentWebView?.let { wv ->
                                        viewModel.saveCurrentPageOffline(
                                            webView = wv,
                                            onSuccess = { Toast.makeText(context, "تم حفظ الصفحة بنجاح للقراءة دون إنترنت", Toast.LENGTH_SHORT).show() },
                                            onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                        )
                                    }
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("الموقع بنمط الكمبيوتر (Desktop)") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (activeTab?.desktopMode == true) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    viewModel.toggleDesktopMode()
                                    currentWebView?.let { wv ->
                                        val desktop = activeTab?.desktopMode != true
                                        wv.settings.userAgentString = if (desktop) {
                                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                        } else null
                                        wv.reload()
                                    }
                                    showOptionsMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(if (webSubtitleEnabled) "إيقاف الترجمة فوق الموقع" else "تشغيل الترجمة فوق الموقع") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Subtitles,
                                        contentDescription = null,
                                        tint = if (webSubtitleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                    )
                                },
                                onClick = {
                                    webSubtitleEnabled = !webSubtitleEnabled
                                    currentWebView?.evaluateJavascript(
                                        "window.OmniSubtitleSetEnabled && window.OmniSubtitleSetEnabled(${webSubtitleEnabled});",
                                        null
                                    )
                                    Toast.makeText(
                                        context,
                                        if (webSubtitleEnabled) "تم تشغيل الترجمة فوق مشغل الموقع" else "تم إيقاف الترجمة فوق مشغل الموقع",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    showOptionsMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(if (pageTranslationEnabled) "إيقاف ترجمة الصفحة" else "ترجمة الصفحة إلى ${LiveSubtitleEngine.targetLang}") },
                                leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                onClick = {
                                    pageTranslationEnabled = !pageTranslationEnabled
                                    currentWebView?.evaluateJavascript(if (pageTranslationEnabled) PAGE_TEXT_SCAN_SCRIPT else PAGE_TRANSLATION_RESET_SCRIPT, null)
                                    Toast.makeText(context, if (pageTranslationEnabled) "جارٍ ترجمة النصوص الظاهرة" else "تمت استعادة نصوص الصفحة", Toast.LENGTH_SHORT).show()
                                    showOptionsMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(if (translationSource == TranslationSource.MICROPHONE) "إيقاف الترجمة عبر الميكروفون" else "ترجمة صوت الموقع عبر الميكروفون") },
                                leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                                onClick = {
                                    val activity = context as? android.app.Activity
                                    if (!webLiveMicEnabled && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        activity?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), 422) }
                                        Toast.makeText(context, "اسمح بالميكروفون ثم اضغط الخيار مرة أخرى", Toast.LENGTH_LONG).show()
                                    } else {
                                        val enabling = translationSource != TranslationSource.MICROPHONE
                                        if (enabling && translationSource == TranslationSource.INTERNAL_AUDIO) {
                                            context.startService(Intent(context, PlaybackCaptureService::class.java).apply {
                                                action = PlaybackCaptureService.ACTION_STOP
                                            })
                                        }
                                        webLiveMicEnabled = enabling
                                        if (enabling) {
                                            webSubtitleEnabled = true
                                            LiveSubtitleEngine.startMicrophoneRecognition(context) { 0L }
                                        } else {
                                            LiveSubtitleEngine.stopMicrophoneRecognition()
                                        }
                                        showOptionsMenu = false
                                    }
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(if (voiceModelInstalled) "إدارة نموذج الترجمة الصوتية" else "تنزيل نموذج الترجمة الصوتية") },
                                leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    showVoiceModelDialog = true
                                }
                            )

                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                DropdownMenuItem(
                                    text = { Text(if (translationSource == TranslationSource.INTERNAL_AUDIO) "إيقاف التقاط الصوت الداخلي" else "تشغيل التقاط الصوت الداخلي (Android 10+)") },
                                    leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                                    onClick = {
                                        showOptionsMenu = false
                                        if (translationSource == TranslationSource.INTERNAL_AUDIO) {
                                            context.startService(Intent(context, PlaybackCaptureService::class.java).apply {
                                                action = PlaybackCaptureService.ACTION_STOP
                                            })
                                            LiveSubtitleEngine.stopMicrophoneRecognition()
                                            webLiveMicEnabled = false
                                            Toast.makeText(context, "تم إيقاف التقاط الصوت الداخلي", Toast.LENGTH_SHORT).show()
                                        } else if (!voiceModelInstalled) {
                                            showVoiceModelDialog = true
                                            Toast.makeText(context, "نزّل نموذج الترجمة أولاً", Toast.LENGTH_LONG).show()
                                        } else {
                                            playbackCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                                        }
                                    }
                                )
                            }

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("الصفحات المحفوظة (${offlinePages.size})") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    showOfflineSheet = true
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("تقارير التصفح والخصوصية") },
                                leadingIcon = { Icon(Icons.Default.QueryStats, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    showAnalyticsSheet = true
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("سجل التشخيص والأخطاء") },
                                leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showOptionsMenu = false
                                    showDiagnosticsSheet = true
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("الإعدادات وتخصيص المتصفح") },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    showSettingsSheet = true
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("إدارة مزود الترجمة والنماذج") },
                                leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    showTranslationSettings = true
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("فتح فيديو من الهاتف") },
                                leadingIcon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    localVideoLauncher.launch(arrayOf("video/*"))
                                }
                            )
                        }
                    }
                }

                // Loading Progress Bar
                if (activeTab?.isLoading == true) {
                    LinearProgressIndicator(
                        progress = { (activeTab?.progress ?: 0) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    lazyItems(tabs, key = { it.id }) { tab ->
                        Surface(
                            modifier = Modifier
                                .widthIn(min = 132.dp, max = 190.dp)
                                .height(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .clickable { viewModel.selectTab(tab.id) },
                            color = if (tab.id == activeTabId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = if (tab.id == activeTabId) 3.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (tab.icon != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = tab.icon!!.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        if (tab.isIncognito) Icons.Default.Security else Icons.Default.Language,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tab.title.ifBlank { "تبويب جديد" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                if (tab.isLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                }
                                IconButton(onClick = { viewModel.closeTab(tab.id) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "إغلاق التبويب", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    // Persistent prominent + New Tab button in the tab bar
                    item {
                        Surface(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .clickable { viewModel.addNewTab("https://www.google.com", isIncognito = false) },
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = "فتح تبويب جديد", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            // Bottom Browser Navigation Bar
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { currentWebView?.goBack() },
                        enabled = activeTab?.canGoBack == true
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }

                    IconButton(
                        onClick = { currentWebView?.goForward() },
                        enabled = activeTab?.canGoForward == true
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "تقدم")
                    }

                    IconButton(
                        onClick = {
                            viewModel.updateCurrentTab(url = "about:blank", title = "صفحة البداية")
                            currentWebView?.loadUrl("about:blank")
                        }
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "الرئيسية")
                    }

                    // Bookmarks & History
                    IconButton(onClick = { showBookmarksSheet = true }) {
                        Icon(Icons.Default.Bookmarks, contentDescription = "العلامات والسجل")
                    }

                    // Downloads Screen shortcut with badge
                    Box {
                        IconButton(onClick = { showDownloadsScreen = true }) {
                            Icon(Icons.Default.Download, contentDescription = "التنزيلات")
                        }
                        if (activeDownloadsCount > 0) {
                            Badge(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 4.dp, end = 4.dp)
                            ) {
                                Text("$activeDownloadsCount")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            // If active tab URL is about:blank -> show Start / Home Page
            if (activeTab?.url == "about:blank" || activeTab?.url.isNullOrBlank()) {
                StartHomePage(
                    isIncognito = activeTab?.isIncognito == true,
                    onOpenUrl = { url ->
                        viewModel.updateCurrentTab(url = url, title = url, isLoading = true, isSecure = url.startsWith("https://"))
                        currentWebView?.loadUrl(url)
                    }
                )
            } else {
                // Actual Chromium-based Android WebView
                key(activeTabId) {
                    AndroidView(
                        factory = { ctx ->
                            webViewCache[activeTabId] ?: WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    loadsImagesAutomatically = !isDataSaver
                                    mediaPlaybackRequiresUserGesture = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    // Do not surface stale offline error pages while a network is available.
                                    // Data saving is handled by media/image policies instead of forcing cache-only navigation.
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    javaScriptCanOpenWindowsAutomatically = true
                                    setSupportMultipleWindows(false)
                                }
                                // Google/YouTube authentication uses cross-site cookies in WebView.
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, activeTab?.isIncognito != true)
                                if (activeTab?.isIncognito == true) {
                                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                    settings.saveFormData = false
                                    settings.setGeolocationEnabled(false)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                    clearCache(true)
                                    clearHistory()
                                    clearFormData()
                                    clearSslPreferences()
                                }

                                setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                                    val suggestedName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                                    DownloadManager.startDownload(
                                        context = ctx,
                                        url = url,
                                        title = suggestedName,
                                        quality = "تنزيل مباشر",
                                        mimeType = mimetype ?: "application/octet-stream",
                                        pageUrl = activeTab?.url
                                    )
                                    Toast.makeText(ctx, "بدء تنزيل الملف: $suggestedName", Toast.LENGTH_SHORT).show()
                                }

                                setOnLongClickListener {
                                    val hit = hitTestResult
                                    val extra = hit.extra.orEmpty()
                                    val isActionable = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                                        hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE ||
                                        hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                                        hit.type == WebView.HitTestResult.PHONE_TYPE ||
                                        hit.type == WebView.HitTestResult.GEO_TYPE ||
                                        hit.type == WebView.HitTestResult.EMAIL_TYPE ||
                                        extra.isNotBlank()

                                    if (isActionable) {
                                        longPressHitResult = hit
                                        showLongPressDialog = true
                                        true
                                    } else {
                                        false
                                    }
                                }

                                addJavascriptInterface(
                                    WebAppInterface(
                                        onMediaDetected = { url, title, w, h, dur, subs ->
                                            MediaSniffer.onMediaUrlIntercepted(
                                                url = url,
                                                pageUrl = activeTab?.url ?: "",
                                                pageTitle = title,
                                                width = w,
                                                height = h,
                                                duration = dur,
                                                subtitlesJson = subs
                                            )
                                        },
                                        onYouTubeMediaDetected = { title, pageUrl, thumb, dur, formats, subs ->
                                            MediaSniffer.onYouTubeMediaReceivedFromJS(
                                                title = title,
                                                pageUrl = pageUrl,
                                                thumbnail = thumb,
                                                duration = dur,
                                                formatsJson = formats,
                                                subsJson = subs
                                            )
                                        },
                                        onSubtitleCue = { text, startMs, endMs, cueId ->
                                            Handler(Looper.getMainLooper()).post {
                                                if (!webSubtitleEnabled) return@post
                                                coroutineScope.launch {
                                                    val translated = LiveSubtitleEngine.translateDirect(text, "auto", LiveSubtitleEngine.targetLang)
                                                    currentWebView?.evaluateJavascript(
                                                        "window.OmniSubtitleSetTranslated && window.OmniSubtitleSetTranslated($cueId, ${JSONObject.quote(translated)});",
                                                        null
                                                    )
                                                }
                                            }
                                        },
                                        onPageText = { textJson ->
                                            if (pageTranslationEnabled) coroutineScope.launch {
                                                try {
                                                    val values = JSONArray(textJson)
                                                    val translations = JSONObject()
                                                    for (i in 0 until minOf(values.length(), 30)) {
                                                        val original = values.optString(i).trim()
                                                        if (original.length >= 3) {
                                                            val translated = LiveSubtitleEngine.translateDirect(original, "auto", LiveSubtitleEngine.targetLang)
                                                            if (translated.isNotBlank() && !translated.equals(original, true)) translations.put(original, translated)
                                                        }
                                                    }
                                                    currentWebView?.evaluateJavascript(
                                                        "window.OmniApplyPageTranslation && window.OmniApplyPageTranslation(${JSONObject.quote(translations.toString())});",
                                                        null
                                                    )
                                                } catch (e: Exception) {
                                                    DiagnosticLogger.w("PageTranslation", "فشل ترجمة نصوص الصفحة: ${e.message}")
                                                }
                                            }
                                        }
                                    ),
                                    "OmniBridge"
                                )

                                webChromeClient = object : WebChromeClient() {
                                    override fun onShowFileChooser(
                                        webView: WebView?,
                                        filePathCallback: ValueCallback<Array<Uri>>?,
                                        fileChooserParams: FileChooserParams?
                                    ): Boolean {
                                        pendingFileChooser?.onReceiveValue(null)
                                        pendingFileChooser = filePathCallback
                                        if (filePathCallback == null) return false
                                        val accepted = fileChooserParams?.acceptTypes
                                            ?.flatMap { it.split(",") }
                                            ?.map { it.trim() }
                                            ?.filter { it.isNotBlank() }
                                            ?.toTypedArray()
                                            ?.takeIf { it.isNotEmpty() }
                                            ?: arrayOf("*/*")
                                        return try {
                                            fileChooserLauncher.launch(accepted)
                                            true
                                        } catch (e: Exception) {
                                            pendingFileChooser = null
                                            DiagnosticLogger.w("WebView", "تعذر فتح منتقي الملفات: ${e.message}")
                                            false
                                        }
                                    }

                                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                        val custom = view ?: return
                                        val activity = ctx as? android.app.Activity ?: return
                                        if (fullScreenCustomView != null) {
                                            callback?.onCustomViewHidden()
                                            return
                                        }
                                        fullScreenCustomView = custom
                                        (activity.window.decorView as? FrameLayout)?.addView(
                                            custom,
                                            FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                        val overlay = TextView(ctx).apply {
                                            setTextColor(android.graphics.Color.WHITE)
                                            setBackgroundColor(android.graphics.Color.argb(190, 0, 0, 0))
                                            gravity = android.view.Gravity.CENTER
                                            textSize = 20f
                                            setPadding(18, 8, 18, 8)
                                            maxLines = 3
                                            isClickable = false
                                            isFocusable = false
                                            text = if (webLiveMicEnabled && webSubtitleEnabled) liveSubtitleText else ""
                                            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
                                        }
                                        fullScreenSubtitleView = overlay
                                        (activity.window.decorView as? FrameLayout)?.addView(
                                            overlay,
                                            FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                                android.view.Gravity.BOTTOM
                                            ).apply { bottomMargin = 90 }
                                        )
                                        val captureButton = TextView(ctx).apply {
                                            text = if (translationSource == TranslationSource.INTERNAL_AUDIO) "إيقاف ترجمة الصوت" else "ترجمة الصوت"
                                            setTextColor(android.graphics.Color.WHITE)
                                            setBackgroundColor(android.graphics.Color.argb(190, 30, 30, 30))
                                            gravity = android.view.Gravity.CENTER
                                            textSize = 14f
                                            setPadding(18, 10, 18, 10)
                                            isClickable = true
                                            setOnClickListener {
                                                if (translationSource == TranslationSource.INTERNAL_AUDIO) {
                                                    context.startService(Intent(context, PlaybackCaptureService::class.java).apply { action = PlaybackCaptureService.ACTION_STOP })
                                                    LiveSubtitleEngine.stopMicrophoneRecognition()
                                                    webLiveMicEnabled = false
                                                } else if (!voiceModelInstalled) {
                                                    showVoiceModelDialog = true
                                                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                                    playbackCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                                                }
                                            }
                                        }
                                        fullScreenCaptureButton = captureButton
                                        (activity.window.decorView as? FrameLayout)?.addView(
                                            captureButton,
                                            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin = 56; rightMargin = 16 }
                                        )
                                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                                        activity.window.decorView.systemUiVisibility = (
                                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                            )
                                    }

                                    override fun onHideCustomView() {
                                        val activity = ctx as? android.app.Activity ?: return
                                        val custom = fullScreenCustomView ?: return
                                        (custom.parent as? ViewGroup)?.removeView(custom)
                                        (fullScreenSubtitleView?.parent as? ViewGroup)?.removeView(fullScreenSubtitleView)
                                        (fullScreenCaptureButton?.parent as? ViewGroup)?.removeView(fullScreenCaptureButton)
                                        fullScreenCustomView = null
                                        fullScreenSubtitleView = null
                                        fullScreenCaptureButton = null
                                        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                                        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                                    }

                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        viewModel.updateCurrentTab(
                                            progress = newProgress,
                                            isLoading = newProgress < 100
                                        )
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank()) {
                                            viewModel.updateCurrentTab(title = title)
                                        }
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): WebResourceResponse? {
                                        val reqUrl = request?.url?.toString() ?: return null

                                        // 1. AdBlocker Check
                                        if (AdBlockManager.shouldBlockUrl(reqUrl)) {
                                            return WebResourceResponse("text/plain", "UTF-8", null)
                                        }

                                        // 2. Video Sniffer interceptor
                                        if (MediaSniffer.isMediaResource(reqUrl)) {
                                            val reqHeaders = request.requestHeaders?.toMutableMap() ?: mutableMapOf()
                                            val currentCookies = CookieManager.getInstance().getCookie(reqUrl)
                                                ?: CookieManager.getInstance().getCookie(activeTab?.url ?: "https://www.youtube.com")
                                            if (!currentCookies.isNullOrBlank()) {
                                                reqHeaders["Cookie"] = currentCookies
                                            }
                                            val currentReferer = activeTab?.url
                                            if (!reqHeaders.containsKey("Referer") && !currentReferer.isNullOrBlank()) {
                                                reqHeaders["Referer"] = currentReferer
                                            }
                                            MediaSniffer.onMediaUrlIntercepted(
                                                url = reqUrl,
                                                pageUrl = activeTab?.url ?: "",
                                                pageTitle = activeTab?.title ?: "فيديو مباشر",
                                                headers = reqHeaders
                                            )
                                        }

                                        return super.shouldInterceptRequest(view, request)
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val uri = request?.url ?: return false
                                        val scheme = uri.scheme?.lowercase()
                                        if (scheme != "http" && scheme != "https") {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                                context.startActivity(intent)
                                                return true
                                            } catch (_: Exception) {
                                                return true
                                            }
                                        }
                                        return false
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        if (!url.isNullOrBlank() && url != activeTab?.url) {
                                            viewModel.updateCurrentTab(url = url)
                                            MediaSniffer.checkAndSniffYouTubePage(url)
                                            view?.evaluateJavascript(MediaSniffer.snifferInjectionScript, null)
                                        }
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        url?.let {
                                            MediaSniffer.resetForNewPage(it)
                                            viewModel.updateCurrentTab(
                                                url = it,
                                                icon = favicon,
                                                isLoading = true,
                                                isSecure = it.startsWith("https://")
                                            )
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        url?.let {
                                            viewModel.updateCurrentTab(
                                                url = it,
                                                isLoading = false,
                                                canGoBack = view?.canGoBack() == true,
                                                canGoForward = view?.canGoForward() == true
                                            )
                                            viewModel.onPageFinished(it, view?.title ?: "")

                                            // Inject video sniffer and adblock DOM cosmetic filters
                                            view?.evaluateJavascript(MediaSniffer.snifferInjectionScript, null)
                                            view?.evaluateJavascript(WebSubtitleOverlay.script, null)
                                            view?.evaluateJavascript(
                                                "window.OmniSubtitleSetEnabled && window.OmniSubtitleSetEnabled(${webSubtitleEnabled});",
                                                null
                                            )
                                            if (isAdBlock) {
                                                view?.evaluateJavascript(AdBlockManager.cosmeticCssInjection, null)
                                            }
                                        }
                                    }
                                }

                                currentWebView = this
                                activeTab?.url?.let { loadUrl(it) }
                            }.also { webViewCache[activeTabId] = it }
                        },
                        update = { wv ->
                            currentWebView = wv
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Eye-Comfort Night Reading Tint Overlay
            if (isNightReading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2C1E0A).copy(alpha = 0.35f))
                )
            }

            if (translationSource != TranslationSource.NONE) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("● $translationSourceLabel مفعلة", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = {
                            if (translationSource == TranslationSource.INTERNAL_AUDIO) {
                                context.startService(Intent(context, PlaybackCaptureService::class.java).apply {
                                    action = PlaybackCaptureService.ACTION_STOP
                                })
                            }
                            LiveSubtitleEngine.stopMicrophoneRecognition()
                            webLiveMicEnabled = false
                            webSubtitleEnabled = false
                        }) { Text("إيقاف") }
                    }
                }
            }

            // Floating Pulsing Video Sniffer Badge
            if (currentPageMedia.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showMediaSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp, pressedElevation = 12.dp),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    text = {
                        Text(
                            text = if (currentPageMedia.size == 1) "تنزيل الفيديو" else "تنزيل (${currentPageMedia.size}) فيديوهات",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
        }
    }

    // Tab Manager Sheet
    if (showTabSheet) {
        TabManagerSheet(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelectTab = { viewModel.selectTab(it) },
            onCloseTab = { viewModel.closeTab(it) },
            onCloseAllExcept = { viewModel.closeAllTabsExcept(it) },
            onMoveTab = { from, to -> viewModel.moveTab(from, to) },
            onNewTab = { isIncognito -> viewModel.addNewTab(isIncognito = isIncognito) },
            onDismiss = { showTabSheet = false }
        )
    }

    // Video Detection & Download Options Sheet
    if (showMediaSheet) {
        VideoDetectionDialog(
            mediaList = currentPageMedia,
            currentDestination = downloadDest,
            onPlayOnline = { media, quality ->
                showMediaSheet = false
                activePlayingVideoTitle = media.title
                activePlayingVideoUri = quality.url
                activePlayingHeaders = MediaSniffer.capturedHeaders[quality.url]
                    ?: MediaSniffer.capturedHeaders[media.originalUrl]
                    ?: emptyMap()
                activePlayingMimeType = quality.mimeType.ifBlank { media.mimeType }
                activePlayingSubtitlePath = null
                activePlayingIsPartial = false
            },
            onStartDownload = { media, quality, withSub, dest ->
                DownloadManager.startDownload(
                    context = context,
                    url = quality.url,
                    title = media.title,
                    quality = quality.label,
                    subtitleUrl = null,
                    mimeType = if (quality.isAudioOnly) quality.mimeType else media.mimeType,
                    pageUrl = media.pageUrl.ifBlank { activeTab?.url },
                    destination = dest
                )
                Toast.makeText(context, "بدأ تنزيل '${media.title}' بجودة ${quality.label}", Toast.LENGTH_SHORT).show()
            },
            onDownloadSubtitle = { media, subtitleUrl ->
                DownloadManager.startDownload(
                    context = context,
                    url = subtitleUrl,
                    title = "${media.title} - ترجمة",
                    quality = "ترجمة فقط",
                    mimeType = if (subtitleUrl.endsWith(".srt", true)) "application/x-subrip" else "text/vtt",
                    pageUrl = media.pageUrl.ifBlank { activeTab?.url },
                    destination = downloadDest
                )
                Toast.makeText(context, "بدأ تنزيل ملف الترجمة", Toast.LENGTH_SHORT).show()
            },
            onOpenDiagnostics = { showDiagnosticsSheet = true },
            onDismiss = { showMediaSheet = false }
        )
    }

    // Downloads Screen
    if (showDownloadsScreen) {
        DownloadsScreen(
            downloads = allDownloads,
            onPlayVideo = { item, isPartial ->
                activePlayingVideoTitle = item.title
                activePlayingVideoUri = item.localPath
                activePlayingHeaders = emptyMap()
                activePlayingMimeType = item.mimeType
                activePlayingSubtitlePath = item.subtitlePath
            activePlayingIsPartial = isPartial
                val total = item.totalBytes
                activePlayingProgressPercent = if (total > 0) ((item.downloadedBytes.toFloat() / total) * 100).toInt() else 0
            },
            onOpenDiagnostics = { showDiagnosticsSheet = true },
            onOpenFile = { item -> openDownloadedFile(context, item) },
            onMoveTo = { item, destination ->
                DownloadManager.moveDownload(context, item.id, destination)
                Toast.makeText(context, "جارٍ نقل الملف مع الحفاظ على اسمه", Toast.LENGTH_SHORT).show()
            },
            onClose = { showDownloadsScreen = false }
        )
    }

    // Bookmarks & History
    if (showBookmarksSheet) {
        BookmarksHistoryDialog(
            bookmarks = allBookmarks,
            history = recentHistory,
            onOpenUrl = { url ->
                currentWebView?.loadUrl(url)
                viewModel.updateCurrentTab(url = url)
            },
            onDeleteBookmark = { viewModel.deleteBookmark(it) },
            onDeleteHistoryItem = { viewModel.deleteHistoryItem(it) },
            onClearHistory = { viewModel.clearHistory() },
            onDismiss = { showBookmarksSheet = false }
        )
    }

    // Offline Pages
    if (showOfflineSheet) {
        OfflinePagesDialog(
            pages = offlinePages,
            onOpenOfflinePage = { filePath ->
                currentWebView?.loadUrl("file://$filePath")
            },
            onDeletePage = { id, path -> viewModel.deleteOfflinePage(id, path) },
            onDismiss = { showOfflineSheet = false }
        )
    }

    // Analytics Dashboard
    if (showAnalyticsSheet) {
        AnalyticsDashboardDialog(
            stats = browsingStats,
            onDismiss = { showAnalyticsSheet = false }
        )
    }

    // Reader Mode Dialog
    if (showReaderModeDialog) {
        ReaderModeDialog(
            title = readerModeTitle,
            paragraphs = readerModeParagraphs,
            onDismiss = { showReaderModeDialog = false }
        )
    }

    // Settings
    if (showSettingsSheet) {
        SettingsDialog(
            viewModel = viewModel,
            onOpenDiagnostics = {
                showSettingsSheet = false
                showDiagnosticsSheet = true
            },
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (showTranslationSettings) {
        TranslationSettingsDialog(onDismiss = { showTranslationSettings = false })
    }

    // Diagnostics Log Center
    if (showDiagnosticsSheet) {
        DiagnosticsDialog(
            onDismiss = { showDiagnosticsSheet = false }
        )
    }

    // Long Press Context Menu Dialog for WebView Links & Images
    if (showLongPressDialog && longPressHitResult != null) {
        val hit = longPressHitResult!!
        val extra = hit.extra ?: ""
        val isLink = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val isImage = hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE

        AlertDialog(
            onDismissRequest = { showLongPressDialog = false },
            title = { Text(if (isLink && isImage) "خيارات الرابط والصورة" else if (isLink) "خيارات الرابط" else "خيارات الصورة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = extra, style = MaterialTheme.typography.bodySmall, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))

                    if (isLink) {
                        OutlinedButton(
                            onClick = {
                                showLongPressDialog = false
                                viewModel.addNewTab(url = extra, isIncognito = false)
                                Toast.makeText(context, "تم فتح الرابط في تبويب جديد", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Tab, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("فتح في تبويب جديد")
                        }

                        OutlinedButton(
                            onClick = {
                                showLongPressDialog = false
                                viewModel.addNewTab(url = extra, isIncognito = true)
                                Toast.makeText(context, "تم فتح الرابط في تبويب خفي", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("فتح في تبويب خفي")
                        }

                        OutlinedButton(
                            onClick = {
                                showLongPressDialog = false
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", extra))
                                Toast.makeText(context, "تم نسخ الرابط إلى الحافظة", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("نسخ الرابط")
                        }

                        OutlinedButton(
                            onClick = {
                                showLongPressDialog = false
                                val guessedName = android.webkit.URLUtil.guessFileName(extra, null, null)
                                DownloadManager.startDownload(
                                    context = context,
                                    url = extra,
                                    title = guessedName,
                                    quality = "تنزيل مباشر",
                                    mimeType = "application/octet-stream",
                                    pageUrl = activeTab?.url
                                )
                                Toast.makeText(context, "بدء تنزيل: $guessedName", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تنزيل الملف / الرابط")
                        }

                        OutlinedButton(
                            onClick = {
                                showLongPressDialog = false
                                try {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, extra)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "مشاركة الرابط"))
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("مشاركة الرابط")
                        }
                    }

                    if (isImage && extra.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                showLongPressDialog = false
                                val guessedName = android.webkit.URLUtil.guessFileName(extra, null, "image/*")
                                DownloadManager.startDownload(
                                    context = context,
                                    url = extra,
                                    title = guessedName,
                                    quality = "تنزيل صورة",
                                    mimeType = "image/*",
                                    pageUrl = activeTab?.url
                                )
                                Toast.makeText(context, "بدء تنزيل الصورة", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("حفظ وتنزيل الصورة")
                        }

                        OutlinedButton(
                            onClick = {
                                showLongPressDialog = false
                                viewModel.addNewTab(url = extra, isIncognito = false)
                                Toast.makeText(context, "تم فتح الصورة في تبويب جديد", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("فتح الصورة في تبويب جديد")
                        }

                        OutlinedButton(
                            onClick = {
                                showLongPressDialog = false
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Image URL", extra))
                                Toast.makeText(context, "تم نسخ رابط الصورة", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("نسخ رابط الصورة")
                        }
                    }

                    if (!isLink && !isImage && extra.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                showLongPressDialog = false
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Text", extra))
                                Toast.makeText(context, "تم نسخ النص", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("نسخ النص")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLongPressDialog = false }) { Text("إغلاق") }
            }
        )
    }

    if (showVoiceModelDialog) {
        AlertDialog(
            onDismissRequest = { if (!voiceModelStatus.running) showVoiceModelDialog = false },
            title = { Text("نماذج التعرف الصوتي Vosk") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val installedCount = VoskModelManager.installedModels(context).size
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("كتالوج النماذج الصوتية", fontWeight = FontWeight.Bold)
                            Text("النماذج اختيارية ولا تُضاف إلى APK. نزّل اللغة المطلوبة فقط، وسيتم التحقق من ملفاتها قبل التشغيل.", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            Text("المثبت: $installedCount من ${VoskModelManager.availableModels.size} • اللغة المطلوبة: ${LiveSubtitleEngine.sourceLang}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    VoskModelManager.availableModels.forEach { model ->
                        val installed = VoskModelManager.isInstalled(context, model.language)
                        val active = voiceModelStatus.language == model.language && voiceModelStatus.running
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(model.displayName, fontWeight = FontWeight.SemiBold)
                                    Text("${model.language} · ${model.sizeMb} MB تقريبًا • تنزيل اختياري", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    when {
                                        active -> Text("جارٍ التنزيل: ${voiceModelStatus.progress}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        installed -> Text("مثبت وجاهز", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        voiceModelStatus.language == model.language && voiceModelStatus.error != null -> Text(voiceModelStatus.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                        else -> Text("غير مثبت", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (active) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                } else if (installed) {
                                    TextButton(enabled = !voiceModelStatus.running, onClick = {
                                        VoskModelManager.deleteModel(context, model.language)
                                        Toast.makeText(context, "تم حذف نموذج ${model.displayName}", Toast.LENGTH_SHORT).show()
                                    }) { Text("حذف") }
                                } else {
                                    TextButton(enabled = !voiceModelStatus.running, onClick = {
                                        coroutineScope.launch { VoskModelManager.downloadModel(context, model.language) }
                                    }) { Text("تنزيل") }
                                }
                            }
                        }
                    }
                    if (voiceModelStatus.running) {
                        LinearProgressIndicator(progress = { voiceModelStatus.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = { TextButton(onClick = { if (!voiceModelStatus.running) showVoiceModelDialog = false }) { Text("إغلاق") } }
        )
    }

    // ExoPlayer Video Dialog (supports streaming partial cache or full video!)
    if (activePlayingVideoUri != null) {
        VideoPlayerDialog(
            videoTitle = activePlayingVideoTitle ?: "مشغل الفيديو",
            videoUriString = activePlayingVideoUri!!,
            requestHeaders = activePlayingHeaders,
            mimeType = activePlayingMimeType,
            subtitleFilePath = activePlayingSubtitlePath,
            isPartialDownload = activePlayingIsPartial,
            downloadProgressPercent = activePlayingProgressPercent,
            onDismiss = {
                activePlayingVideoUri = null
                activePlayingHeaders = emptyMap()
                activePlayingMimeType = "video/mp4"
                activePlayingVideoTitle = null
                activePlayingSubtitlePath = null
            },
            onRequestInternalAudioCapture = {
                if (translationSource == TranslationSource.INTERNAL_AUDIO) {
                    Toast.makeText(context, "التقاط الصوت الداخلي مفعّل بالفعل", Toast.LENGTH_SHORT).show()
                                } else if (!VoskModelManager.isInstalled(context, VoskModelManager.canonicalLanguage(LiveSubtitleEngine.sourceLang))) {
                    showVoiceModelDialog = true
                    Toast.makeText(context, "لا يوجد نموذج Vosk مثبت للغة ${LiveSubtitleEngine.sourceLang}. لن يتم تشغيل نموذج خاطئ.", Toast.LENGTH_LONG).show()
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    playbackCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            },
            onOpenTranslationSettings = { showTranslationSettings = true }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StartHomePage(
    isIncognito: Boolean,
    onOpenUrl: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var shortcutToDelete by remember { mutableStateOf<QuickShortcut?>(null) }
    var newShortcutTitle by remember { mutableStateOf("") }
    var newShortcutUrl by remember { mutableStateOf("") }

    val prefs = remember { context.getSharedPreferences("omni_speed_dials", android.content.Context.MODE_PRIVATE) }
    var shortcutsList by remember {
        val savedJson = prefs.getString("custom_list", null)
        val list = mutableListOf<QuickShortcut>()
        if (savedJson != null) {
            try {
                val arr = org.json.JSONArray(savedJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(QuickShortcut(obj.getString("title"), obj.getString("url"), Icons.Default.Language))
                }
            } catch (_: Exception) {}
        }
        mutableStateOf(if (list.isNotEmpty()) list else POPULAR_SHORTCUTS.toMutableList())
    }

    fun saveShortcuts(updated: List<QuickShortcut>) {
        shortcutsList = updated.toMutableList()
        val arr = org.json.JSONArray()
        for (s in updated) {
            val obj = org.json.JSONObject()
            obj.put("title", s.title)
            obj.put("url", s.url)
            arr.put(obj)
        }
        prefs.edit().putString("custom_list", arr.toString()).apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isIncognito) Color(0xFF121212) else MaterialTheme.colorScheme.background)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isIncognito) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = Color.LightGray,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "وضع التصفح الخفي المتقدم",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "لن يتم حفظ أي سجل، أو ملفات تعريف الارتباط، أو بيانات التصفح على جهازك.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )
        } else {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.omnibrowser_hero_banner_1790252555221),
                contentDescription = "OmniBrowser Hero Banner",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "OmniBrowser الذكي",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "متصفحك الفائق مع كاشف وتحميل الفيديو والترجمة الفورية",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Quick Shortcuts Header & Add Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "المواقع المفضلة والشائعة:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("إضافة موقع", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(shortcutsList) { shortcut ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.combinedClickable(
                        onClick = { onOpenUrl(shortcut.url) },
                        onLongClick = { shortcutToDelete = shortcut }
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = shortcut.icon,
                            contentDescription = shortcut.title,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = shortcut.title,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("إضافة اختصار جديد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newShortcutTitle,
                        onValueChange = { newShortcutTitle = it },
                        label = { Text("اسم الموقع") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newShortcutUrl,
                        onValueChange = { newShortcutUrl = it },
                        label = { Text("عنوان الرابط (URL)") },
                        placeholder = { Text("https://example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val title = newShortcutTitle.trim()
                        var url = newShortcutUrl.trim()
                        if (title.isNotBlank() && url.isNotBlank()) {
                            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                url = "https://$url"
                            }
                            val updated = shortcutsList.toMutableList().apply {
                                add(QuickShortcut(title, url, Icons.Default.Language))
                            }
                            saveShortcuts(updated)
                            newShortcutTitle = ""
                            newShortcutUrl = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("إلغاء") }
            }
        )
    }

    shortcutToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { shortcutToDelete = null },
            title = { Text("إزالة الموقع") },
            text = { Text("هل ترغب في إزالة '${target.title}' من المواقع المفضلة؟") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val updated = shortcutsList.toMutableList().apply { remove(target) }
                        saveShortcuts(updated)
                        shortcutToDelete = null
                    }
                ) {
                    Text("إزالة")
                }
            },
            dismissButton = {
                TextButton(onClick = { shortcutToDelete = null }) { Text("إلغاء") }
            }
        )
    }
}
