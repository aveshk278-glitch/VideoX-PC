package com.videox.pc.engine

import com.videox.pc.shared.UpscaleMath
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled FFmpeg/ffprobe binaries (gyan.dev static builds).
 * Unlike Android, there is no library extraction step: the static
 * exes run directly from the install dir. Probing is ffprobe-only —
 * there is no MediaMetadataRetriever equivalent on desktop, so when
 * ffprobe cannot read a file the error says so honestly.
 *
 * Phase 1 uses [verifyBinary] (selftest) and the ffmpeg dir for
 * yt-dlp merging; [probeVideo] and [hasHardwareEncoder] are the
 * plumbing Phase 2 (upscaler) builds on.
 */
object FfmpegRunner {

    private var cachedHardwareEncoder: Boolean? = null

    private fun ffmpegExe(): File? = Tools.bundledTool(Tools.FFMPEG_EXE)
    private fun ffprobeExe(): File? = Tools.bundledTool(Tools.FFPROBE_EXE)

    /** True when the ffmpeg binary starts and reports its version. */
    fun verifyBinary(): Boolean {
        val exe = ffmpegExe() ?: return false
        return try {
            val (code, out) = runCapture(listOf(exe.absolutePath, "-version"), 15)
            code == 0 && out.contains("ffmpeg version", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /** True when ffprobe starts and reports its version. */
    fun verifyFfprobe(): Boolean {
        val exe = ffprobeExe() ?: return false
        return try {
            val (code, out) = runCapture(listOf(exe.absolutePath, "-version"), 15)
            code == 0 && out.contains("ffprobe version", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /** Probe resolution, frame rate, duration and audio presence via ffprobe CSV. */
    fun probeVideo(file: File): UpscaleMath.ProbeResult {
        val exe = ffprobeExe()
            ?: throw IOException("ffprobe.exe was not found, so this video cannot be read.")
        val (videoCode, videoOut) = runCapture(
            UpscaleMath.probeVideoArgs(exe.absolutePath, file.absolutePath), 30
        )
        val (_, audioOut) = runCapture(
            UpscaleMath.probeAudioArgs(exe.absolutePath, file.absolutePath), 30
        )
        if (videoCode != 0) {
            throw IOException(
                "Could not read this video's resolution or frame rate. " +
                    "ffprobe detail: exit code $videoCode, output: ${videoOut.trim().take(200)}"
            )
        }
        return UpscaleMath.parseProbeCsv(videoOut.trim().lineSequence().firstOrNull().orEmpty(), audioOut.trim())
            ?: throw IOException(
                "Could not read this video's resolution or frame rate. " +
                    "ffprobe detail: exit code $videoCode, output: ${videoOut.trim().take(200)}"
            )
    }

    /** True when this FFmpeg build offers a Windows hardware H.264 encoder. Probed once, then cached. */
    fun hasHardwareEncoder(): Boolean {
        cachedHardwareEncoder?.let { return it }
        val found = try {
            val exe = ffmpegExe() ?: return false.also { cachedHardwareEncoder = false }
            val (code, out) = runCapture(UpscaleMath.encodersArgs(exe.absolutePath), 30)
            code == 0 && (UpscaleMath.encoderListed(out, "h264_mf") || UpscaleMath.encoderListed(out, "h264_nvenc"))
        } catch (_: Exception) {
            false
        }
        cachedHardwareEncoder = found
        return found
    }

    private fun runCapture(args: List<String>, timeoutSeconds: Long): Pair<Int, String> {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (output.length < 8192) output.append(line).append('\n')
                }
            }
        }
        reader.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IOException("The media tool took too long to respond.")
        }
        reader.join(10_000)
        return process.exitValue() to output.toString()
    }
}
