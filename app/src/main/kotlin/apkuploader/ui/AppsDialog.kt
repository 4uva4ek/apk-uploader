package apkuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apkuploader.AppModel
import apkuploader.adb.formatBytes
import apkuploader.install.InstalledApp

/** Third-party apps on the headset with Uninstall and Remove cache actions. */
@Composable
fun AppsDialog(model: AppModel, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var uninstallTarget by remember { mutableStateOf<InstalledApp?>(null) }
    var cacheTarget by remember { mutableStateOf<InstalledApp?>(null) }
    LaunchedEffect(model.selected?.serial) { if (model.apps == null) model.refreshApps() }

    val apps = model.apps.orEmpty().filter { app ->
        filter.isBlank() || app.packageName.contains(filter, true) || model.labels[app.packageName]?.contains(filter, true) == true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Installed apps")
                Spacer(Modifier.width(12.dp))
                model.apps?.let { Text("${it.size} on ${model.details?.model ?: model.selected?.displayName ?: "the headset"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        text = {
            Column(Modifier.widthIn(min = 600.dp, max = 820.dp).height(520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text("Filter by package or name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { model.refreshApps() }, enabled = !model.appsLoading && model.selected?.isReady == true) { Text("Refresh") }
                }
                if (model.appsLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    model.appsProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                model.appsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (model.selected?.isReady != true) {
                    Text("Connect the headset to list its apps.", style = MaterialTheme.typography.bodyMedium, color = WarningAmber)
                } else if (!model.appsLoading && model.apps != null && apps.isEmpty()) {
                    Text(if (filter.isBlank()) "No third-party apps installed." else "Nothing matches \"$filter\".", style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(app, model.labels[app.packageName], onUninstall = { uninstallTarget = app }, onRemoveCache = { cacheTarget = app })
                        HorizontalDivider()
                    }
                }
                Text(
                    "Third-party apps only; system apps are never listed. Names are known for apps installed through ${apkuploader.BuildInfo.APP_NAME}. " +
                        "Actions are queued and reported in the main window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    uninstallTarget?.let { app ->
        val label = model.labels[app.packageName] ?: app.packageName
        ConfirmDialog(
            title = "Uninstall $label?",
            text = "${app.packageName} ${app.version} will be removed from the headset together with its data and settings." +
                if (app.hasCache) " Its OBB/data folders under /sdcard/Android (${formatBytes((app.obbKb + app.dataKb) * 1024)}) are kept; use Remove cache for those." else "",
            confirmLabel = "Uninstall",
            onConfirm = {
                model.uninstallPackage(app)
                uninstallTarget = null
            },
            onDismiss = { uninstallTarget = null },
        )
    }

    cacheTarget?.let { app ->
        val label = model.labels[app.packageName] ?: app.packageName
        val folders = listOfNotNull(
            if (app.obbKb > 0) "${app.obbRoot} (${formatBytes(app.obbKb * 1024)})" else null,
            if (app.dataKb > 0) "${app.dataRoot} (${formatBytes(app.dataKb * 1024)})" else null,
        )
        ConfirmDialog(
            title = "Remove cache of $label?",
            text = "These folders will be deleted from the headset:\n${folders.joinToString("\n")}\nThe installed app is kept; it may re-create its data folder on the next start.",
            confirmLabel = "Remove cache",
            onConfirm = {
                model.removeCacheOf(app)
                cacheTarget = null
            },
            onDismiss = { cacheTarget = null },
        )
    }
}

@Composable
private fun AppRow(app: InstalledApp, label: String?, onUninstall: () -> Unit, onRemoveCache: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(0.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label ?: app.packageName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            if (label != null) Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                buildString {
                    append(app.version)
                    if (app.obbKb > 0) append(" · OBB ").append(formatBytes(app.obbKb * 1024))
                    if (app.dataKb > 0) append(" · data ").append(formatBytes(app.dataKb * 1024))
                    if (!app.hasCache) append(" · no cache on /sdcard")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        TextButton(onClick = onRemoveCache, enabled = app.hasCache) { Text("Remove cache") }
        TextButton(onClick = onUninstall) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
    }
}
