package com.videox.pc.engine

import com.videox.pc.shared.QualitySelector
import com.videox.pc.shared.Sha256
import com.videox.pc.shared.YtDlpFormats
import com.videox.pc.shared.YtVideoInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Failure from the yt-dlp subprocess. [rawOutput] is stderr/stdout tail for error mapping. */
class YtDlpException(message: String, val rawOutput: String = "") : IOException(message)

/** Thrown when [YtDlpCli.cancel] stopped a running download. Not an error. */
class YtDlpCancelledException : IOException("Download cancelled.")

data class ProgressUpdate(val percent: Float, val etaSeconds: Long)

data class DownloadRequest(
    val url: String,
    val formatSelector: String?,
    val audioOnly: Boolean,
    val audioFormat: String,
    val outputBaseName: String,
    val extractorArgs: String?,
    val proxyUrl: String?,
    val extraOptions: List<Pair<String, String?>> = emptyList()
)

data class DownloadOutcome(
    val exitCode: Int,
    val alreadyDownloaded: Boolean,
    val outputFile: File?
)

/**
 * Parse one yt-dlp progress line, e.g.
 * "[download]  45.2% of 123.45MiB at 1.23MiB/s ETA 00:42".
 * Returns null for non-progress lines. ETA is -1 when unknown.
 */
internal fun parseProgressLine(line: String): ProgressUpdate? {
    if (!line.contains("[download]")) return null
    val percentMatch = Regex("""(\d+(?:\.\d+)?)%""").find(line) ?: return null
    val percent = percentMatch.groupValues[1].toFloatOrNull() ?: return null
    val etaMatch = Regex("""ETA\s+(\d+):(\d+)(?::(\d+))?""").find(line)
    val eta = if (etaMatch != null) {
        val groups = etaMatch.groupValues
        if (groups[3].isNotEmpty()) {
            groups[1].toLong() * 3600 + groups[2].toLong() * 60 + groups[3].toLong()
        } else {
            groups[1].toLong() * 60 + groups[2].toLong()
        }
    } else {
        -1L
    }
    return ProgressUpdate(percent, eta)
}

/**
 * True when [candidate] is a newer yt-dlp version than [current].
 * Versions are date-based (YYYY.MM.DD); compared numerically per part.
 * An unparsable candidate is never treated as newer.
 */
internal fun isNewerVersion(candidate: String, current: String?): Boolean {
    if (current == null) return true
    val candParts = candidate.trim().split(".")
    val curParts = current.trim().split(".")
    if (candParts.any { it.toIntOrNull() == null }) return false
    val len = maxOf(candParts.size, curParts.size)
    for (i in 0 until len) {
        val c = candParts.getOrNull(i)?.toIntOrNull() ?: 0
        val o = curParts.getOrNull(i)?.toIntOrNull() ?: 0
        if (c != o) return c > o
    }
    return false
}

/**
 * yt-dlp on Windows: the standalone yt-dlp.exe driven as a subprocess.
 * There is no JVM yt-dlp library equivalent to youtubedl-android, so
 * this wrapper mirrors the Android engine's behaviour with CLI flags
 * (verified against yt-dlp 2026.10.26):
 *
 * - getInfo runs --dump-single-json --skip-download; formats are parsed
 *   by [YtDlpFormats] (the CLI equivalent of the library's VideoInfo).
 * - Downloads always pair video with the best audio (never a bare
 *   video-only format id) and merge to mp4 via the bundled FFmpeg
 *   (--ffmpeg-location), exactly like the Android engine.
 * - No DRM, paywall, login or platform-protection bypass. No cookies,
 *   no credentials, no account login. Errors surface as-is.
 * - The Windows exe bundles curl_cffi, so extractor impersonation works
 *   here — the Android impersonation limit does not apply on PC.
 *
 * All blocking calls must run off the UI thread (the caller uses
 * Dispatchers.IO). Cancel with [cancel] using the same process id.
 */
object YtDlpCli {

    private const val INFO_TIMEOUT_SECONDS = 180L
    private const val VERSION_TIMEOUT_SECONDS = 20L
    private const val MAX_JSON_BYTES = 32L * 1024 * 1024

    private val processes = ConcurrentHashMap<String, Process>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    private fun exeOrThrow(): File =
        Tools.ytDlpExe() ?: throw YtDlpException(
            "yt-dlp.exe was not found. The installer should include it — please reinstall VideoX."
        )

