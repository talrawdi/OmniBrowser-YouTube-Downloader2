package com.example.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.downloader.MediaSniffer
import com.example.data.downloader.SniffedMedia
import com.example.data.downloader.SniffedQuality
import com.example.data.downloader.StorageDestination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetectionDialog(
    mediaList: List<SniffedMedia>,
    currentDestination: StorageDestination,
    onPlayOnline: (SniffedMedia, SniffedQuality) -> Unit,
    onStartDownload: (media: SniffedMedia, selectedQuality: SniffedQuality, withSubtitles: Boolean, destination: StorageDestination) -> Unit,
    onDownloadSubtitle: (media: SniffedMedia, subtitleUrl: String) -> Unit = { _, _ -> },
    onOpenDiagnostics: () -> Unit = {},
    onDismiss: () -> Unit
) {
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
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "الوسائط المكتشفة (${mediaList.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "سجل التشخيص",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                }
            }

            Text(
                text = "اختر الدقة المطلوبة ومكان الحفظ للبدء بالتنزيل أو المعاينة المباشرة:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            if (mediaList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لم يتم العثور على مقاطع فيديو في هذه الصفحة بعد. قم بتشغيل الفيديو على الصفحة ليتم التقاطه فوراً.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(mediaList) { media ->
                        MediaItemCard(
                            media = media,
                            initialDestination = currentDestination,
                            onPlay = { quality -> onPlayOnline(media, quality) },
                            onDownload = { quality, withSub, dest ->
                                onStartDownload(media, quality, withSub, dest)
                                onDismiss()
                            },
                            onDownloadSubtitle = { subtitleUrl ->
                                onDownloadSubtitle(media, subtitleUrl)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MediaItemCard(
    media: SniffedMedia,
    initialDestination: StorageDestination,
    onPlay: (SniffedQuality) -> Unit,
    onDownload: (quality: SniffedQuality, withSubtitles: Boolean, destination: StorageDestination) -> Unit,
    onDownloadSubtitle: (subtitleUrl: String) -> Unit
) {
    val availableQualities = remember(media.qualities) {
        media.qualities
            .filter { it.url.isNotBlank() }
            .distinctBy { "${it.label.trim().lowercase()}|${it.url}" }
            .sortedWith(compareBy<SniffedQuality> { it.isAudioOnly }.thenByDescending {
                Regex("(\\d{3,4})p").find(it.label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            })
    }
    var selectedQuality by remember(availableQualities) {
        mutableStateOf(availableQualities.firstOrNull() ?: SniffedQuality("جودة متاحة", media.originalUrl))
    }
    LaunchedEffect(availableQualities) {
        if (availableQualities.none { it.url == selectedQuality.url }) {
            selectedQuality = availableQualities.firstOrNull() ?: selectedQuality
        }
    }
    var downloadWithSubtitles by remember { mutableStateOf(false) }
    var selectedDestination by remember { mutableStateOf(initialDestination) }
    var showQualityDropdown by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Media Title & Type
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!media.thumbnailUrl.isNullOrBlank()) {
                        coil.compose.AsyncImage(
                            model = media.thumbnailUrl,
                            contentDescription = media.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = if (selectedQuality.isAudioOnly) Icons.Default.Audiotrack else Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = media.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${media.mimeType} • ${MediaSniffer.formatFileSize(selectedQuality.sizeBytes, selectedQuality.isEstimatedSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Quality Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "دقة الفيديو:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Box {
                    AssistChip(
                        onClick = { showQualityDropdown = true },
                        label = { Text("${selectedQuality.label} (${MediaSniffer.formatFileSize(selectedQuality.sizeBytes, selectedQuality.isEstimatedSize)})") },
                        leadingIcon = { Icon(Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
                    )

                    DropdownMenu(
                        expanded = showQualityDropdown,
                        onDismissRequest = { showQualityDropdown = false }
                    ) {
                        availableQualities.forEach { quality ->
                            DropdownMenuItem(
                                text = {
                                    Text("${quality.label} (${MediaSniffer.formatFileSize(quality.sizeBytes, quality.isEstimatedSize)})")
                                },
                                onClick = {
                                    selectedQuality = quality
                                    showQualityDropdown = false
                                }
                            )
                        }
                    }
                }
            }

            // Subtitle download is intentionally disabled: video-only downloads are more reliable.
            // Destination Selector Choice
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedDestination == StorageDestination.PUBLIC_DOWNLOADS,
                    onClick = { selectedDestination = StorageDestination.PUBLIC_DOWNLOADS },
                    label = { Text("تنزيلات الجهاز") },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = selectedDestination == StorageDestination.INTERNAL_VAULT,
                    onClick = { selectedDestination = StorageDestination.INTERNAL_VAULT },
                    label = { Text("المجلد الخاص بالتطبيق") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onPlay(selectedQuality) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("مشاهدة")
                }

                Button(
                    onClick = { onDownload(selectedQuality, downloadWithSubtitles, selectedDestination) },
                    modifier = Modifier.weight(1.4f)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تنزيل سريع")
                }
            }
        }
    }
}
