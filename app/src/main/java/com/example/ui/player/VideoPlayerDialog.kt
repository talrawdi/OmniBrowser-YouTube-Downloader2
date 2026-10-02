package com.example.ui.player

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.diagnostics.DiagnosticLogger
import com.example.data.translation.LiveSubtitleEngine
import com.example.data.translation.PlaybackCaptureService
import com.example.ui.settings.DiagnosticsDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerDialog(
    videoTitle: String,
    videoUriString: String,
    requestHeaders: Map<String, String> = emptyMap(),
    mimeType: String = "video/mp4",
    subtitleFilePath: String? = null,
    isPartialDownload: Boolean = false,
    downloadProgressPercent: Int = 0,
    onRequestInternalAudioCapture: () -> Unit = {},
    onOpenTranslationSettings: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val subtitleFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            coroutineScope.launch {
                val ok = LiveSubtitleEngine.loadAndTranslateUri(context, it.toString(), LiveSubtitleEngine.targetLang)
                Toast.makeText(context, if (ok) "تم تحميل ملف الترجمة" else "تعذر قراءة ملف الترجمة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var isFullscreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showTranslationSheet by remember { mutableStateOf(false) }
    var playerErrorMessage by remember { mutableStateOf<String?>(null) }
    var showDiagnosticsFromPlayer by remember { mutableStateOf(false) }
    var doubleTapSeekText by remember { mutableStateOf<String?>(null) }
    var doubleTapIsForward by remember { mutableStateOf(true) }

    // Live Subtitles State
    val liveSubtitleText by LiveSubtitleEngine.activeSubtitleText.collectAsState()
    val isLiveTranslating by LiveSubtitleEngine.isLiveTranslating.collectAsState()

    // Initialize ExoPlayer with HTTP datasource supporting redirects and generic streams
    val exoPlayer = remember(videoUriString, mimeType, requestHeaders) {
        val isNetworkUri = videoUriString.startsWith("http://") || videoUriString.startsWith("https://")
        val mediaUri = if (isNetworkUri || videoUriString.startsWith("content://")) {
            Uri.parse(videoUriString)
        } else {
            Uri.fromFile(File(videoUriString))
        }
        val dataSourceFactory = if (isNetworkUri) {
            DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(30000)
                .setDefaultRequestProperties(requestHeaders.filterKeys {
                    it.equals("Cookie", true) ||
                        it.equals("Referer", true) ||
                        it.equals("Origin", true) ||
                        it.equals("Authorization", true)
                })
        } else {
            // DefaultDataSource selects FileDataSource/ContentDataSource for local files and MediaStore URIs.
            DefaultDataSource.Factory(context)
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                try {
                    if (!isNetworkUri && !videoUriString.startsWith("content://")) {
                        val file = File(videoUriString)
                        if (!file.exists() || file.length() == 0L) {
                            playerErrorMessage = "الملف المحلي فارغ أو غير موجود (${videoUriString})"
                            DiagnosticLogger.e("VideoPlayer", "الملف المحلي المطلوب تشغيله غير موجود أو فارغ: $videoUriString")
                            return@apply
                        }
                    }
                    val mediaItem = MediaItem.Builder()
                        .setUri(mediaUri)
                        .setMimeType(
                            mimeType.takeIf { it.isNotBlank() && !it.equals("application/octet-stream", true) }
                        )
                        .build()
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true
                } catch (e: Exception) {
                    playerErrorMessage = "خطأ في تهيئة المشغل: ${e.message}"
                    DiagnosticLogger.e("VideoPlayer", "فشل تجهيز مشغل الوسائط: ${e.message}", e)
                }
            }
    }

    // Auto-load subtitle if provided
    LaunchedEffect(subtitleFilePath) {
        if (!subtitleFilePath.isNullOrBlank()) {
            if (subtitleFilePath.startsWith("content://")) {
                LiveSubtitleEngine.loadAndTranslateUri(context, subtitleFilePath, "ar")
            } else {
                val subFile = File(subtitleFilePath)
                if (subFile.exists()) LiveSubtitleEngine.loadAndTranslateFile(subFile, "ar")
            }
        }
    }

    // Periodic position updater for subtitles & slider (80ms for millisecond accuracy)
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPositionMs = exoPlayer.currentPosition
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            LiveSubtitleEngine.updatePlaybackPosition(currentPositionMs)
            delay(80)
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = error.message ?: error.errorCodeName
                playerErrorMessage = "تعذر تشغيل هذا المقطع ($detail). يمكنك تنزيل المقطع أو اختيار جودة أخرى."
                DiagnosticLogger.e("VideoPlayer", "خطأ أثناء تشغيل الوسائط: $detail [كود: ${error.errorCodeName}]", error)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            if (LiveSubtitleEngine.translationSource.value == com.example.data.translation.TranslationSource.INTERNAL_AUDIO) {
                context.startService(Intent(context, PlaybackCaptureService::class.java).apply { action = PlaybackCaptureService.ACTION_STOP })
            }
            LiveSubtitleEngine.clear()
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Dialog(
        onDismissRequest = {
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Android Media3 PlayerView
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false // Custom Compose UI controller
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setOnClickListener {
                            showControls = !showControls
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Gesture Tap & Double-Tap Listener Box
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = { offset ->
                                val isRight = offset.x > (size.width / 2)
                                if (isRight) {
                                    val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(exoPlayer.duration.coerceAtLeast(0L))
                                    exoPlayer.seekTo(target)
                                    doubleTapIsForward = true
                                    doubleTapSeekText = "+10 ثوانٍ"
                                } else {
                                    val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                    exoPlayer.seekTo(target)
                                    doubleTapIsForward = false
                                    doubleTapSeekText = "-10 ثوانٍ"
                                }
                                coroutineScope.launch {
                                    delay(750)
                                    doubleTapSeekText = null
                                }
                            }
                        )
                    }
            )

            // Animated Double-Tap Indicator
            AnimatedVisibility(
                visible = doubleTapSeekText != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(if (doubleTapIsForward) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 48.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = if (doubleTapIsForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = doubleTapSeekText.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Partial Download Banner indicator
            if (isPartialDownload) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 70.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "مشاهدة مباشرة أثناء التنزيل ($downloadProgressPercent% تم تحميله)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Player Error Overlay
            if (playerErrorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .fillMaxWidth(0.9f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "تعذر تشغيل الفيديو",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = playerErrorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showDiagnosticsFromPlayer = true }
                            ) {
                                Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("عرض سجل التشخيص")
                            }
                            Button(onClick = onDismiss) {
                                Text("إغلاق")
                            }
                        }
                    }
                }
            }

            if (showDiagnosticsFromPlayer) {
                DiagnosticsDialog(onDismiss = { showDiagnosticsFromPlayer = false })
            }

            // Live Synchronized Subtitle Overlay with Spring Transitions & High Contrast
            AnimatedVisibility(
                visible = liveSubtitleText.isNotBlank(),
                enter = fadeIn(animationSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) +
                        slideInVertically(animationSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) { it / 3 },
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(200)) +
                       slideOutVertically(animationSpec = androidx.compose.animation.core.tween(200)) { it / 4 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showControls) 136.dp else 44.dp)
                    .padding(horizontal = 20.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0B132B).copy(alpha = 0.90f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    shadowElevation = 10.dp
                ) {
                    Text(
                        text = liveSubtitleText,
                        color = Color(0xFFFDE047), // Radiant high-visibility golden yellow
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                    )
                }
            }

            // Overlay Controls
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    // Top App Bar Controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = Color.White
                            )
                        }

                        Text(
                            text = videoTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        )

                        // Live Translation & Subtitles Button
                        IconButton(
                            onClick = { showTranslationSheet = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = "الترجمة الفورية",
                                tint = if (isLiveTranslating) Color.Yellow else Color.White
                            )
                        }

                        // Picture-in-Picture Button
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            IconButton(
                                onClick = {
                                    val activity = context as? Activity
                                    try {
                                        activity?.enterPictureInPictureMode(
                                            android.app.PictureInPictureParams.Builder().build()
                                        )
                                    } catch (e: Exception) {
                                        DiagnosticLogger.w("VideoPlayer", "فشل تشغيل وضع صورة داخل صورة: ${e.message}")
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "صورة داخل صورة",
                                    tint = Color.White
                                )
                            }
                        }

                        // Playback Speed Button
                        Box {
                            IconButton(onClick = { showSpeedMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "سرعة التشغيل",
                                    tint = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false }
                            ) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = { Text("${speed}x") },
                                        onClick = {
                                            currentSpeed = speed
                                            exoPlayer.playbackParameters = PlaybackParameters(speed)
                                            showSpeedMenu = false
                                        },
                                        leadingIcon = {
                                            if (currentSpeed == speed) {
                                                Icon(Icons.Default.Check, contentDescription = null)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Center Play / Pause / Rewind / Forward Controls
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val newPos = (exoPlayer.currentPosition - 10000).coerceAtLeast(0L)
                                exoPlayer.seekTo(newPos)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "رجوع 10 ثوان",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(64.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val newPos = (exoPlayer.currentPosition + 10000).coerceAtMost(durationMs)
                                exoPlayer.seekTo(newPos)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "تقديم 10 ثوان",
                                tint = Color.White
                            )
                        }
                    }

                    // Bottom Bar: Progress Slider, Timestamps, Fullscreen
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Slider(
                            value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f,
                            onValueChange = { ratio ->
                                val target = (ratio * durationMs).toLong()
                                currentPositionMs = target
                                exoPlayer.seekTo(target)
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${formatPlayerTime(currentPositionMs)} / ${formatPlayerTime(durationMs)}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )

                            IconButton(
                                onClick = {
                                    val activity = context as? Activity
                                    if (isFullscreen) {
                                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        isFullscreen = false
                                    } else {
                                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                        isFullscreen = true
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = "شاشة كاملة",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // Live Translation & Subtitle Settings Bottom Sheet
            if (showTranslationSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showTranslationSheet = false },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "خدمة الترجمة المباشرة والفورية المجانية",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "ترجمة ملفات VTT/SRT تعمل مع توقيت الفيديو. وللترجمة الحية من الكلام استخدم ميكروفون الجهاز بعد منح الإذن.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )

                        Button(
                            onClick = {
                                val activity = context as? Activity
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    activity?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), 421) }
                                    Toast.makeText(context, "اسمح بالميكروفون ثم اضغط الزر مرة أخرى", Toast.LENGTH_LONG).show()
                                } else {
                                    LiveSubtitleEngine.startMicrophoneRecognition(context) { exoPlayer.currentPosition }
                                    Toast.makeText(context, "بدأ التعرف الصوتي والترجمة الحية من الميكروفون", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("بدء الترجمة الحية من الميكروفون")
                        }

                        OutlinedButton(
                            onClick = {
                                onRequestInternalAudioCapture()
                                showTranslationSheet = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isLiveTranslating) "التقاط الصوت الداخلي مفعّل" else "ترجمة صوت الفيديو الداخلي")
                        }

                        if (isLiveTranslating) {
                            OutlinedButton(
                                onClick = {
                                    if (LiveSubtitleEngine.translationSource.value == com.example.data.translation.TranslationSource.INTERNAL_AUDIO) {
                                        context.startService(Intent(context, PlaybackCaptureService::class.java).apply { action = PlaybackCaptureService.ACTION_STOP })
                                    }
                                    LiveSubtitleEngine.stopMicrophoneRecognition()
                                    LiveSubtitleEngine.clear()
                                    Toast.makeText(context, "تم إيقاف الترجمة الحية", Toast.LENGTH_SHORT).show()
                                    showTranslationSheet = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("إيقاف الترجمة الحية")
                            }
                        }

                        OutlinedButton(
                            onClick = { subtitleFileLauncher.launch(arrayOf("text/*", "application/x-subrip", "application/ttml+xml")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Subtitles, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("اختيار ملف ترجمة SRT / VTT")
                        }

                        TextButton(onClick = { onOpenTranslationSettings(); showTranslationSheet = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("فتح إعدادات الخدمة واللغات والنماذج")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Export Subtitle (.SRT)
                        OutlinedButton(
                            onClick = {
                                val srtFile = LiveSubtitleEngine.exportToSrtFile(context, videoTitle)
                                if (srtFile != null) {
                                    Toast.makeText(context, "تم تصدير ملف الترجمة: ${srtFile.name}", Toast.LENGTH_LONG).show()
                                    // Share SRT
                                    try {
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", srtFile)
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "مشاركة ملف الترجمة"))
                                    } catch (_: Exception) {}
                                } else {
                                    Toast.makeText(context, "لا توجد ترجمة متاحة للتصدير حالياً", Toast.LENGTH_SHORT).show()
                                }
                                showTranslationSheet = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("حفظ وتصدير ملف الترجمة (.SRT)")
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

private fun formatPlayerTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
}
