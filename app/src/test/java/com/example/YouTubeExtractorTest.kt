package com.example

import com.example.data.downloader.YouTubeExtractor
import org.junit.Assert.*
import org.junit.Test

class YouTubeExtractorTest {

    @Test
    fun extractVideoId_fromVariousFormats() {
        // Standard watch URL
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        // Mobile watch URL
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"))
        // Short URL (youtu.be)
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
        // Shorts URL
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        // Embed URL
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        // Raw Video ID
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("dQw4w9WgXcQ"))
        // Non-YouTube URL
        assertNull(YouTubeExtractor.extractVideoId("https://example.com/video.mp4"))
    }

    @Test
    fun isYouTubeUrl_detection() {
        assertTrue(YouTubeExtractor.isYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(YouTubeExtractor.isYouTubeUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(YouTubeExtractor.isYouTubeUrl("https://m.youtube.com/shorts/12345678901"))
        assertFalse(YouTubeExtractor.isYouTubeUrl("https://example.com/index.html"))
    }

    @Test
    fun userAgentMapping_correctForClients() {
        val vrUa = YouTubeExtractor.getUserAgentForClient("ANDROID_VR")
        assertTrue(vrUa.contains("Quest 3"))

        val iosUa = YouTubeExtractor.getUserAgentForClient("IOS")
        assertTrue(iosUa.contains("iPhone"))

        val androidUa = YouTubeExtractor.getUserAgentForClient("ANDROID")
        assertTrue(androidUa.contains("Android"))
    }
}
