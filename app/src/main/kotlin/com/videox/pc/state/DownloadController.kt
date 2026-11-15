package com.videox.pc.state

import com.videox.pc.engine.DirectDownloader
import com.videox.pc.engine.DownloadRequest
import com.videox.pc.engine.MediaProbe
import com.videox.pc.engine.Tools
import com.videox.pc.engine.YtDlpCancelledException
import com.videox.pc.engine.YtDlpCli
import com.videox.pc.engine.YtDlpException
import com.videox.pc.shared.QualitySelector
import com.videox.pc.shared.YtVideoInfo
import com.videox.pc.store.HistoryItem
import com.videox.pc.store.JsonHistory
import com.videox.pc.store.JsonSettings
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class ActiveDownload(
    val id: String,
    val url: String,
    val title: String,
    val progressPercent: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val etaSeconds: Long,
    val statusText: String
)

data class FallbackOffer(val url: String, val reason: String)

data class DownloadUiState(
    val info: YtVideoInfo? = null,
    val infoUrl: String = "",
    val infoExtractorArgs: String? = null,
    val directProbe: MediaProbe? = null,
    val isFetchingInfo: Boolean = false,
    val selectedHeight: Int? = null,
    val audioOnly: Boolean = false,
    val audioFormat: String = "mp3",
    val customFileName: String = "",
    val message: String? = null,
    val isMessageError: Boolean = false,
    val active: List<ActiveDownload> = emptyList(),
    val fallbackOffer: FallbackOffer? = null,
    val engineVersion: String? = null,
    val isUpdatingEngine: Boolean = false
)

/**
 * Download logic for the desktop app: URL routing, format selection,
 * queueing and history. Mirrors the Android DownloaderViewModel's
 * rules:
 *
 * - yt-dlp-first for every link that is not proven direct media;
 *   direct media (extension or content-type probe) uses the fast
 *   HTTP path.
 * - Never silently downgrade: a requested quality that is blocked
 *   surfaces an honest error, and the 360p fallback is offered only
 *   as an explicit user choice.
 * - An already-downloaded file is reported as such, never as failure.
 * - Errors come from [QualitySelector.describeYtDlpError], so the
 *   user reads the real reason, not a generic message.
 * - Downloads stop if the app closes (no background services on
 *   desktop) — stated honestly in the UI.
 */
