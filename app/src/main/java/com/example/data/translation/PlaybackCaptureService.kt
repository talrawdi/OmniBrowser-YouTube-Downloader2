package com.example.data.translation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.data.diagnostics.DiagnosticLogger

/** Owns the MediaProjection while playback audio capture is active. */
class PlaybackCaptureService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LiveSubtitleEngine.stopMicrophoneRecognition()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            DiagnosticLogger.w(TAG, "بيانات MediaProjection غير صالحة")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            if (projection == null) {
                DiagnosticLogger.w(TAG, "تعذر إنشاء MediaProjection")
                stopSelf()
                return START_NOT_STICKY
            }
            LiveSubtitleEngine.startPlaybackCapture(this, projection, { 0L }) { started ->
                if (!started) {
                    DiagnosticLogger.w(TAG, "فشل تشغيل التقاط الصوت بعد بدء الخدمة")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.e(TAG, "فشل تشغيل خدمة التقاط الصوت: ${e.message}", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LiveSubtitleEngine.stopMicrophoneRecognition()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "الترجمة الصوتية الداخلية",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(com.example.R.drawable.ic_launcher_foreground)
        .setContentTitle("الترجمة الصوتية مفعلة")
        .setContentText("يتم التقاط صوت الفيديو الداخلي بأمان")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        const val ACTION_STOP = "com.example.action.STOP_PLAYBACK_CAPTURE"
        const val EXTRA_RESULT_CODE = "playback_result_code"
        const val EXTRA_RESULT_DATA = "playback_result_data"
        private const val TAG = "PlaybackCaptureService"
        private const val CHANNEL_ID = "playback_capture_channel"
        private const val NOTIFICATION_ID = 7101
    }
}
