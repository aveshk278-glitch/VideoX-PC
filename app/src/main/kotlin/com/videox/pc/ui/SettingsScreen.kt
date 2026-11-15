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
import com.videox.pc.engine.Tools
import com.videox.pc.shared.QualitySelector
import com.videox.pc.state.DownloadController
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun SettingsScreen(
    controller: DownloadController,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit
) {
    val state by controller.state.collectAsState()
    val settings = controller.settings
    var proxyText by remember { mutableStateOf(settings.proxyUrl) }
    var subtitleLangsText by remember { mutableStateOf(settings.subtitleLangs) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var maxConcurrent by remember { mutableStateOf(settings.maxConcurrentDownloads) }
    var subtitlesEnabled by remember { mutableStateOf(settings.subtitlesEnabled) }
    var embedChapters by remember { mutableStateOf(settings.embedChapters) }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionTitle("Settings")

        PremiumCard {
            SectionTitle("Appearance")
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = darkTheme,
                    onCheckedChange = { onDarkThemeChange(it) }
                )
                Text(
                    text = "Dark theme",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        PremiumCard {
            SectionTitle("Downloads")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Files are saved to:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = Tools.downloadDir().absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Downloads at once:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.width(12.dp))
                SecondaryButton("−") {
                    val next = (maxConcurrent - 1).coerceIn(1, 3)
                    maxConcurrent = next
                    settings.maxConcurrentDownloads = next
                    controller.onMaxConcurrentChanged()
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = maxConcurrent.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.width(8.dp))
                SecondaryButton("+") {
                    val next = (maxConcurrent + 1).coerceIn(1, 3)
                    maxConcurrent = next
                    settings.maxConcurrentDownloads = next
                    controller.onMaxConcurrentChanged()
                }
            }
        }

        PremiumCard {
            SectionTitle("Network")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = proxyText,
                onValueChange = { proxyText = it },
                label = { Text("Proxy URL (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Save proxy") {
                    val trimmed = proxyText.trim()
                    if (trimmed.isEmpty() || QualitySelector.isValidProxyUrl(trimmed)) {
                        settings.proxyUrl = trimmed
                        backupMessage = "Proxy saved."
                    } else {
                        backupMessage = "That proxy URL does not look valid (expected http://host:port)."
                    }
                }
            }
            if (proxyText.isNotBlank() && !QualitySelector.isValidProxyUrl(proxyText.trim())) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Expected format: http://host:port",
                    style = MaterialTheme.typography.bodySmall,
                    color = VideoXError
                )
            }
        }

        PremiumCard {
            SectionTitle("Subtitles and chapters")
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = subtitlesEnabled,
                    onCheckedChange = {
                        subtitlesEnabled = it
                        settings.subtitlesEnabled = it
                    }
                )
                Text(
                    text = "Embed subtitles into downloads",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = embedChapters,
                    onCheckedChange = {
                        embedChapters = it
                        settings.embedChapters = it
                    }
                )
                Text(
                    text = "Embed chapter markers",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = subtitleLangsText,
                onValueChange = { subtitleLangsText = it },
                label = { Text("Subtitle languages (e.g. en,hi or all)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            PrimaryButton("Save subtitle languages") {
                settings.subtitleLangs = subtitleLangsText
                subtitleLangsText = settings.subtitleLangs
                backupMessage = "Subtitle languages saved."
            }
        }

        PremiumCard {
            SectionTitle("Download engine")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "yt-dlp ${state.engineVersion ?: "unknown"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Updates download to your user folder — the install folder is never modified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrimaryButton("Update engine", enabled = !state.isUpdatingEngine) {
                    controller.updateEngine()
                }
                if (state.isUpdatingEngine) {
                    CircularProgressIndicator()
                }
            }
        }

        PremiumCard {
            SectionTitle("Text extraction")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Translation is not available in the PC version yet. " +
                    "Subtitle extraction and speech transcription arrive with the next update.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        PremiumCard {
            SectionTitle("Settings backup")
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Export") {
                    val file = pickFile(save = true, defaultName = "videox-settings.json")
                    if (file != null) {
                        try {
                            file.writeText(settings.exportBackup())
                            backupMessage = "Settings exported."
                        } catch (e: Exception) {
                            backupMessage = "Export failed: ${e.message ?: "could not write the file"}"
                        }
                    }
                }
                SecondaryButton("Import") {
                    val file = pickFile(save = false, defaultName = null)
                    if (file != null) {
                        try {
                            val ok = settings.importBackup(file.readText())
                            backupMessage = if (ok) {
                                "Settings imported. Some changes apply after restart."
                            } else {
                                "That file is not a valid VideoX settings backup."
                            }
                        } catch (e: Exception) {
                            backupMessage = "Import failed: ${e.message ?: "could not read the file"}"
                        }
                    }
                }
            }
            backupMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        PremiumCard {
            SectionTitle("About")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "VideoX 1.0 for Windows. Downloads public video links " +
                    "with the yt-dlp engine and direct media files with a plain " +
                    "HTTP download. Quality is chosen by resolution and is never " +
                    "silently downgraded. It does not bypass DRM, paywalls or " +
                    "logins, and never collects passwords.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun pickFile(save: Boolean, defaultName: String?): File? {
    return try {
        val dialog = FileDialog(
            null as Frame?,
            if (save) "Export settings" else "Import settings",
            if (save) FileDialog.SAVE else FileDialog.LOAD
        )
        if (defaultName != null) dialog.file = defaultName
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        if (dir != null && name != null) File(dir, name) else null
    } catch (_: Exception) {
        null
    }
}
