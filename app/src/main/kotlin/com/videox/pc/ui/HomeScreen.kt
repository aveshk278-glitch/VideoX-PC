package com.videox.pc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videox.pc.engine.DirectDownloader
import com.videox.pc.shared.QualitySelector
import com.videox.pc.state.DownloadController

@Composable
fun HomeScreen(controller: DownloadController) {
    val state by controller.state.collectAsState()
    var urlText by remember { mutableStateOf("") }
    var fileNameText by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionTitle("Download a video")
        Text(
            text = "Paste a link to a public video. VideoX downloads only " +
                "what the source permits — no DRM, paywall or login bypass.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        PremiumCard {
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text("Video URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Paste") {
                    controller.pasteFromClipboard()?.let { pasted ->
                        urlText = pasted.trim()
                    }
                }
                PrimaryButton("Get video", enabled = !state.isFetchingInfo) {
                    controller.fetchInfo(urlText)
                }
                if (state.isFetchingInfo) {
                    CircularProgressIndicator()
                }
            }
        }

        state.message?.let { message ->
            MessageBanner(text = message, isError = state.isMessageError)
        }

        state.fallbackOffer?.let { offer ->
            PremiumCard {
                SectionTitle("Only a lower quality may work")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = offer.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "A 360p version may still download. Download it only if you accept the lower quality.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Download 360p version") {
                        controller.startFallbackDownload()
                    }
                    SecondaryButton("Dismiss") {
                        controller.dismissFallbackOffer()
                    }
                }
            }
        }

        state.directProbe?.let { probe ->
            PremiumCard {
                SectionTitle("Direct media file")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = probe.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${probe.contentType} • ${DirectDownloader.formatSize(probe.contentLength)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryButton("Download file") {
                    controller.startDownload()
                }
            }
        }

        state.info?.let { info ->
            PremiumCard {
                SectionTitle(info.title)
                Spacer(modifier = Modifier.height(4.dp))
                val meta = listOf(
                    info.uploader.takeIf { it.isNotBlank() },
                    formatDuration(info.durationSeconds).takeIf { info.durationSeconds > 0 }
                ).filterNotNull().joinToString(" • ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.audioOnly,
                        onCheckedChange = { controller.setAudioOnly(it) }
                    )
                    Text(
                        text = "Audio only",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                if (state.audioOnly) {
                    listOf("mp3", "m4a", "opus").forEach { format ->
                        SelectableRow(
                            label = format.uppercase(),
                            sublabel = null,
                            selected = state.audioFormat == format,
                            onClick = { controller.setAudioFormat(format) }
                        )
                    }
                } else {
                    val heights = QualitySelector.availableHeights(info.formats)
                    SelectableRow(
                        label = "Best Quality",
                        sublabel = QualitySelector.maxQualityLabel(info.formats)
                            .takeIf { it.isNotBlank() },
                        selected = state.selectedHeight == null,
                        onClick = { controller.setSelectedHeight(null) }
                    )
                    heights.forEach { height ->
                        val size = QualitySelector.estimatedSizeForHeight(info.formats, height)
                        SelectableRow(
                            label = QualitySelector.qualityLabel(height),
                            sublabel = if (size > 0) "About ${DirectDownloader.formatSize(size)}" else null,
                            selected = state.selectedHeight == height,
                            onClick = { controller.setSelectedHeight(height) }
                        )
                    }
                    if (heights.isEmpty()) {
                        Text(
                            text = "No video qualities were reported for this link.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fileNameText,
                    onValueChange = {
                        fileNameText = it
                        controller.setCustomFileName(it)
                    },
                    label = { Text("Custom file name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryButton("Download") {
                    controller.startDownload()
                }
            }
        }

        if (state.active.isNotEmpty()) {
            SectionTitle("Downloading")
            state.active.forEach { active ->
                PremiumCard {
                    Text(
                        text = active.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (active.progressPercent >= 0f) {
                        val fraction = (active.progressPercent / 100f).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = progressText(active.progressPercent, active.etaSeconds, active.downloadedBytes, active.totalBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SecondaryButton("Cancel") {
                            controller.cancelDownload(active.id)
                        }
                    }
                }
            }
            Text(
                text = "Downloads stop if VideoX is closed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return ""
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun progressText(percent: Float, etaSeconds: Long, downloaded: Long, total: Long): String {
    val parts = mutableListOf<String>()
    if (percent >= 0f) parts += "%.1f%%".format(percent)
    if (total > 0) {
        parts += "${DirectDownloader.formatSize(downloaded)} of ${DirectDownloader.formatSize(total)}"
    } else if (downloaded > 0) {
        parts += DirectDownloader.formatSize(downloaded)
    }
    if (etaSeconds >= 0) parts += "ETA ${formatDuration(etaSeconds)}"
    return parts.joinToString(" • ").ifEmpty { "Starting…" }
}
