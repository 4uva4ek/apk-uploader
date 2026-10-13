package apkuploader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import apkuploader.AppModel
import apkuploader.adb.formatBytes
import apkuploader.install.ItemState
import apkuploader.install.QueueItem
import org.jetbrains.skia.Image

@Composable
fun QueueList(
    model: AppModel,
    modifier: Modifier = Modifier,
    onUninstallAndRetry: (QueueItem.Installable) -> Unit,
    onUninstall: (QueueItem.Installable) -> Unit,
    onRemoveCache: (QueueItem) -> Unit,
) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(model.queue.items, key = { it.id }) { item ->
            ItemCard(item, model, onUninstallAndRetry, onUninstall, onRemoveCache)
        }
    }
}

@Composable
private fun ItemCard(
    item: QueueItem,
    model: AppModel,
    onUninstallAndRetry: (QueueItem.Installable) -> Unit,
    onUninstall: (QueueItem.Installable) -> Unit,
    onRemoveCache: (QueueItem) -> Unit,
) {
    val state = item.state
    val isCurrent = model.queue.current === item
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ItemIcon(item)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WarningAmber) }
                if (item is QueueItem.Cache) {
                    item.plan.notes.take(2).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(6.dp))
                StateLine(state)
                if (state is ItemState.Uploading) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                } else if (state is ItemState.Working) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state is ItemState.NeedsInput && item is QueueItem.Cache) {
                    TextButton(onClick = { model.needsInput(item) }) { Text("Choose target") }
                }
                if (state is ItemState.Failed && state.offerUninstall && item is QueueItem.Installable) {
                    TextButton(onClick = { onUninstallAndRetry(item) }) { Text("Uninstall and retry") }
                }
                if (!state.isActive && item is QueueItem.Installable) {
                    TextButton(onClick = { onUninstall(item) }) { Text("Uninstall") }
                }
                val removableCache = (item is QueueItem.Cache && (item.plan.packageName != null || !item.plan.needsPackage)) ||
                    (item is QueueItem.Bundle && item.bundle.obbs.isNotEmpty())
                if (!state.isActive && removableCache) {
                    IconButton(onClick = { onRemoveCache(item) }) { Icon(Icons.Default.DeleteSweep, contentDescription = "Remove cache from the headset") }
                }
                if (state.isFinished) {
                    IconButton(onClick = { model.queue.retry(item) }) { Icon(Icons.Default.Refresh, contentDescription = "Retry") }
                }
                if (isCurrent && state.isActive) {
                    IconButton(onClick = { model.queue.cancelCurrent() }) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                } else if (!isCurrent) {
                    IconButton(onClick = { model.queue.remove(item) }) { Icon(Icons.Default.Delete, contentDescription = "Remove from the queue") }
                }
            }
        }
    }
}

@Composable
private fun ItemIcon(item: QueueItem) {
    val bitmap: ImageBitmap? = remember(item.id) {
        item.icon?.let { bytes -> runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
    } else {
        Icon(
            when (item) {
                is QueueItem.Installable -> Icons.Default.Android
                is QueueItem.Cache -> Icons.Default.Folder
                is QueueItem.Uninstall -> Icons.Default.Delete
                is QueueItem.RemoveCache -> Icons.Default.FolderZip
            },
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = if (item is QueueItem.Uninstall || item is QueueItem.RemoveCache) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun StateLine(state: ItemState) {
    val colors = MaterialTheme.colorScheme
    val (text, color) = when (state) {
        ItemState.Queued -> "Queued" to colors.onSurfaceVariant
        ItemState.WaitingForDevice -> "Waiting for the headset…" to WarningAmber
        ItemState.NeedsInput -> "Choose where this folder goes on the headset" to WarningAmber
        is ItemState.Uploading -> buildString {
            append("Uploading ").append(formatBytes(state.sent)).append(" / ").append(formatBytes(state.total))
            if (state.bytesPerSecond > 0) append(" · ").append(formatBytes(state.bytesPerSecond.toLong())).append("/s")
            state.remainingSeconds?.let { append(" · ").append(formatDuration(it)).append(" left") }
        } to colors.onSurface
        is ItemState.Working -> state.label to colors.onSurface
        is ItemState.Done -> "✓ ${state.message}" to SuccessGreen
        is ItemState.Failed -> state.message to colors.error
        ItemState.Cancelled -> "Cancelled" to colors.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

fun formatDuration(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m ${seconds % 60}s"
    return "${minutes / 60}h ${minutes % 60}m"
}
