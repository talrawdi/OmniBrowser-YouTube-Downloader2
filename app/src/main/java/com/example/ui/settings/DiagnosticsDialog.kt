package com.example.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.diagnostics.DiagnosticLogger
import com.example.data.diagnostics.LogLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logs by DiagnosticLogger.logsFlow.collectAsState()
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    var selectedTagFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val errorCount = logs.count { it.level == LogLevel.ERROR }

    val filteredLogs = logs.filter { log ->
        val matchesLevel = (selectedLevel == null || log.level == selectedLevel)
        val matchesTag = (selectedTagFilter == null || log.tag.contains(selectedTagFilter!!, ignoreCase = true))
        val matchesSearch = if (searchQuery.isBlank()) true else {
            log.message.contains(searchQuery, ignoreCase = true) ||
            log.tag.contains(searchQuery, ignoreCase = true) ||
            (log.details?.contains(searchQuery, ignoreCase = true) == true)
        }
        matchesLevel && matchesTag && matchesSearch
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = if (errorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "مركز تشخيص النظام وسجل الأخطاء",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (errorCount > 0) {
                            Text(
                                text = "تنبيه: يوجد $errorCount أخطاء مسجلة تحتاج للمعاينة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("بحث في السجلات (اسم الفيديو، الخطأ، الخادم...)") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "مسح")
                        }
                    }
                },
                singleLine = true
            )

            // Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedLevel == null && selectedTagFilter == null,
                    onClick = {
                        selectedLevel = null
                        selectedTagFilter = null
                    },
                    label = { Text("الكل (${logs.size})") }
                )
                FilterChip(
                    selected = selectedLevel == LogLevel.ERROR,
                    onClick = {
                        selectedLevel = if (selectedLevel == LogLevel.ERROR) null else LogLevel.ERROR
                        selectedTagFilter = null
                    },
                    label = { Text("أخطاء ($errorCount)") },
                    leadingIcon = {
                        Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                    }
                )
                FilterChip(
                    selected = selectedTagFilter == "Downloader",
                    onClick = {
                        selectedTagFilter = if (selectedTagFilter == "Downloader") null else "Downloader"
                        selectedLevel = null
                    },
                    label = { Text("التنزيلات ⬇️") }
                )
                FilterChip(
                    selected = selectedTagFilter == "MediaSniffer",
                    onClick = {
                        selectedTagFilter = if (selectedTagFilter == "MediaSniffer") null else "MediaSniffer"
                        selectedLevel = null
                    },
                    label = { Text("كاشف الفيديو 🎬") }
                )
                FilterChip(
                    selected = selectedTagFilter == "VideoPlayer",
                    onClick = {
                        selectedTagFilter = if (selectedTagFilter == "VideoPlayer") null else "VideoPlayer"
                        selectedLevel = null
                    },
                    label = { Text("المشغل 📽️") }
                )
                FilterChip(
                    selected = selectedLevel == LogLevel.SUCCESS,
                    onClick = {
                        selectedLevel = if (selectedLevel == LogLevel.SUCCESS) null else LogLevel.SUCCESS
                        selectedTagFilter = null
                    },
                    label = { Text("نجاح 🟢") }
                )
            }

            Text(
                text = "انقر على أي سطر أدناه لنسخه فوراً إلى الحافظة:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Logs Box
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 220.dp, max = 360.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("لا توجد سجلات مطابقة للمعايير المحددة", color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredLogs, key = { it.id }) { log ->
                            val color = when (log.level) {
                                LogLevel.ERROR -> Color(0xFFFF5252)
                                LogLevel.WARN -> Color(0xFFFFD740)
                                LogLevel.SUCCESS -> Color(0xFF69F0AE)
                                LogLevel.INFO -> Color(0xFF40C4FF)
                                LogLevel.DEBUG -> Color(0xFFB0BEC5)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF282828))
                                    .clickable {
                                        val singleLogReport = buildString {
                                            appendLine("[${log.formattedTime}] [${log.level}] [${log.tag}]")
                                            appendLine("Message: ${log.message}")
                                            if (!log.details.isNullOrBlank()) {
                                                appendLine("Details:\n${log.details}")
                                            }
                                        }
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("OmniLog", singleLogReport))
                                        Toast.makeText(context, "تم نسخ هذا السجل إلى الحافظة بنجاح", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "[${log.formattedTime}]",
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "[${log.tag}]",
                                        color = color,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = log.message,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (!log.details.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = log.details,
                                        color = Color(0xFFFF8A80),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .background(Color(0x33FF5252), RoundedCornerShape(4.dp))
                                            .padding(4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons: Copy All, Share, Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val report = DiagnosticLogger.getSystemDiagnosticReport()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OmniBrowser Report", report))
                        Toast.makeText(context, "تم نسخ تقرير التشخيص بالكامل إلى الحافظة", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1.2f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("نسخ كل السجلات", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        val report = DiagnosticLogger.getSystemDiagnosticReport()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "تقرير تشخيص أخطاء OmniBrowser")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "مشاركة تقرير التشخيص"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("مشاركة")
                }

                IconButton(
                    onClick = {
                        DiagnosticLogger.clear()
                        Toast.makeText(context, "تم مسح السجلات", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "مسح", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
