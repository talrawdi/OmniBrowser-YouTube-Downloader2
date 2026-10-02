package com.example.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.example.data.backup.BackupRestoreManager
import com.example.data.downloader.DownloadManager
import com.example.data.downloader.StorageDestination
import com.example.data.privacy.AdBlockManager
import com.example.data.privacy.DnsProvider
import com.example.data.privacy.SecureDnsManager
import com.example.ui.browser.ACCENT_THEMES
import com.example.ui.browser.BrowserViewModel
import com.example.ui.browser.SearchEngine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    viewModel: BrowserViewModel,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isAdBlock by remember { mutableStateOf(viewModel.isAdBlockEnabled.value) }
    var isDataSaver by remember { mutableStateOf(viewModel.isDataSaverEnabled.value) }
    var isNightReading by remember { mutableStateOf(viewModel.isNightReadingMode.value) }
    var isDohActive by remember { mutableStateOf(SecureDnsManager.isShieldActive.value) }
    var currentDestination by remember { mutableStateOf(viewModel.downloadDestination.value) }
    var currentEngine by remember { mutableStateOf(viewModel.searchEngine.value) }
    var currentAccent by remember { mutableIntStateOf(viewModel.accentIndex.value) }

    // Backup restore picker
    val restorePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val success = BackupRestoreManager.restoreFromFile(context, uri)
                if (success) {
                    Toast.makeText(context, "تمت استعادة البيانات والإشارات المرجعية بنجاح!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "فشلت الاستعادة، تأكد من صحة الملف وكلمة التشفير", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "الإعدادات وتخصيص المتصفح",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // SECTION 1: Privacy & AdBlock
            Text(
                text = "الخصوصية والحماية المتقدمة (بدون خوادم وسيطة):",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // AdBlock switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("حظر الإعلانات والتعقب", fontWeight = FontWeight.Bold)
                            Text("حظر إعلانات البانر، النوافذ المنبثقة وتعقب التصفح", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isAdBlock,
                            onCheckedChange = {
                                isAdBlock = it
                                viewModel.isAdBlockEnabled.value = it
                                AdBlockManager.isEnabled = it
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    // DoH Shield switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("درع تشفير DNS (DoH Shield)", fontWeight = FontWeight.Bold)
                            Text("حماية وتشفير الاستعلامات مباشرة مع Cloudflare دون وسيط", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isDohActive,
                            onCheckedChange = {
                                isDohActive = it
                                SecureDnsManager.toggleShield(it)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    // Data saver switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("وضع توفير البيانات", fontWeight = FontWeight.Bold)
                            Text("تحسين استهلاك الإنترنت وتسريع تحميل الصفحات", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isDataSaver,
                            onCheckedChange = {
                                isDataSaver = it
                                viewModel.isDataSaverEnabled.value = it
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    // Night reading tint switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("الوضع الليلي المريح للعين", fontWeight = FontWeight.Bold)
                            Text("تقليل السطوع وإراحة العين أثناء القراءة ليلاً", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isNightReading,
                            onCheckedChange = {
                                isNightReading = it
                                viewModel.isNightReadingMode.value = it
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SECTION 2: Download Destination
            Text(
                text = "إعدادات تنزيل الفيديو:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("المجلد الافتراضي لحفظ مقاطع الفيديو:", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = currentDestination == StorageDestination.PUBLIC_DOWNLOADS,
                            onClick = {
                                currentDestination = StorageDestination.PUBLIC_DOWNLOADS
                                viewModel.downloadDestination.value = StorageDestination.PUBLIC_DOWNLOADS
                                DownloadManager.preferredDestination = StorageDestination.PUBLIC_DOWNLOADS
                            },
                            label = { Text("تنزيلات الجهاز") }
                        )
                        FilterChip(
                            selected = currentDestination == StorageDestination.INTERNAL_VAULT,
                            onClick = {
                                currentDestination = StorageDestination.INTERNAL_VAULT
                                viewModel.downloadDestination.value = StorageDestination.INTERNAL_VAULT
                                DownloadManager.preferredDestination = StorageDestination.INTERNAL_VAULT
                            },
                            label = { Text("المجلد الخاص المشفر") }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "محرك يوتيوب المحلي (بدون خادم / On-Device):",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "يعمل 100% داخل الهاتف بدون أي وسيط أو سيرفر خارجي. يحلل دقات 1080p و 720p HD و 480p والصوت MP3/M4A مباشرة مع فك التشفير التلقائي وتجديد الروابط المنتهية.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SECTION 3: Search Engine & Customization
            Text(
                text = "محرك البحث والمظهر:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("محرك البحث الافتراضي:", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SearchEngine.values().forEach { engine ->
                            FilterChip(
                                selected = currentEngine == engine,
                                onClick = {
                                    currentEngine = engine
                                    viewModel.searchEngine.value = engine
                                },
                                label = { Text(engine.displayName) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("لون الواجهة المفضل:", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ACCENT_THEMES.forEachIndexed { index, theme ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(theme.primaryColorHex))
                                    .clickable {
                                        currentAccent = index
                                        viewModel.accentIndex.value = index
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentAccent == index) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SECTION 4: Backup & Restore & Diagnostics
            Text(
                text = "النسخ الاحتياطي وتشخيص الأخطاء:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            val backupFile = BackupRestoreManager.createBackupFile(context)
                            if (backupFile != null) {
                                BackupRestoreManager.shareBackupFile(context, backupFile)
                            } else {
                                Toast.makeText(context, "فشل إنشاء النسخة الاحتياطية", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("نسخ احتياطي")
                }

                OutlinedButton(
                    onClick = {
                        restorePickerLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("استعادة")
                }
            }

            // Diagnostics Button
            Button(
                onClick = {
                    onOpenDiagnostics()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("فتح سجل التشخيص والأخطاء التفصيلي")
            }
        }
    }
}
