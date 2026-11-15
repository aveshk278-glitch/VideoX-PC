package com.videox.pc.engine

import com.videox.pc.shared.QualitySelector
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** What a content-type probe found: a real media file behind the URL. */
data class MediaProbe(
    val contentType: String,
    val fileName: String,
    val contentLength: Long
)

/**
 * Direct media downloads over plain HTTP — the fast path for direct
 * MP4/WebM links, replacing Android's DownloadManager (which has no
 * desktop equivalent). Same rules as the Android fast path:
 *
 * - A URL is only treated as direct media when its path ends in a
 *   supported extension, or a content-type probe proves it is a real
 *   media file. Everything else goes to yt-dlp.
 * - Validation never rejects a link for its host or extension: yt-dlp
 *   handles pages, and extension-less files are detected by probing.
 * - No resume in v1: a cancelled or failed download restarts from the
 *   beginning, and no partial file is left behind. (The yt-dlp path
 *   resumes via .part files; this path does not yet.)
 */
object DirectDownloader {

    val SUPPORTED_EXTENSIONS: List<String> = QualitySelector.DIRECT_MEDIA_EXTENSIONS

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /** True only for http(s) URLs whose path ends in a supported media extension. */
    fun isSupportedVideoUrl(rawUrl: String): Boolean {
        val url = rawUrl.trim()
        if (url.isEmpty()) return false
        if (!isHttpUrl(url)) return false
        return QualitySelector.hasDirectMediaExtension(url)
    }

    /**
     * Human-readable validation error, or null when the URL looks usable.
     * This checks ONLY that the text is a usable http(s) URL.
     */
    fun validateUrl(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return "Please enter a video URL."
        if (!isHttpUrl(url)) {
            return "That does not look like a valid URL. It should start with http:// or https://"
        }
        return null
    }

    private fun isHttpUrl(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) return false
        return try {
            val parsed = URL(url)
            !parsed.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ask the server what a URL actually is (HEAD, with a Range-GET
     * fallback for servers that refuse HEAD). Follows redirects.
     * Returns a probe only when the response is a real media file.
     * Returns null for web pages, errors, or anything not media.
     * Call on a background thread.
     */
    fun probeDirectMedia(rawUrl: String): MediaProbe? {
        val url = rawUrl.trim()
        if (!isHttpUrl(url)) return null
        return try {
            probeOnce(url, useRangeGet = false)
                ?: probeOnce(url, useRangeGet = true)
        } catch (_: Exception) {
            null
        }
    }

    private fun probeOnce(url: String, useRangeGet: Boolean): MediaProbe? {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        conn.apply {
            requestMethod = if (useRangeGet) "GET" else "HEAD"
            if (useRangeGet) setRequestProperty("Range", "bytes=0-0")
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) return null
            parseProbe(url, conn)
        } catch (_: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseProbe(url: String, conn: HttpURLConnection): MediaProbe? {
        val contentType = conn.contentType?.substringBefore(";")?.trim()?.lowercase()
            ?: return null
        if (!contentType.startsWith("video/") && !contentType.startsWith("audio/")) return null
        val length = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
        val name = fileNameFromProbe(url, conn.getHeaderField("Content-Disposition"), contentType)
        return MediaProbe(contentType, name, length)
    }

    private fun fileNameFromProbe(url: String, disposition: String?, contentType: String): String {
        val fromHeader = disposition?.let { d ->
            Regex("filename\\*?=(?:UTF-8''|\"?)([^\";]+)")
                .find(d)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
                ?.takeIf { it.isNotEmpty() }
        }
        val fromPath = try {
            URL(url).path.substringAfterLast("/").substringBefore("?")
        } catch (_: Exception) {
            ""
        }
        val name = fromHeader
            ?: fromPath.takeIf { it.contains(".") }
            ?: "videox_${System.currentTimeMillis()}.${QualitySelector.extensionForContentType(contentType) ?: "mp4"}"
        return QualitySelector.sanitizeFileName(name)
    }

    fun fileNameFromUrl(rawUrl: String, contentType: String? = null): String {
        val url = rawUrl.trim()
        val path = try {
            URL(url).path
        } catch (_: Exception) {
            url.substringBefore("?")
        }
        var name = path.substringAfterLast("/").substringBefore("?").trim()
        if (name.isEmpty() || !name.contains(".")) {
            val ext = QualitySelector.extensionForContentType(contentType) ?: "mp4"
            name = "videox_${System.currentTimeMillis()}.$ext"
        }
        return QualitySelector.sanitizeFileName(name)
    }

    /**
     * Stream [url] into [destFile]. [onProgress] receives downloaded and
     * total bytes (total is 0 when unknown). [isCancelled] is checked
     * after every buffer read; on cancellation the partial file is
     * deleted and [IOException] is thrown. Returns bytes written.
     * Call on a background thread.
     */
    fun download(
        url: String,
        destFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    ): Long {
        destFile.parentFile?.mkdirs()
        val conn = URL(url.trim()).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("Download failed: server returned HTTP $code.")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            var written = 0L
            conn.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (isCancelled()) {
                            throw IOException("Download cancelled.")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            return written
        } catch (e: Exception) {
            destFile.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
