package com.videox.pc.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpCliTest {

    @Test
    fun progressLineWithMinutesEta() {
        val parsed = parseProgressLine("[download]  45.2% of 123.45MiB at 1.23MiB/s ETA 00:42")
        assertEquals(45.2f, parsed!!.percent, 0.001f)
        assertEquals(42L, parsed.etaSeconds)
    }

    @Test
    fun progressLineWithHoursEta() {
        val parsed = parseProgressLine("[download]   3.0% of 1.20GiB at 500.00KiB/s ETA 01:02:03")
        assertEquals(3.0f, parsed!!.percent, 0.001f)
        assertEquals(3723L, parsed.etaSeconds)
    }

    @Test
    fun progressLineWithoutEta() {
        val parsed = parseProgressLine("[download] 100% of 12.34MiB in 00:05")
        assertEquals(100f, parsed!!.percent, 0.001f)
        assertEquals(-1L, parsed.etaSeconds)
    }

    @Test
    fun nonProgressLinesReturnNull() {
        assertNull(parseProgressLine("[info] Downloading video"))
        assertNull(parseProgressLine("[ffmpeg] Merging formats"))
        assertNull(parseProgressLine("plain text"))
        assertNull(parseProgressLine("[download] Destination: video.mp4"))
    }

    @Test
    fun versionComparison() {
        assertTrue(isNewerVersion("2026.10.26", "2025.11.12"))
        assertFalse(isNewerVersion("2026.10.26", "2026.10.26"))
        assertFalse(isNewerVersion("2025.11.12", "2026.10.26"))
        assertTrue(isNewerVersion("2026.10.26", null))
        assertTrue(isNewerVersion("2026.11.1", "2026.10.26"))
        assertFalse(isNewerVersion("not-a-version", "2026.10.26"))
        assertFalse(isNewerVersion("2026.10", "2026.10.26"))
    }

    @Test
    fun findOutputFilePicksNewestNonPartial() {
        val dir = Files.createTempDirectory("videox-find-output").toFile()
        try {
            val old = File(dir, "video.mp4").apply { writeText("old") }
            old.setLastModified(1000L)
            File(dir, "video.mp4.part").apply { writeText("partial") }.setLastModified(3000L)
            val newest = File(dir, "video.mkv").apply { writeText("new") }
            newest.setLastModified(2000L)
            File(dir, "other.mp4").apply { writeText("other") }
            assertEquals(newest, YtDlpCli.findOutputFile(dir, "video"))
            assertNull(YtDlpCli.findOutputFile(dir, "missing"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
