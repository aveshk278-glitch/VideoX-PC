package com.videox.pc.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpFormatsTest {

    private val sampleJson = """
        {
          "id": "dQw4w9WgXcQ",
          "title": "Rick Astley - Never Gonna Give You Up (Official Video)",
          "uploader": "Rick Astley",
          "duration": 212.0,
          "formats": [
            {"format_id": "18", "ext": "mp4", "width": 640, "height": 360, "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "filesize": 12345678, "filesize_approx": null, "format_note": "360p"},
            {"format_id": "137", "ext": "mp4", "width": 1920, "height": 1080, "vcodec": "avc1.640028", "acodec": "none", "filesize": null, "filesize_approx": 98765432, "format_note": "1080p", "fragments": [{"url": "https://example.com/1", "duration": 5.0}]},
            {"format_id": "140", "ext": "m4a", "width": null, "height": null, "vcodec": "none", "acodec": "mp4a.40.2", "filesize": 3456789, "filesize_approx": null, "format_note": "128k", "http_headers": {"User-Agent": "x"}},
            {"format_id": "22", "ext": "mp4", "width": 1280, "height": 720, "vcodec": "avc1.64001F", "acodec": "mp4a.40.2", "filesize": 0, "filesize_approx": 55555555, "format_note": "720p"}
          ],
          "thumbnails": [{"url": "https://example.com/t.jpg"}],
          "subtitles": {"en": [{"ext": "vtt"}]}
        }
    """.trimIndent()

    @Test
    fun parsesTitleUploaderAndDuration() {
        val info = YtDlpFormats.parse(sampleJson)
        assertEquals("Rick Astley - Never Gonna Give You Up (Official Video)", info.title)
        assertEquals("Rick Astley", info.uploader)
        assertEquals(212L, info.durationSeconds)
    }

    @Test
    fun parsesAllFormatsSortedByHeightDesc() {
        val info = YtDlpFormats.parse(sampleJson)
        assertEquals(listOf("137", "22", "18", "140"), info.formats.map { it.formatId })
    }

    @Test
    fun marksVideoOnlyAndAudioOnly() {
        val info = YtDlpFormats.parse(sampleJson)
        val videoOnly = info.formats.first { it.formatId == "137" }
        assertTrue(videoOnly.isVideoOnly)
        assertTrue(!videoOnly.isAudioOnly)
        val audioOnly = info.formats.first { it.formatId == "140" }
        assertTrue(audioOnly.isAudioOnly)
        assertTrue(!audioOnly.isVideoOnly)
        assertEquals(0, audioOnly.height)
    }

    @Test
    fun fallsBackToApproximateSize() {
        val info = YtDlpFormats.parse(sampleJson)
        assertEquals(98765432L, info.formats.first { it.formatId == "137" }.fileSize)
        assertEquals(55555555L, info.formats.first { it.formatId == "22" }.fileSize)
        assertEquals(12345678L, info.formats.first { it.formatId == "18" }.fileSize)
    }

    @Test
    fun buildsLabelsLikeAndroid() {
        val info = YtDlpFormats.parse(sampleJson)
        assertEquals(
            "Video only • 1920x1080 • mp4 • 1080p",
            info.formats.first { it.formatId == "137" }.label
        )
        assertEquals(
            "Audio • m4a • 128k",
            info.formats.first { it.formatId == "140" }.label
        )
    }

    @Test
    fun decodesEscapedTitle() {
        val json = """{"title": "A \"quoted\" caf\u00e9", "uploader": "", "duration": 5, "formats": []}"""
        val info = YtDlpFormats.parse(json)
        assertEquals("A \"quoted\" café", info.title)
    }

    @Test
    fun missingFormatsYieldsEmptyList() {
        val info = YtDlpFormats.parse("""{"title": "T", "uploader": "U", "duration": 10}""")
        assertEquals("T", info.title)
        assertTrue(info.formats.isEmpty())
    }

    @Test
    fun nullAndGarbageInputYieldsEmptyInfo() {
        assertTrue(YtDlpFormats.parse(null).formats.isEmpty())
        assertTrue(YtDlpFormats.parse("").formats.isEmpty())
        assertTrue(YtDlpFormats.parse("not json").formats.isEmpty())
        assertTrue(YtDlpFormats.parse("[]").formats.isEmpty())
    }

    @Test
    fun ignoresNestedFormatsKey() {
        val json = """{"title": "T", "formats": [{"format_id": "1", "ext": "mp4", "width": 1, "height": 1, "vcodec": "x", "acodec": "y", "nested": {"formats": [{"format_id": "bogus"}]}}]}"""
        val info = YtDlpFormats.parse(json)
        assertEquals(listOf("1"), info.formats.map { it.formatId })
    }
}
