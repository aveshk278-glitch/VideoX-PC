package com.videox.pc.store

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSettingsTest {

    private fun tempFile(): java.io.File =
        Files.createTempFile("videox-settings-test", ".json").toFile().also { it.delete() }

    @Test
    fun defaultsMatchAndroid() {
        val settings = JsonSettings(tempFile())
        assertTrue(settings.darkTheme)
        assertEquals(2, settings.maxConcurrentDownloads)
        assertEquals("", settings.proxyUrl)
        assertFalse(settings.subtitlesEnabled)
        assertEquals("en", settings.subtitleLangs)
        assertFalse(settings.embedChapters)
        assertTrue(settings.transcribeFallback)
        assertEquals("auto", settings.extractionLanguage)
        assertFalse(settings.translateEnabled)
        assertEquals("en", settings.translateTargetLanguage)
        assertEquals("auto", settings.upscaleProvider)
        assertEquals("performance", settings.upscaleMode)
    }

    @Test
    fun valuesSurviveReload() {
        val file = tempFile()
        val first = JsonSettings(file)
        first.proxyUrl = "http://127.0.0.1:8080"
        first.maxConcurrentDownloads = 3
        first.subtitlesEnabled = true
        first.darkTheme = false
        val second = JsonSettings(file)
        assertEquals("http://127.0.0.1:8080", second.proxyUrl)
        assertEquals(3, second.maxConcurrentDownloads)
        assertTrue(second.subtitlesEnabled)
        assertFalse(second.darkTheme)
        file.delete()
    }

    @Test
    fun maxConcurrentIsClamped() {
        val settings = JsonSettings(tempFile())
        settings.maxConcurrentDownloads = 99
        assertEquals(3, settings.maxConcurrentDownloads)
        settings.maxConcurrentDownloads = 0
        assertEquals(1, settings.maxConcurrentDownloads)
    }

    @Test
    fun subtitleLangsAreSanitised() {
        val settings = JsonSettings(tempFile())
        settings.subtitleLangs = "EN, HI "
        assertEquals("en,hi", settings.subtitleLangs)
    }

    @Test
    fun corruptFileFallsBackToDefaults() {
        val file = tempFile()
        file.writeText("{not json")
        val settings = JsonSettings(file)
        assertTrue(settings.darkTheme)
        assertEquals(2, settings.maxConcurrentDownloads)
        file.delete()
    }

    @Test
    fun backupRoundTrip() {
        val file = tempFile()
        val first = JsonSettings(file)
        first.proxyUrl = "http://proxy:3128"
        first.embedChapters = true
        val backup = first.exportBackup()
        val second = JsonSettings(tempFile())
        assertTrue(second.importBackup(backup))
        assertEquals("http://proxy:3128", second.proxyUrl)
        assertTrue(second.embedChapters)
        file.delete()
    }

    @Test
    fun invalidBackupIsRejected() {
        val settings = JsonSettings(tempFile())
        assertFalse(settings.importBackup("hello"))
        assertFalse(settings.importBackup(""))
        assertFalse(settings.importBackup("[1,2,3]"))
    }

    @Test
    fun unknownBackupKeysAreIgnored() {
        val settings = JsonSettings(tempFile())
        assertTrue(settings.importBackup("""{"wifi_only":"true","proxy_url":"http://x:1"}"""))
        assertEquals("http://x:1", settings.proxyUrl)
    }
}