    /** Current yt-dlp version (e.g. "2026.10.26"), or null if unknown. */
    fun version(): String? {
        val exe = Tools.ytDlpExe() ?: return null
        return try {
            val out = runCapture(listOf(exe.absolutePath, "--version"), VERSION_TIMEOUT_SECONDS)
            out.stdout.trim().lineSequence().firstOrNull()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Run yt-dlp --dump-single-json --skip-download and return the raw
     * JSON. Nothing is downloaded. Throws [YtDlpException] when the
     * process fails or returns no JSON.
     */
    fun dumpVideoJson(url: String, proxyUrl: String?, extractorArgs: String?): String {
        val exe = exeOrThrow()
        val args = mutableListOf(exe.absolutePath, "--dump-single-json", "--skip-download")
        proxyUrl?.takeIf { it.isNotBlank() }?.let { args += listOf("--proxy", it) }
        extractorArgs?.let { args += listOf("--extractor-args", it) }
        args += url
        val out = runCapture(args, INFO_TIMEOUT_SECONDS)
        if (out.exitCode != 0) {
            throw YtDlpException(
                "yt-dlp could not read this link.",
                out.stderr.takeLast(1000)
            )
        }
        return out.stdout.takeIf { it.isNotBlank() }
            ?: throw YtDlpException("yt-dlp returned no video information.")
    }

    /** Fetch title + available formats for a URL. */
    fun getInfo(url: String, proxyUrl: String? = null, extractorArgs: String? = null): YtVideoInfo {
        val json = dumpVideoJson(url, proxyUrl, extractorArgs)
        val info = YtDlpFormats.parse(json)
        if (info.title.isEmpty() && info.formats.isEmpty()) {
            throw YtDlpException("yt-dlp returned no video information.")
        }
        return info.copy(title = info.title.ifEmpty { url })
    }

    /**
     * Check whether the Android-player-client (360p) fallback would
     * download this video, WITHOUT downloading anything: runs yt-dlp
     * in simulate mode. True means the user can be offered the 360p
     * download as an explicit choice; false means there is no fallback.
     */
    fun probeFallback(url: String, proxyUrl: String? = null): Boolean {
        return try {
            val exe = exeOrThrow()
            val args = mutableListOf(
                exe.absolutePath, "--simulate",
                "-f", QualitySelector.ANDROID_FALLBACK_SELECTOR,
                "--extractor-args", QualitySelector.ANDROID_FALLBACK_ARGS
            )
            proxyUrl?.takeIf { it.isNotBlank() }?.let { args += listOf("--proxy", it) }
            args += url
            runCapture(args, INFO_TIMEOUT_SECONDS).exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Download with yt-dlp. [request.formatSelector] is built by
     * [QualitySelector] (e.g. "bestvideo+bestaudio/best"). [onProgress]
     * receives percent (0-100) and ETA seconds (-1 when unknown).
     * Cancel with [cancel] using the same [processId].
     */
    fun download(
        request: DownloadRequest,
        processId: String,
        onProgress: (Float, Long) -> Unit
    ): DownloadOutcome {
        val exe = exeOrThrow()
        val dir = Tools.downloadDir()
        val args = mutableListOf<String>()
        args += exe.absolutePath
        args += listOf("-o", File(dir, "${request.outputBaseName}.%(ext)s").absolutePath)
        args += "--no-overwrites"
        request.proxyUrl?.takeIf { it.isNotBlank() }?.let { args += listOf("--proxy", it) }
        request.extractorArgs?.let { args += listOf("--extractor-args", it) }
        Tools.ffmpegDir()?.let { args += listOf("--ffmpeg-location", it.absolutePath) }
        if (request.audioOnly) {
            args += listOf("-f", "bestaudio/best")
            args += "--extract-audio"
            args += listOf("--audio-format", request.audioFormat)
        } else {
            args += listOf("-f", request.formatSelector ?: "bestvideo+bestaudio/best")
        }
        args += listOf("--merge-output-format", "mp4")
        request.extraOptions.forEach { (option, value) ->
            if (value == null) args += option else args += listOf(option, value)
        }
        args += request.url

        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        processes[processId] = process
        val tail = StringBuilder()
        var alreadyDownloaded = false
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (tail.length < 4000) {
                        tail.append(line).append('\n')
                        if (tail.length > 4000) tail.delete(0, tail.length - 4000)
                    }
                    if (QualitySelector.isAlreadyDownloaded(line)) alreadyDownloaded = true
                    parseProgressLine(line)?.let { onProgress(it.percent, it.etaSeconds) }
                }
            }
            val exitCode = process.waitFor()
            if (cancelledIds.contains(processId)) {
                throw YtDlpCancelledException()
            }
            if (exitCode != 0) {
                throw YtDlpException("Download failed.", tail.toString().takeLast(1000))
            }
            return DownloadOutcome(exitCode, alreadyDownloaded, findOutputFile(dir, request.outputBaseName))
        } finally {
            processes.remove(processId)
            cancelledIds.remove(processId)
            if (process.isAlive) process.destroy()
        }
    }

    /** The file yt-dlp wrote for [outputBaseName], newest match wins. */
    internal fun findOutputFile(dir: File, outputBaseName: String): File? {
        val files = dir.listFiles() ?: return null
        return files
            .filter { it.isFile && it.name.startsWith("$outputBaseName.") }
            .filterNot { it.name.endsWith(".part") || it.name.endsWith(".ytdl") || it.name.endsWith(".temp") }
            .maxByOrNull { it.lastModified() }
    }

    fun cancel(processId: String) {
        cancelledIds.add(processId)
        try {
            processes[processId]?.destroy()
        } catch (_: Exception) {
            // Process already finished or never started — nothing to cancel.
        }
    }

    /**
     * Self-update: fetch the latest stable yt-dlp.exe from GitHub into
     * the per-user tools dir (never the protected install dir), after
     * verifying its SHA-256 against the release's SHA2-256SUMS file.
     * Returns "DONE" when a newer version was installed,
     * "ALREADY_UP_TO_DATE" when there was nothing newer.
     * Throws on network failure. Run on a background thread.
     */
    fun updateEngine(): String {
        val current = version()
        val latest = latestReleaseTag()
        if (!isNewerVersion(latest, current)) return "ALREADY_UP_TO_DATE"
        val dir = Tools.userToolsDir()
        val tmpExe = File(dir, "yt-dlp.exe.tmp")
        val tmpSums = File(dir, "SHA2-256SUMS.tmp")
        try {
            downloadFile(
                URL("https://github.com/yt-dlp/yt-dlp/releases/download/$latest/yt-dlp.exe"),
                tmpExe
            )
            downloadFile(
                URL("https://github.com/yt-dlp/yt-dlp/releases/download/$latest/SHA2-256SUMS"),
                tmpSums
            )
            val expected = tmpSums.readLines()
                .firstOrNull { it.endsWith("yt-dlp.exe") }
                ?.substringBefore(" ")
                ?.trim()
                ?: throw IOException("The release checksum file did not list yt-dlp.exe.")
            val actual = Sha256.fileHex(tmpExe)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IOException("The downloaded yt-dlp.exe did not match its published checksum.")
            }
            val target = File(dir, Tools.YT_DLP_EXE)
            if (target.exists() && !target.delete()) {
                throw IOException("Could not replace the old yt-dlp.exe (is a download running?).")
            }
            if (!tmpExe.renameTo(target)) {
                throw IOException("Could not install the downloaded yt-dlp.exe.")
            }
            File(dir, "yt-dlp.version.txt").writeText(latest)
            return "DONE"
        } finally {
            tmpExe.delete()
            tmpSums.delete()
        }
    }

