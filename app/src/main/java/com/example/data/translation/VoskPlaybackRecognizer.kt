package com.example.data.translation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import com.example.data.diagnostics.DiagnosticLogger
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Captures permitted playback audio from Android 10+ and feeds PCM directly to Vosk. */
class VoskPlaybackRecognizer(
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var projection: MediaProjection? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    fun start(context: Context, projection: MediaProjection, modelDirectory: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !modelDirectory.isDirectory) return false
        return try {
            this.projection = projection
            model = Model(modelDirectory.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            recorder = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes((minBuffer.coerceAtLeast(4096)) * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) error("تعذر تهيئة التقاط الصوت الداخلي")
            running.set(true)
            recorder?.startRecording()
            worker = thread(name = "VoskPlaybackStream") { readLoop() }
            true
        } catch (e: Exception) {
            DiagnosticLogger.e("VoskPlayback", "فشل التقاط الصوت الداخلي: ${e.message}", e)
            stop()
            onFailure(e.message ?: "لا يسمح مصدر الصوت بالالتقاط الداخلي")
            false
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(4096) // 128 ms at 16 kHz mono PCM16
        try {
            while (running.get()) {
                val count = recorder?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: -1
                if (count <= 0) continue
                val current = recognizer ?: break
                if (current.acceptWaveForm(buffer, count)) {
                    extract(current.getResult(), "text")?.takeIf { it.isNotBlank() }?.let(onFinal)
                } else {
                    extract(current.getPartialResult(), "partial")?.takeIf { it.isNotBlank() }?.let(onPartial)
                }
            }
        } catch (e: Exception) {
            if (running.get()) onFailure(e.message ?: "انقطع التقاط الصوت الداخلي")
        }
    }

    fun stop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        worker = null
    }

    private fun extract(json: String, key: String): String? {
        val raw = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
            .find(json)?.groupValues?.getOrNull(1) ?: return null
        return raw.replace("\\\\", "\\").replace("\\\"", "\"").trim()
    }

    companion object { private const val SAMPLE_RATE = 16_000 }
}
