package com.videox.pc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videox.pc.engine.DirectDownloader
import com.videox.pc.state.DownloadController
import com.videox.pc.store.HistoryItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DownloadsScreen(controller: DownloadController) {
    val items by controller.historyItems.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionTitle("Downloads")
            if (items.isNotEmpty()) {
                SecondaryButton("Clear history") {
                    controller.clearHistory()
                }
            }
        }
        if (items.isEmpty()) {
            Text(
                text = "No downloads yet. Files you download appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { item ->
                    HistoryRow(item, controller)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, controller: DownloadController) {
    PremiumCard {
        Text(
            text = item.title.ifBlank { item.fileName },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = listOf(
                item.fileName,
                item.status,
                DirectDownloader.formatSize(item.fileSize).takeIf { item.fileSize > 0 },
                formatTime(item.timestamp).takeIf { item.timestamp > 0 }
            ).filterNotNull().joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (item.status == "Completed" || item.status == "Already downloaded") {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Open") {
                    controller.openHistoryFile(item)
                }
                SecondaryButton("Show in folder") {
                    controller.showHistoryFileInFolder(item)
                }
                SecondaryButton("Remove") {
                    controller.removeHistoryItem(item.id)
                }
            }
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            SecondaryButton("Remove") {
                controller.removeHistoryItem(item.id)
            }
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

private fun formatTime(timestamp: Long): String {
    return try {
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
    } catch (_: Exception) {
        ""
    }
}
