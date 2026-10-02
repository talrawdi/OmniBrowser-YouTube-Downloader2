package com.example.ui.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.downloader.DownloadManager
import com.example.data.downloader.MediaSniffer
import com.example.data.downloader.StorageDestination
import com.example.data.local.entity.DownloadEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntity>,
    onPlayVideo: (DownloadEntity, isPartial: Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenFile: (DownloadEntity) -> Unit = {},
    onMoveTo: (DownloadEntity, StorageDestination) -> Unit = { _, _ -> },
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var selectedFilterIndex by remember { mutableIntStateOf(0) }
    val activeCount = downloads.count { it.status == "DOWNLOADING" || it.status == "PAUSED" }
    val completedCount = downloads.count { it.status == "COMPLETED" }
    val failedCount = downloads.count { it.status == "FAILED" }
    val filterTabs = listOf("الكل (${downloads.size})", "قيد التنزيل ($activeCount)", "المكتملة ($completedCount)")

    var isTurboBoosted by remember { mutableStateOf(DownloadManager.speedBoosterEnabled) }

    val filteredList = when (selectedFilterIndex) {
        1 -> downloads.filter { it.status == "DOWNLOADING" || it.status == "PAUSED" }
        2 -> downloads.filter { it.status == "COMPLETED" }
        else -> downloads
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DownloadForOffline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مدير التنزيلات السريع", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "سجل التشخيص والأخطاء",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Turbo Booster Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (isTurboBoosted) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline
                        )
                        Switch(
                            checked = isTurboBoosted,
                            onCheckedChange = {
                                isTurboBoosted = it
                                DownloadManager.speedBoosterEnabled = it
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DownloadSummaryStat("قيد العمل", activeCount.toString(), MaterialTheme.colorScheme.primary)
                    DownloadSummaryStat("مكتمل", completedCount.toString(), Color(0xFF2E7D32))
                    DownloadSummaryStat("فشل", failedCount.toString(), if (failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Speed Boost Announcement
            if (isTurboBoosted) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.RocketLaunch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "وضع التسريع الفائق مفعّل (تنزيل متعدد الخيوط ومضاعفة السرعة)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Filter Tabs
            TabRow(selectedTabIndex = selectedFilterIndex) {
                filterTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedFilterIndex == index,
                        onClick = { selectedFilterIndex = index },
                        text = { Text(title, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "لا توجد تنزيلات في هذه القائمة",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredList, key = { it.id }) { item ->
                        DownloadItemRow(
                            download = item,
                            onPlay = { isPartial -> onPlayVideo(item, isPartial) },
                            onPause = { DownloadManager.pauseDownload(context, item.id) },
                            onResume = { DownloadManager.resumeDownload(context, item.id) },
                            onCancel = { DownloadManager.cancelDownload(context, item.id) },
                            onShare = { shareFile(context, item.localPath) },
                            onOpenFile = { onOpenFile(item) },
                            onMoveTo = { destination -> onMoveTo(item, destination) },
                            onOpenDiagnostics = onOpenDiagnostics
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadSummaryStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DownloadItemRow(
    download: DownloadEntity,
    onPlay: (isPartial: Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onOpenFile: () -> Unit,
    onMoveTo: (StorageDestination) -> Unit = {},
    onOpenDiagnostics: () -> Unit = {}
) {
    var moveMenuExpanded by remember { mutableStateOf(false) }
    val progress = if (download.totalBytes > 0) {
        (download.downloadedBytes.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val percent = (progress * 100).toInt()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Title & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = download.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(download.quality, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(24.dp)
                        )
                        if (download.subtitlePath != null) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("ترجمة مرفقة", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }

                // Status chip
                val statusColor = when (download.status) {
                    "COMPLETED" -> Color(0xFF2E7D32)
                    "DOWNLOADING" -> MaterialTheme.colorScheme.primary
                    "PAUSED" -> Color(0xFFF57C00)
                    "FAILED" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                }
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = when (download.status) {
                                "COMPLETED" -> Icons.Default.CheckCircle
                                "DOWNLOADING" -> Icons.Default.Downloading
                                "PAUSED" -> Icons.Default.PauseCircle
                                "FAILED" -> Icons.Default.ErrorOutline
                                else -> Icons.Default.Info
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = when (download.status) {
                                "COMPLETED" -> "مكتمل"
                                "DOWNLOADING" -> "قيد التنزيل ($percent%)"
                                "PAUSED" -> "متوقف مؤقتاً"
                                "FAILED" -> "فشل"
                                else -> download.status
                            },
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress Bar & Stats
            if (download.status == "DOWNLOADING" || download.status == "PAUSED") {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${MediaSniffer.formatFileSize(download.downloadedBytes)} / ${MediaSniffer.formatFileSize(download.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (download.status == "DOWNLOADING" && download.speedBytesPerSec > 0) {
                        Text(
                            text = "${MediaSniffer.formatFileSize(download.speedBytesPerSec)}/ثانية",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // In case of error
            if (download.status == "FAILED" && !download.errorReason.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = download.errorReason,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onOpenDiagnostics,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("معاينة السجل", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                // Play while downloading button (KEY FEATURE)
                if (download.status == "DOWNLOADING" || (download.status == "PAUSED" && download.downloadedBytes > 100_000)) {
                    Button(
                        onClick = { onPlay(true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("مشاهدة ما تم تنزيله")
                    }
                }

                if (download.status == "COMPLETED" && isVideoDownload(download)) {
                    Button(
                        onClick = { onPlay(false) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تشغيل")
                    }

                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "مشاركة")
                    }
                }

                if (download.status == "COMPLETED") {
                    IconButton(onClick = onOpenFile) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "فتح موقع الملف")
                    }
                    Box {
                        IconButton(onClick = { moveMenuExpanded = true }) {
                            Icon(Icons.Default.FolderCopy, contentDescription = "نقل الملف")
                        }
                        DropdownMenu(
                            expanded = moveMenuExpanded,
                            onDismissRequest = { moveMenuExpanded = false }
                        ) {
                            val destination = if (download.destinationType == StorageDestination.INTERNAL_VAULT.name) {
                                StorageDestination.PUBLIC_DOWNLOADS
                            } else StorageDestination.INTERNAL_VAULT
                            DropdownMenuItem(
                                text = { Text(if (destination == StorageDestination.PUBLIC_DOWNLOADS) "نقل إلى تنزيلات الجهاز" else "نقل إلى الخزنة الداخلية") },
                                onClick = {
                                    moveMenuExpanded = false
                                    onMoveTo(destination)
                                }
                            )
                        }
                    }
                }

                if (download.status == "DOWNLOADING") {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "إيقاف مؤقت")
                    }
                }

                if (download.status == "PAUSED" || download.status == "FAILED") {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "استئناف")
                    }
                }

                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "حذف",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun shareFile(context: Context, filePath: String) {
    try {
        val uri = if (filePath.startsWith("content://")) {
            Uri.parse(filePath)
        } else {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "الملف غير موجود في الذاكرة", Toast.LENGTH_SHORT).show()
                return
            }
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "مشاركة الفيديو"))
    } catch (e: Exception) {
        Toast.makeText(context, "فشل المشاركة: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun isVideoDownload(download: DownloadEntity): Boolean {
    val mime = download.mimeType.lowercase()
    if (mime.startsWith("video/") || mime.contains("mpegurl") || mime.contains("mp4") || mime.contains("webm")) return true
    val name = download.fileName.lowercase()
    return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") || name.endsWith(".mov") || name.endsWith(".m4v")
}
