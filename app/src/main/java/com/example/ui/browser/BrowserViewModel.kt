package com.example.ui.browser

import android.app.Application
import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.diagnostics.DiagnosticLogger
import com.example.data.downloader.DownloadManager
import com.example.data.downloader.MediaSniffer
import com.example.data.downloader.StorageDestination
import com.example.data.local.AppDatabase
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.BrowsingStatEntity
import com.example.data.local.entity.HistoryEntity
import com.example.data.local.entity.OfflinePageEntity
import com.example.data.privacy.AdBlockManager
import com.example.data.privacy.SecureDnsManager
import com.example.data.translation.TranslationProviderManager
import com.example.data.translation.LiveSubtitleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)

    // Tabs: restore normal tabs, then open a fresh tab for this session.
    private val tabPrefs = application.getSharedPreferences("browser_tabs_state", Context.MODE_PRIVATE)
    private val restoredTabs = loadPersistedTabs()
    private val _tabs = MutableStateFlow<List<BrowserTab>>(
        (restoredTabs + BrowserTab(url = "about:blank", title = "علامة تبويب جديدة")).ifEmpty {
            listOf(BrowserTab(url = "https://www.google.com", title = "Google"))
        }
    )
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>(_tabs.value.last().id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    val activeTab: StateFlow<BrowserTab?> = combine(_tabs, _activeTabId) { tabsList, id ->
        tabsList.firstOrNull { it.id == id } ?: tabsList.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _tabs.value.last())

    // Settings & Toggles
    val isAdBlockEnabled = MutableStateFlow(true)
    val isDataSaverEnabled = MutableStateFlow(false)
    val isNightReadingMode = MutableStateFlow(false)
    val isDohShieldEnabled = SecureDnsManager.isShieldActive
    val searchEngine = MutableStateFlow(SearchEngine.GOOGLE)
    val accentIndex = MutableStateFlow(0)
    val downloadDestination = MutableStateFlow(StorageDestination.PUBLIC_DOWNLOADS)
    val speedBooster = MutableStateFlow(true)

    // Data from Room
    val allDownloads = db.downloadDao().getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allBookmarks = db.bookmarkDao().getAllBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentHistory = db.historyDao().getRecentHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val offlinePages = db.offlinePageDao().getAllOfflinePages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val browsingStats = db.browsingStatDao().getRecentStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Sniffed media
    val sniffedMedia = MediaSniffer.sniffedMediaList

    init {
        DownloadManager.init(application)
        TranslationProviderManager.init(application)
        LiveSubtitleEngine.init(application)
        recordDailyStat()
    }

    // Tab Management
    fun addNewTab(url: String = "https://www.google.com", isIncognito: Boolean = false) {
        val newTab = BrowserTab(
            url = url,
            title = if (isIncognito) "تصفح خفي جديد" else "علامة تبويب جديدة",
            isIncognito = isIncognito
        )
        _tabs.value = _tabs.value + newTab
        _activeTabId.value = newTab.id
        persistTabs()
        DiagnosticLogger.d("BrowserTabs", "فتح تبويب جديد (${if (isIncognito) "خفي" else "عادي"}): $url")
    }

    fun closeTab(tabId: String) {
        val currentTabs = _tabs.value.toMutableList()
        val index = currentTabs.indexOfFirst { it.id == tabId }
        if (index != -1) {
            currentTabs.removeAt(index)
            if (currentTabs.isEmpty()) {
                currentTabs.add(BrowserTab(url = "https://www.google.com", title = "Google"))
            }
            _tabs.value = currentTabs
            if (_activeTabId.value == tabId) {
                val newActiveIndex = if (index >= currentTabs.size) currentTabs.size - 1 else index
                _activeTabId.value = currentTabs[newActiveIndex].id
            }
            persistTabs()
            DiagnosticLogger.d("BrowserTabs", "إغلاق التبويب: $tabId")
        }
    }

    fun selectTab(tabId: String) {
        _activeTabId.value = tabId
        persistTabs()
    }

    fun moveTab(fromIndex: Int, toIndex: Int) {
        val current = _tabs.value.toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val tab = current.removeAt(fromIndex)
        current.add(toIndex, tab)
        _tabs.value = current
        persistTabs()
    }

    fun closeAllTabsExcept(tabId: String) {
        val keep = _tabs.value.firstOrNull { it.id == tabId } ?: BrowserTab(url = "about:blank", title = "علامة تبويب جديدة")
        _tabs.value = listOf(keep)
        _activeTabId.value = keep.id
        persistTabs()
        DiagnosticLogger.i("BrowserTabs", "تم إغلاق جميع التبويبات باستثناء التبويب النشط")
    }

    fun updateCurrentTab(
        url: String? = null,
        title: String? = null,
        icon: android.graphics.Bitmap? = null,
        isLoading: Boolean? = null,
        progress: Int? = null,
        canGoBack: Boolean? = null,
        canGoForward: Boolean? = null,
        isSecure: Boolean? = null
    ) {
        val id = _activeTabId.value
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == id) {
                tab.copy(
                    url = url ?: tab.url,
                    title = title ?: tab.title,
                    icon = icon ?: tab.icon,
                    isLoading = isLoading ?: tab.isLoading,
                    progress = progress ?: tab.progress,
                    canGoBack = canGoBack ?: tab.canGoBack,
                    canGoForward = canGoForward ?: tab.canGoForward,
                    isSecure = isSecure ?: tab.isSecure
                )
            } else tab
        }
        persistTabs()
    }

    fun toggleDesktopMode() {
        val id = _activeTabId.value
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == id) tab.copy(desktopMode = !tab.desktopMode) else tab
        }
        persistTabs()
    }

    private fun persistTabs() {
        val json = JSONArray()
        _tabs.value.filter { !it.isIncognito && it.url.isNotBlank() && it.url != "about:blank" }.forEach { tab ->
            json.put(JSONObject().apply {
                put("id", tab.id)
                put("title", tab.title)
                put("url", tab.url)
                put("desktopMode", tab.desktopMode)
                put("isSecure", tab.isSecure)
            })
        }
        tabPrefs.edit().putString("tabs", json.toString()).apply()
    }

    private fun loadPersistedTabs(): List<BrowserTab> {
        return try {
            val raw = tabPrefs.getString("tabs", null) ?: return emptyList()
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.getJSONObject(i)
                    val url = item.optString("url", "about:blank")
                    if (url.isNotBlank() && url != "about:blank") add(
                        BrowserTab(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            title = item.optString("title", url),
                            url = url,
                            desktopMode = item.optBoolean("desktopMode", false),
                            isSecure = item.optBoolean("isSecure", url.startsWith("https://"))
                        )
                    )
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.w("BrowserTabs", "تعذر استعادة التبويبات: ${e.message}")
            emptyList()
        }
    }

    fun onPageFinished(url: String, title: String) {
        val current = activeTab.value ?: return
        if (!current.isIncognito && url.isNotBlank() && !url.startsWith("about:") && !url.startsWith("data:")) {
            viewModelScope.launch(Dispatchers.IO) {
                val existing = db.historyDao().getHistoryByUrl(url)
                if (existing != null) {
                    db.historyDao().incrementVisit(url, System.currentTimeMillis())
                } else {
                    db.historyDao().insertHistory(
                        HistoryEntity(
                            title = title.ifBlank { url },
                            url = url,
                            visitTime = System.currentTimeMillis()
                        )
                    )
                }
                incrementSitesVisitedStat()
            }
        }
    }

    fun toggleBookmark(url: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.bookmarkDao().getBookmarkByUrl(url)
            if (existing != null) {
                db.bookmarkDao().deleteBookmark(existing)
                DiagnosticLogger.i("Bookmarks", "تمت إزالة الموقع من الإشارات المرجعية: $url")
            } else {
                db.bookmarkDao().insertBookmark(
                    BookmarkEntity(
                        title = title.ifBlank { url },
                        url = url
                    )
                )
                DiagnosticLogger.s("Bookmarks", "تم حفظ الموقع في الإشارات المرجعية: $url")
            }
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.bookmarkDao().deleteBookmark(bookmark)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            db.historyDao().clearHistory()
            DiagnosticLogger.i("History", "تم مسح سجل التصفح بالكامل")
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.historyDao().deleteHistoryById(id)
        }
    }

    fun saveCurrentPageOffline(webView: WebView, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val tab = activeTab.value ?: return
        val url = tab.url
        val title = tab.title

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val dir = File(context.filesDir, "offline_pages").apply { mkdirs() }
                val cleanTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
                val fileName = "${cleanTitle}_${System.currentTimeMillis()}.mht"
                val destFile = File(dir, fileName)

                withContext(Dispatchers.Main) {
                    webView.saveWebArchive(destFile.absolutePath, false) { path ->
                        if (path != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                val entity = OfflinePageEntity(
                                    id = UUID.randomUUID().toString(),
                                    title = title,
                                    url = url,
                                    filePath = path,
                                    sizeBytes = File(path).length(),
                                    savedAt = System.currentTimeMillis()
                                )
                                db.offlinePageDao().insertOfflinePage(entity)
                                DiagnosticLogger.s("OfflinePages", "تم حفظ الصفحة للقراءة دون إنترنت بنجاح: $title")
                                withContext(Dispatchers.Main) { onSuccess() }
                            }
                        } else {
                            viewModelScope.launch(Dispatchers.Main) {
                                onError("تعذر حفظ الصفحة في أرشيف الويب")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل حفظ الصفحة") }
            }
        }
    }

    fun deleteOfflinePage(id: String, filePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { File(filePath).delete() } catch (_: Exception) {}
            db.offlinePageDao().deleteOfflinePageById(id)
        }
    }

    // Stats
    private fun recordDailyStat() {
        viewModelScope.launch(Dispatchers.IO) {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val existing = db.browsingStatDao().getStatForDate(dateStr)
            if (existing == null) {
                db.browsingStatDao().insertOrUpdate(
                    BrowsingStatEntity(
                        dateStr = dateStr,
                        sitesVisited = 1,
                        trackersBlocked = 0,
                        bytesSaved = 0L,
                        timeSpentSeconds = 60
                    )
                )
            }
        }
    }

    private suspend fun incrementSitesVisitedStat() {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val existing = db.browsingStatDao().getStatForDate(dateStr)
        if (existing != null) {
            db.browsingStatDao().insertOrUpdate(
                existing.copy(
                    sitesVisited = existing.sitesVisited + 1,
                    trackersBlocked = (AdBlockManager.blockedAdsCount).toInt(),
                    bytesSaved = existing.bytesSaved + 150_000L // Est saved
                )
            )
        }
    }
}