    /** Latest stable release tag (e.g. "2026.10.26") from the GitHub redirect. */
    internal fun latestReleaseTag(): String {
        val conn = URL("https://github.com/yt-dlp/yt-dlp/releases/latest")
            .openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "VideoX-PC")
        return try {
            conn.connect()
            val location = conn.getHeaderField("Location")
                ?: conn.url.toString()
            val tag = location.trim().substringAfterLast("/").substringBefore("?")
            if (tag.isEmpty()) throw IOException("Could not determine the latest yt-dlp release.")
            tag
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(url: URL, dest: File) {
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "VideoX-PC")
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("Download failed (HTTP ${conn.responseCode}).")
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private data class Captured(val exitCode: Int, val stdout: String, val stderr: String)

    /** Run a command, capturing stdout (capped) and stderr separately. */
    private fun runCapture(args: List<String>, timeoutSeconds: Long): Captured {
        val process = ProcessBuilder(args).start()
        var stdout = ""
        var stderr = ""
        val outReader = Thread {
            stdout = readCapped(process.inputStream, MAX_JSON_BYTES)
        }
        val errReader = Thread {
            stderr = readCapped(process.errorStream, 64 * 1024)
        }
        outReader.start()
        errReader.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw YtDlpException("yt-dlp took too long to respond.")
        }
        outReader.join(10_000)
        errReader.join(10_000)
        return Captured(process.exitValue(), stdout, stderr)
    }

    private fun readCapped(stream: InputStream, capBytes: Long): String {
        val bytes = ByteArray(8192)
        val out = StringBuilder()
        var total = 0L
        while (true) {
            val read = stream.read(bytes)
            if (read < 0) break
            total += read
            if (total > capBytes) break
            out.append(String(bytes, 0, read, Charsets.UTF_8))
        }
        return out.toString()
    }
}