class DownloadController(
    val settings: JsonSettings,
    private val history: JsonHistory,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    private val _historyItems = MutableStateFlow(history.all())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val cancelFlags = mutableMapOf<String, Boolean>()
    private val idCounter = AtomicLong(System.currentTimeMillis())
    private val lastEmittedPercent = mutableMapOf<String, Float>()

    private fun semaphore(): Semaphore = Semaphore(settings.maxConcurrentDownloads)

    @Volatile
    private var queueGate: Semaphore = semaphore()

    fun refreshEngineVersion() {
        scope.launch(Dispatchers.IO) {
            val version = YtDlpCli.version()
            _state.update { it.copy(engineVersion = version) }
        }
    }

    fun updateEngine() {
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(isUpdatingEngine = true) }
            try {
                val result = YtDlpCli.updateEngine()
                val version = YtDlpCli.version()
                _state.update {
                    it.copy(
                        isUpdatingEngine = false,
                        engineVersion = version,
                        message = if (result == "DONE") {
                            "Engine updated to yt-dlp $version."
                        } else {
                            "Engine is already up to date (yt-dlp ${version ?: "unknown"})."
                        },
                        isMessageError = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isUpdatingEngine = false,
                        message = "Engine update failed: ${e.message ?: "network error"}",
                        isMessageError = true
                    )
                }
            }
        }
    }

    fun pasteFromClipboard(): String? {
        return try {
            val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
            if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                contents.getTransferData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun setSelectedHeight(height: Int?) {
        _state.update { it.copy(selectedHeight = height) }
    }

    fun setAudioOnly(audioOnly: Boolean) {
        _state.update { it.copy(audioOnly = audioOnly) }
    }

    fun setAudioFormat(format: String) {
        _state.update { it.copy(audioFormat = format) }
    }

    fun setCustomFileName(name: String) {
        _state.update { it.copy(customFileName = name) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun dismissFallbackOffer() {
        _state.update { it.copy(fallbackOffer = null) }
    }

    fun fetchInfo(rawUrl: String) {
        val url = rawUrl.trim()
        val validationError = DirectDownloader.validateUrl(url)
        if (validationError != null) {
            _state.update { it.copy(message = validationError, isMessageError = true) }
            return
        }
        _state.update {
            it.copy(
                isFetchingInfo = true, info = null, directProbe = null,
                infoUrl = url, fallbackOffer = null, message = null,
                selectedHeight = null
            )
        }
        scope.launch(Dispatchers.IO) {
            try {
                val probe = withContext(Dispatchers.IO) { DirectDownloader.probeDirectMedia(url) }
                if (probe != null) {
                    _state.update { it.copy(isFetchingInfo = false, directProbe = probe) }
                    return@launch
                }
                val attempts = QualitySelector.downloadAttempts(url)
                var lastError: Exception? = null
                var lastRaw = ""
                for (extractorArgs in attempts) {
                    try {
                        val info = YtDlpCli.getInfo(url, proxyOrNull(), extractorArgs)
                        _state.update {
                            it.copy(isFetchingInfo = false, info = info, infoExtractorArgs = extractorArgs)
                        }
                        return@launch
                    } catch (e: YtDlpException) {
                        lastError = e
                        lastRaw = e.rawOutput
                    } catch (e: Exception) {
                        lastError = e
                        lastRaw = e.message.orEmpty()
                    }
                }
                if (QualitySelector.isYouTubeUrl(url) && YtDlpCli.probeFallback(url, proxyOrNull())) {
                    _state.update {
                        it.copy(
                            isFetchingInfo = false,
                            fallbackOffer = FallbackOffer(
                                url,
                                QualitySelector.describeYtDlpError(lastRaw, proxyConfigured())
                            )
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isFetchingInfo = false,
                            message = QualitySelector.describeYtDlpError(lastRaw, proxyConfigured()),
                            isMessageError = true
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isFetchingInfo = false,
                        message = QualitySelector.describeYtDlpError(e.message, proxyConfigured()),
                        isMessageError = true
                    )
                }
            }
        }
    }

    fun startDownload() {
        val current = _state.value
        val probe = current.directProbe
        if (probe != null) {
            startDirectDownload(current.infoUrl, probe)
            return
        }
        val info = current.info ?: return
        val url = current.infoUrl
        val title = info.title
        val baseName = QualitySelector.resolveOutputBaseName(title, current.customFileName.ifBlank { null })
        val existing = findExistingDownload(baseName)
        if (existing != null) {
            recordHistory(url, title, existing.name, existing.length(), "Already downloaded", kindFor(current))
            _state.update {
                it.copy(message = "Already downloaded: ${existing.name}", isMessageError = false)
            }
            return
        }
        val selector = if (current.audioOnly) {
            null
        } else {
            QualitySelector.buildFormatSelector(
                QualitySelector.clampHeight(info.formats, current.selectedHeight)
            )
        }
        val id = "dl_${idCounter.incrementAndGet()}"
        val job = scope.launch(Dispatchers.IO) {
            queueGate.withPermit {
                runYtDlpDownload(id, url, title, baseName, selector, current, extractorArgs = current.infoExtractorArgs)
            }
        }
        jobs[id] = job
        addActive(id, url, title, "Queued…")
    }

    fun startFallbackDownload() {
        val offer = _state.value.fallbackOffer ?: return
        _state.update { it.copy(fallbackOffer = null) }
        val id = "dl_${idCounter.incrementAndGet()}"
        val job = scope.launch(Dispatchers.IO) {
            queueGate.withPermit {
                val current = _state.value
                runYtDlpDownload(
                    id, offer.url, offer.url, QualitySelector.resolveOutputBaseName(offer.url, null),
                    QualitySelector.ANDROID_FALLBACK_SELECTOR, current,
                    extractorArgs = QualitySelector.ANDROID_FALLBACK_ARGS
                )
            }
        }
        jobs[id] = job
        addActive(id, offer.url, offer.url, "Queued…")
    }

    private suspend fun runYtDlpDownload(
        id: String,
        url: String,
        title: String,
        baseName: String,
        selector: String?,
        current: DownloadUiState,
        extractorArgs: String?
    ) {
        updateActive(id) { it.copy(statusText = "Downloading…") }
        val request = DownloadRequest(
            url = url,
            formatSelector = selector,
            audioOnly = current.audioOnly,
            audioFormat = current.audioFormat,
            outputBaseName = baseName,
            extractorArgs = extractorArgs,
            proxyUrl = proxyOrNull(),
            extraOptions = QualitySelector.extraDownloadOptions(
                subtitlesEnabled = settings.subtitlesEnabled,
                subtitleLangs = settings.subtitleLangs,
                embedChapters = settings.embedChapters,
                audioOnly = current.audioOnly
            )
        )
        try {
            val outcome = YtDlpCli.download(request, id) { percent, eta ->
                reportProgress(id, percent, eta)
            }
            val file = outcome.outputFile
            if (outcome.alreadyDownloaded && file != null) {
                recordHistory(url, title, file.name, file.length(), "Already downloaded", kindFor(current))
                showMessage("Already downloaded: ${file.name}", isError = false)
            } else if (file != null) {
                recordHistory(url, title, file.name, file.length(), "Completed", kindFor(current))
                showMessage("Downloaded: ${file.name}", isError = false)
            } else {
                recordHistory(url, title, baseName, 0L, "Completed", kindFor(current))
                showMessage("Download finished, but the output file was not found in ${Tools.downloadDir().absolutePath}.", isError = true)
            }
        } catch (e: YtDlpCancelledException) {
            showMessage("Download cancelled.", isError = false)
        } catch (e: YtDlpException) {
            recordHistory(url, title, baseName, 0L, "Failed", kindFor(current))
            showMessage(QualitySelector.describeYtDlpError(e.rawOutput.ifEmpty { e.message }, proxyConfigured()), isError = true)
        } catch (e: Exception) {
            recordHistory(url, title, baseName, 0L, "Failed", kindFor(current))
            showMessage(QualitySelector.describeYtDlpError(e.message, proxyConfigured()), isError = true)
        } finally {
            removeActive(id)
            jobs.remove(id)
        }
    }

    private fun startDirectDownload(url: String, probe: MediaProbe) {
        val baseName = QualitySelector.resolveOutputBaseName(probe.fileName, _state.value.customFileName.ifBlank { null })
        val existing = findExistingDownload(baseName.substringBeforeLast(".", baseName))
        if (existing != null) {
            recordHistory(url, probe.fileName, existing.name, existing.length(), "Already downloaded", "direct")
            _state.update {
                it.copy(message = "Already downloaded: ${existing.name}", isMessageError = false)
            }
            return
        }
        val destFile = File(Tools.downloadDir(), QualitySelector.sanitizeFileName(probe.fileName))
        val id = "dl_${idCounter.incrementAndGet()}"
        cancelFlags[id] = false
        val job = scope.launch(Dispatchers.IO) {
            queueGate.withPermit {
                updateActive(id) { it.copy(statusText = "Downloading…") }
                try {
                    val written = DirectDownloader.download(
                        url, destFile,
                        onProgress = { downloaded, total ->
                            val percent = if (total > 0) downloaded * 100f / total else -1f
                            reportProgressBytes(id, percent, downloaded, total)
                        },
                        isCancelled = { cancelFlags[id] == true }
                    )
                    recordHistory(url, probe.fileName, destFile.name, written, "Completed", "direct")
                    showMessage("Downloaded: ${destFile.name}", isError = false)
                } catch (e: IOException) {
                    if (cancelFlags[id] == true) {
                        showMessage("Download cancelled.", isError = false)
                    } else {
                        recordHistory(url, probe.fileName, destFile.name, 0L, "Failed", "direct")
                        showMessage("Download failed: ${e.message ?: "network error"}", isError = true)
                    }
                } finally {
                    removeActive(id)
                    jobs.remove(id)
                    cancelFlags.remove(id)
                }
            }
        }
        jobs[id] = job
        addActive(id, url, probe.fileName, "Queued…")
    }

    fun cancelDownload(id: String) {
        cancelFlags[id] = true
        YtDlpCli.cancel(id)
        jobs[id]?.cancel()
    }

    fun cancelAllDownloads() {
        jobs.keys.toList().forEach { cancelDownload(it) }
    }

    fun refreshHistory() {
        _historyItems.value = history.all()
    }

    fun clearHistory() {
        history.clear()
        _historyItems.value = emptyList()
    }

    fun removeHistoryItem(id: String) {
        history.remove(id)
        refreshHistory()
    }

    fun openHistoryFile(item: HistoryItem) {
        val file = File(Tools.downloadDir(), item.fileName)
        if (!file.exists()) {
            showMessage("That file is no longer in ${Tools.downloadDir().absolutePath}.", isError = true)
            return
        }
        try {
            Desktop.getDesktop().open(file)
        } catch (e: Exception) {
            showMessage("Could not open the file: ${e.message ?: "no associated app"}", isError = true)
        }
    }

    fun showHistoryFileInFolder(item: HistoryItem) {
        val file = File(Tools.downloadDir(), item.fileName)
        if (!file.exists()) {
            showMessage("That file is no longer in ${Tools.downloadDir().absolutePath}.", isError = true)
            return
        }
        try {
            Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", file.absolutePath))
        } catch (e: Exception) {
            showMessage("Could not open Explorer: ${e.message ?: "unknown error"}", isError = true)
        }
    }

    fun onMaxConcurrentChanged() {
        queueGate = semaphore()
    }

    private fun kindFor(current: DownloadUiState): String =
        if (current.audioOnly) "audio" else "video"

    private fun proxyOrNull(): String? =
        settings.proxyUrl.takeIf { it.isNotBlank() }

    private fun proxyConfigured(): Boolean = settings.proxyUrl.isNotBlank()

    private fun findExistingDownload(baseName: String): File? {
        val dir = Tools.downloadDir()
        val files = dir.listFiles() ?: return null
        return files.firstOrNull { it.isFile && it.name.startsWith("$baseName.") }
    }

    private fun recordHistory(url: String, title: String, fileName: String, size: Long, status: String, kind: String) {
        history.add(
            HistoryItem(
                id = "h_${idCounter.incrementAndGet()}",
                url = url,
                title = title,
                fileName = fileName,
                fileSize = size,
                status = status,
                kind = kind,
                timestamp = System.currentTimeMillis()
            )
        )
        refreshHistory()
    }

    private fun showMessage(text: String, isError: Boolean) {
        _state.update { it.copy(message = text, isMessageError = isError) }
    }

    private fun addActive(id: String, url: String, title: String, statusText: String) {
        _state.update {
            it.copy(
                active = it.active + ActiveDownload(
                    id = id, url = url, title = title,
                    progressPercent = -1f, downloadedBytes = 0L, totalBytes = 0L,
                    etaSeconds = -1L, statusText = statusText
                )
            )
        }
    }

    private fun updateActive(id: String, change: (ActiveDownload) -> ActiveDownload) {
        _state.update { state ->
            state.copy(active = state.active.map { if (it.id == id) change(it) else it })
        }
    }

    private fun removeActive(id: String) {
        _state.update { state ->
            state.copy(active = state.active.filterNot { it.id == id })
        }
        lastEmittedPercent.remove(id)
    }

    private fun reportProgress(id: String, percent: Float, etaSeconds: Long) {
        val last = lastEmittedPercent[id]
        if (last != null && percent - last < 0.5f && percent < 100f) return
        lastEmittedPercent[id] = percent
        updateActive(id) {
            it.copy(progressPercent = percent, etaSeconds = etaSeconds, statusText = "Downloading…")
        }
    }

    private fun reportProgressBytes(id: String, percent: Float, downloaded: Long, total: Long) {
        val last = lastEmittedPercent[id]
        if (last != null && percent >= 0f && percent - last < 0.5f && percent < 100f) return
        lastEmittedPercent[id] = percent
        updateActive(id) {
            it.copy(
                progressPercent = percent, downloadedBytes = downloaded, totalBytes = total,
                etaSeconds = -1L, statusText = "Downloading…"
            )
        }
    }
}
