package com.videox.pc.engine

import java.io.File

/**
 * Locates the bundled sidecar binaries (yt-dlp.exe, ffmpeg.exe,
 * ffprobe.exe) and the per-user tools directory.
 *
 * Packaged apps: the Compose plugin sets the system property
 * `compose.application.resources.dir` to the directory holding the
 * files from `desktop-resources/` (see app/build.gradle.kts). The CI
 * workflow places the sidecars under `windows-x64/` there.
 * Unpackaged runs (gradle run, dev): falls back to
 * `<working dir>/desktop-resources/windows-x64/`.
 *
 * The resolution order and the system property name are UNVERIFIED
 * until the first packaged run — the `--selftest` flag exists to
 * catch a wrong path before any release is called working.
 */
object Tools {

    const val YT_DLP_EXE = "yt-dlp.exe"
    const val FFMPEG_EXE = "ffmpeg.exe"
    const val FFPROBE_EXE = "ffprobe.exe"

    /** Directory holding the bundled sidecars, or null when not found. */
    fun bundledToolsDir(): File? {
        val candidates = listOfNotNull(
            System.getProperty("compose.application.resources.dir")?.let { File(it, "windows-x64") },
            File(System.getProperty("user.dir"), "desktop-resources/windows-x64")
        )
        return candidates.firstOrNull { it.isDirectory }
    }

    /** One bundled tool, or null when the file is missing. */
    fun bundledTool(name: String): File? =
        bundledToolsDir()?.let { File(it, name) }?.takeIf { it.isFile }

    /**
     * Per-user tools directory: %LOCALAPPDATA%/VideoX/tools. Holds the
     * self-updated yt-dlp.exe (the install dir under Program Files is
     * not writable without elevation, so updates never touch it).
     */
    fun userToolsDir(): File {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?: File(System.getProperty("user.home"), "AppData/Local").absolutePath
        return File(localAppData, "VideoX/tools").apply { mkdirs() }
    }

    /**
     * The yt-dlp binary to run: the self-updated copy in the user tools
     * dir when one exists (the updater only writes it after verifying a
     * newer release), otherwise the bundled copy. Null when neither
     * exists — the caller must report that honestly.
     */
    fun ytDlpExe(): File? {
        val updated = File(userToolsDir(), YT_DLP_EXE).takeIf { it.isFile }
        return updated ?: bundledTool(YT_DLP_EXE)
    }

    /** Directory containing ffmpeg.exe/ffprobe.exe, or null. */
    fun ffmpegDir(): File? {
        val dir = bundledToolsDir() ?: return null
        return dir.takeIf { File(dir, FFMPEG_EXE).isFile }
    }

    /** The user's download folder: %USERPROFILE%/Downloads/VideoX. */
    fun downloadDir(): File {
        val downloads = File(System.getProperty("user.home"), "Downloads")
        return File(downloads, "VideoX").apply { mkdirs() }
    }
}
