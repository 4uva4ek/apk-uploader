package apkuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apkuploader.adb.formatBytes
import apkuploader.cache.CacheKind
import apkuploader.cache.CacheLayout
import apkuploader.install.QueueItem

@Composable
fun ErrorDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, modifier = Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Dismiss") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, modifier = Modifier.widthIn(max = 520.dp)) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks where an unrecognised cache folder belongs. */
@Composable
fun CacheTargetDialog(item: QueueItem.Cache, onConfirm: (String, CacheKind) -> Unit, onCancel: () -> Unit) {
    val plan = item.plan
    var packageName by remember { mutableStateOf(plan.packageName ?: "") }
    var kind by remember { mutableStateOf(if (plan.kind == CacheKind.MIRROR) CacheKind.DATA else plan.kind) }
    val valid = CacheLayout.looksLikePackage(packageName.trim())
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Where does \"${plan.root.name}\" go?") },
        text = {
            Column(Modifier.widthIn(min = 420.dp, max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${plan.files.size} file(s), ${formatBytes(plan.totalBytes)}. " +
                        "The layout of this folder was not recognised. Enter the game's package name and choose the destination.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                plan.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Package name (com.example.game)") },
                    isError = packageName.isNotEmpty() && !valid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                listOf(CacheKind.OBB, CacheKind.DATA).forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = kind == option, onClick = { kind = option })
                        Column {
                            Text(option.label)
                            Text(
                                "${option.remoteRoot}/${packageName.trim().ifEmpty { "<package>" }}/",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "Files: " + plan.files.take(4).joinToString(", ") { it.relativePath } + if (plan.files.size > 4) ", …" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onConfirm(packageName.trim(), kind) }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Skip") } },
    )
}
