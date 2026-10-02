package com.example.data.translation

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.example.data.diagnostics.DiagnosticLogger
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Continuous local STT path. Unlike SpeechRecognizer, AudioRecord remains open and
 * Vosk consumes a PCM stream; silence creates no start/stop recognizer events.
 * A model directory must be installed under filesDir/stt-models/<language>.
 */
class VoskStreamingRecognizer(
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    fun start(modelDirectory: File): Boolean {
        if (running.get()) return true
        if (!modelDirectory.isDirectory) return false
        return try {
            model = Model(modelDirectory.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("حجم مخزن الصوت غير صالح")
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("تعذر تهيئة AudioRecord")
            }
            running.set(true)
            recorder?.startRecording()
            worker = thread(name = "VoskAudioStream") { readLoop() }
            true
        } catch (e: Exception) {
            DiagnosticLogger.e("VoskSTT", "فشل بدء التعرف المتدفق: ${e.message}", e)
            stop()
            main.post { onFailure(e.message ?: "تعذر تشغيل نموذج التعرف") }
            false
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(4096) // 128 ms at 16 kHz mono PCM16
        try {
            while (running.get()) {
                val count = recorder?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: -1
                if (count <= 0) continue
                val r = recognizer ?: break
                if (r.acceptWaveForm(buffer, count)) {
                    val text = extractValue(r.getResult(), "text")
                    if (text.isNotBlank()) main.post { onFinal(text) }
                } else {
                    val partial = extractValue(r.getPartialResult(), "partial")
                    if (partial.isNotBlank()) main.post { onPartial(partial) }
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                DiagnosticLogger.e("VoskSTT", "انقطع دفق التعرف: ${e.message}", e)
                main.post { onFailure(e.message ?: "انقطع دفق الصوت") }
            }
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
        worker = null
    }

    private fun extractValue(json: String, key: String): String {
        val match = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
            .find(json)?.groupValues?.getOrNull(1) ?: return ""
        return match.replace("\\\\", "\\").replace("\\\"", "\"").trim()
    }

    companion object { private const val SAMPLE_RATE = 16_000 }
}
