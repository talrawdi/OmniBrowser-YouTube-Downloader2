package com.example.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class ReaderTheme(val bgColor: Color, val textColor: Color, val label: String) {
    LIGHT(Color(0xFFFAF9F6), Color(0xFF1E293B), "فاتح"),
    SEPIA(Color(0xFFFBF0D9), Color(0xFF433422), "سيبيا"),
    DARK(Color(0xFF121824), Color(0xFFE2E8F0), "داكن"),
    AMOLED(Color(0xFF000000), Color(0xFFEDEDED), "أسود نقي")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderModeDialog(
    title: String,
    paragraphs: List<String>,
    onDismiss: () -> Unit
) {
    var fontSize by remember { mutableFloatStateOf(18f) }
    var currentTheme by remember { mutableStateOf(ReaderTheme.SEPIA) }
    val wordCount = remember(paragraphs) { paragraphs.sumOf { it.split(Regex("\\s+")).size } }
    val readingMinutes = remember(wordCount) { maxOf(1, wordCount / 180) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                Surface(
                    color = currentTheme.bgColor,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "إغلاق وضع القراءة", tint = currentTheme.textColor)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "وضع القراءة السلس",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = currentTheme.textColor
                            )
                        }

                        // Theme & Font Controls
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { fontSize = (fontSize - 2f).coerceAtLeast(14f) }) {
                                Text("A-", color = currentTheme.textColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            IconButton(onClick = { fontSize = (fontSize + 2f).coerceAtMost(26f) }) {
                                Text("A+", color = currentTheme.textColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }

                            // Theme Selector Dots
                            ReaderTheme.values().forEach { t ->
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(t.bgColor)
                                        .clickable { currentTheme = t }
                                        .padding(2.dp)
                                ) {
                                    if (currentTheme == t) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            containerColor = currentTheme.bgColor
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(vertical = 20.dp)
            ) {
                item {
                    Text(
                        text = title,
                        fontSize = (fontSize + 6f).sp,
                        fontWeight = FontWeight.Bold,
                        color = currentTheme.textColor,
                        lineHeight = (fontSize + 12f).sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.padding(bottom = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = currentTheme.textColor.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = currentTheme.textColor.copy(alpha = 0.8f),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "قراءة $readingMinutes دقائق • $wordCount كلمة",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = currentTheme.textColor.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = currentTheme.textColor.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(16.dp))
                }

                items(paragraphs) { p ->
                    Text(
                        text = p,
                        fontSize = fontSize.sp,
                        color = currentTheme.textColor,
                        lineHeight = (fontSize * 1.6f).sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = "نهاية المقال",
                        style = MaterialTheme.typography.bodySmall,
                        color = currentTheme.textColor.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
