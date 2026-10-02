package com.example.data.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.data.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Native Android MediaMuxer utility to merge separate DASH video (e.g. 1080p Full HD)
 * and audio (AAC / M4A) tracks into a single playable MP4 file completely on-device.
 */
object NativeMediaMuxer {

    suspend fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || !audioFile.exists()) {
            DiagnosticLogger.e("MediaMuxer", "ملفات المصدر للدمج غير موجودة")
            return@withContext false
        }

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            val videoTrackIndex = selectTrack(videoExtractor, "video/")
            val audioTrackIndex = selectTrack(audioExtractor, "audio/")

            if (videoTrackIndex < 0 || audioTrackIndex < 0) {
                DiagnosticLogger.w("MediaMuxer", "لم يتم العثور على مسار فيديو أو صوت صالح للدمج")
                return@withContext false
            }

            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)

            audioExtractor.selectTrack(audioTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = maxOf(
                videoFormat.getIntegerSafe(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024),
                audioFormat.getIntegerSafe(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            )
            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // Write Video track
            val videoDuration = videoFormat.getLongSafe(MediaFormat.KEY_DURATION, 1L)
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()

                if (videoDuration > 0) {
                    val progress = (bufferInfo.presentationTimeUs.toFloat() / videoDuration.toFloat()) * 0.5f
                    onProgress(progress.coerceIn(0f, 0.5f))
                }
            }

            // Write Audio track
            val audioDuration = audioFormat.getLongSafe(MediaFormat.KEY_DURATION, 1L)
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                audioExtractor.advance()

                if (audioDuration > 0) {
                    val progress = 0.5f + (bufferInfo.presentationTimeUs.toFloat() / audioDuration.toFloat()) * 0.5f
                    onProgress(progress.coerceIn(0.5f, 1.0f))
                }
            }

            onProgress(1.0f)
            DiagnosticLogger.s("MediaMuxer", "تم دمج مسار الصوت والفيديو بنجاح بدقة كاملة: ${outputFile.name}")
            true
        } catch (e: Exception) {
            DiagnosticLogger.e("MediaMuxer", "فشل دمج الصوت والفيديو: ${e.message}", e)
            if (outputFile.exists()) outputFile.delete()
            false
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun MediaFormat.getIntegerSafe(key: String, default: Int): Int {
        return try { if (containsKey(key)) getInteger(key) else default } catch (_: Exception) { default }
    }

    private fun MediaFormat.getLongSafe(key: String, default: Long): Long {
        return try { if (containsKey(key)) getLong(key) else default } catch (_: Exception) { default }
    }
}
