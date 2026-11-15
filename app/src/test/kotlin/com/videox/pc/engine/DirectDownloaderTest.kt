package com.videox.pc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDownloaderTest {

    @Test
    fun validateUrlAcceptsHttpLinks() {
        assertNull(DirectDownloader.validateUrl("https://example.com/video.mp4"))
        assertNull(DirectDownloader.validateUrl("http://example.com/watch?v=1"))
        assertNull(DirectDownloader.validateUrl("  https://example.com/a  "))
    }

    @Test
    fun validateUrlRejectsBadInput() {
        assertEquals("Please enter a video URL.", DirectDownloader.validateUrl(""))
        assertEquals("Please enter a video URL.", DirectDownloader.validateUrl("   "))
        assertTrue(DirectDownloader.validateUrl("not a url")!!.contains("http://"))
        assertTrue(DirectDownloader.validateUrl("ftp://example.com/v.mp4")!!.contains("http://"))
    }

    @Test
    fun isSupportedVideoUrlChecksExtension() {
        assertTrue(DirectDownloader.isSupportedVideoUrl("https://cdn.example.com/clip.mp4"))
        assertTrue(DirectDownloader.isSupportedVideoUrl("https://cdn.example.com/clip.MP4?token=abc"))
        assertTrue(DirectDownloader.isSupportedVideoUrl("https://cdn.example.com/song.mp3"))
        assertFalse(DirectDownloader.isSupportedVideoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(DirectDownloader.isSupportedVideoUrl("https://example.com/page.html"))
        assertFalse(DirectDownloader.isSupportedVideoUrl(""))
    }

    @Test
    fun fileNameFromUrlUsesPathAndSanitises() {
        assertEquals("clip.mp4", DirectDownloader.fileNameFromUrl("https://cdn.example.com/clip.mp4?x=1"))
        val noExt = DirectDownloader.fileNameFromUrl("https://cdn.example.com/download", "video/mp4")
        assertTrue(noExt.startsWith("videox_"))
        assertTrue(noExt.endsWith(".mp4"))
        assertEquals("my video.mp4", DirectDownloader.fileNameFromUrl("https://cdn.example.com/my video.mp4"))
        assertEquals("my_video.mp4", DirectDownloader.fileNameFromUrl("https://cdn.example.com/my:video.mp4"))
    }

    @Test
    fun formatSizeUnits() {
        assertEquals("—", DirectDownloader.formatSize(0))
        assertEquals("512 B", DirectDownloader.formatSize(512))
        assertEquals("2 KB", DirectDownloader.formatSize(2048))
        assertEquals("1.5 MB", DirectDownloader.formatSize(1572864))
        assertEquals("2.00 GB", DirectDownloader.formatSize(2147483648))
    }
}
