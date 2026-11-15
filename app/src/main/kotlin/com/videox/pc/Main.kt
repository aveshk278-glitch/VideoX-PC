package com.videox.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.videox.pc.engine.FfmpegRunner
import com.videox.pc.engine.Tools
import com.videox.pc.engine.YtDlpCli
import com.videox.pc.state.DownloadController
import com.videox.pc.store.HistoryItem
import com.videox.pc.store.JsonHistory
import com.videox.pc.store.JsonSettings
import com.videox.pc.ui.DownloadsScreen
import com.videox.pc.ui.HomeScreen
import com.videox.pc.ui.SettingsScreen
import com.videox.pc.ui.VideoXPurple
import com.videox.pc.ui.VideoXTextPrimary
import com.videox.pc.ui.VideoXTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.system.exitProcess

/**
 * VideoX for Windows — entry point.
 *
 * `--selftest` runs the headless smoke test and exits with a report
 * (used by CI on the packaged app; it must never open a window).
 * Without arguments the desktop UI opens.
 */
fun main(args: Array<String>) {
    if (args.contains("--selftest")) {
        exitProcess(runSelfTest())
    }
    application {
        val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val settings = remember { JsonSettings() }
        val history = remember { JsonHistory() }
        val controller = remember { DownloadController(settings, history, appScope) }
        var darkTheme by remember { mutableStateOf(settings.darkTheme) }
        var screen by remember { mutableStateOf(0) }
        val windowState = rememberWindowState(width = 960.dp, height = 720.dp)

        Window(
            onCloseRequest = {
                appScope.cancel()
                exitApplication()
            },
            state = windowState,
            title = "VideoX"
        ) {
            VideoXTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NavButton("Home", selected = screen == 0) { screen = 0 }
                            NavButton("Downloads", selected = screen == 1) {
                                controller.refreshHistory()
                                screen = 1
                            }
                            NavButton("Settings", selected = screen == 2) {
                                controller.refreshEngineVersion()
                                screen = 2
                            }
                        }
                        when (screen) {
                            0 -> HomeScreen(controller)
                            1 -> DownloadsScreen(controller)
                            2 -> SettingsScreen(
                                controller = controller,
                                darkTheme = darkTheme,
                                onDarkThemeChange = {
                                    darkTheme = it
                                    settings.darkTheme = it
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VideoXPurple,
                contentColor = VideoXTextPrimary
            )
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(text)
        }
    }
}

/**
 * Headless smoke test for CI: verifies the packaged app can find and
 * run its sidecars and read/write its data files, without opening a
 * window. Prints one line per check. Returns 0 when every check
 * passes, 1 otherwise. Phase 1 checks only what Phase 1 bundles:
 * yt-dlp, ffmpeg, ffprobe, settings/history round-trips and a
 * writable download dir. ONNX/Vosk checks arrive with their phases.
 */
internal fun runSelfTest(): Int {
    var failures = 0
    fun check(name: String, block: () -> String?) {
        try {
            val detail = block()
            if (detail == null) {
                println("OK: $name")
            } else {
                println("FAIL: $name — $detail")
                failures++
            }
        } catch (e: Exception) {
            println("FAIL: $name — ${e.message ?: e.javaClass.simpleName}")
            failures++
        }
    }

    check("yt-dlp.exe resolves and reports a version") {
        val exe = Tools.ytDlpExe()
            ?: return@check "yt-dlp.exe was not found (bundled dir: ${Tools.bundledToolsDir()?.absolutePath ?: "none"})"
        val version = YtDlpCli.version()
        if (version.isNullOrBlank()) "yt-dlp.exe at ${exe.absolutePath} did not report a version" else null
    }
    check("ffmpeg.exe runs") {
        if (!FfmpegRunner.verifyBinary()) "ffmpeg -version did not report an ffmpeg version" else null
    }
    check("ffprobe.exe runs") {
        if (!FfmpegRunner.verifyFfprobe()) "ffprobe -version did not report an ffprobe version" else null
    }
    check("settings JSON round-trip") {
        val tmp = File.createTempFile("videox-selftest-settings", ".json")
        try {
            val first = JsonSettings(tmp)
            first.proxyUrl = "http://127.0.0.1:8080"
            first.maxConcurrentDownloads = 3
            val second = JsonSettings(tmp)
            if (second.proxyUrl != "http://127.0.0.1:8080") return@check "proxy value did not survive reload"
            if (second.maxConcurrentDownloads != 3) return@check "max_concurrent did not survive reload"
            null
        } finally {
            tmp.delete()
        }
    }
    check("history JSON round-trip") {
        val tmp = File.createTempFile("videox-selftest-history", ".json")
        try {
            val store = JsonHistory(tmp)
            store.add(
                HistoryItem(
                    id = "selftest", url = "https://example.com/v",
                    title = "Self test", fileName = "test.mp4",
                    fileSize = 123L, status = "Completed", kind = "video",
                    timestamp = 1L
                )
            )
            val items = JsonHistory(tmp).all()
            if (items.size != 1 || items[0].fileName != "test.mp4") {
                return@check "history entry did not survive reload"
            }
            null
        } finally {
            tmp.delete()
        }
    }
    check("download dir is writable") {
        val dir = Tools.downloadDir()
        val probe = File(dir, ".videox-selftest-write")
        try {
            probe.writeText("ok")
            if (!probe.exists()) return@check "could not write to ${dir.absolutePath}"
            null
        } finally {
            probe.delete()
        }
    }

    if (failures == 0) {
        println("SELFTEST: all checks passed.")
    } else {
        println("SELFTEST: $failures check(s) failed.")
    }
    return if (failures == 0) 0 else 1
}
