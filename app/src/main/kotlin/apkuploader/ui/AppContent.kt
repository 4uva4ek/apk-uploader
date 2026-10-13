package apkuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import apkuploader.AppModel
import apkuploader.BuildInfo
import apkuploader.install.ItemState
import apkuploader.install.QueueItem
import apkuploader.update.AppUpdater
import apkuploader.update.UpdateController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(model: AppModel, updater: AppUpdater, pickFiles: () -> Unit, pickFolder: () -> Unit) {
    AppTheme {
        val snackbar = remember { SnackbarHostState() }
        val updates = remember(updater) { UpdateController(updater) }
        var showUpdates by remember { mutableStateOf(false) }
        var showConnect by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showApps by remember { mutableStateOf(false) }
        var uninstallTarget by remember { mutableStateOf<QueueItem.Installable?>(null) }
        var uninstallAndRetryTarget by remember { mutableStateOf<QueueItem.Installable?>(null) }
        var removeCacheTarget by remember { mutableStateOf<QueueItem?>(null) }
        val showLog = model.settings.value.showLog

        LaunchedEffect(updates) { updates.check() }
        LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(BuildInfo.APP_NAME, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(12.dp))
                            Text(BuildInfo.VERSION, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        DeviceChip(model) { showConnect = true }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { showApps = true }, enabled = model.selected?.isReady == true) {
                            Icon(Icons.Default.Apps, contentDescription = "Installed apps")
                        }
                        IconButton(onClick = { model.settings.update { copy(showLog = !showLog) } }) {
                            Icon(Icons.Default.Description, contentDescription = "Log", tint = if (showLog) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                        FilledTonalButton(
                            modifier = Modifier.padding(8.dp, 0.dp),
                            enabled = !model.queue.isBusy,
                            onClick = { showUpdates = true },
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (updates.available != null) "Update available" else "Updates")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (model.queue.items.isEmpty()) {
                    DropCard(onAddFiles = pickFiles, onAddFolder = pickFolder, modifier = Modifier.weight(1f).fillMaxWidth())
                } else {
                    DropStrip(onAddFiles = pickFiles, onAddFolder = pickFolder)
                    QueueList(
                        model = model,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onUninstallAndRetry = { uninstallAndRetryTarget = it },
                        onUninstall = { uninstallTarget = it },
                        onRemoveCache = { removeCacheTarget = it },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val pending = model.queue.items.count { it.state is ItemState.Queued || it.state is ItemState.WaitingForDevice }
                        val done = model.queue.items.count { it.state is ItemState.Done }
                        val failed = model.queue.items.count { it.state is ItemState.Failed }
                        Text(
                            "$pending pending · $done done · $failed failed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { model.queue.clearFinished() }, enabled = model.queue.items.any { it.state.isFinished }) {
                            Text("Clear finished")
                        }
                    }
                }
                if (showLog) LogPanel(model) { model.settings.update { copy(showLog = false) } }
            }
        }

        if (showUpdates) UpdateDialog(updates) { showUpdates = false }
        if (showConnect) ConnectDialog(model) { showConnect = false }
        if (showSettings) SettingsDialog(model) { showSettings = false }
        if (showApps) AppsDialog(model) { showApps = false }

        model.inputRequests.firstOrNull()?.let { item ->
            CacheTargetDialog(
                item = item,
                onConfirm = { packageName, kind ->
                    item.resolve(packageName, kind)
                    model.inputRequests.remove(item)
                    model.queue.ensureWorker()
                },
                onCancel = {
                    item.state = ItemState.Cancelled
                    model.inputRequests.remove(item)
                },
            )
        }

        uninstallAndRetryTarget?.let { item ->
            ConfirmDialog(
                title = "Uninstall ${item.title}?",
                text = "${item.packageName} will be removed from the headset together with its data and settings, " +
                    "then ${item.fileName} will be installed. OBB and data folders under /sdcard/Android are not deleted.",
                confirmLabel = "Uninstall and install",
                onConfirm = {
                    model.queue.uninstallAndRetry(item)
                    uninstallAndRetryTarget = null
                },
                onDismiss = { uninstallAndRetryTarget = null },
            )
        }

        uninstallTarget?.let { item ->
            ConfirmDialog(
                title = "Uninstall ${item.title}?",
                text = "${item.packageName} will be removed from the headset together with its data and settings. " +
                    "OBB and data folders under /sdcard/Android are kept; use \"Remove cache\" for those.",
                confirmLabel = "Uninstall",
                onConfirm = {
                    model.uninstall(item)
                    uninstallTarget = null
                },
                onDismiss = { uninstallTarget = null },
            )
        }

        removeCacheTarget?.let { item ->
            val description = when (item) {
                is QueueItem.Cache -> item.plan.let { plan ->
                    if (plan.kind.name == "OBB" || plan.kind.name == "DATA") "The folder ${plan.remoteRoot(plan.packageName ?: "")} will be deleted from the headset."
                    else "${plan.files.size} file(s) copied from ${item.title} will be deleted from the headset."
                }
                is QueueItem.Bundle -> "${item.bundle.obbs.size} OBB file(s) of ${item.packageName} will be deleted from the headset."
                else -> ""
            }
            ConfirmDialog(
                title = "Remove cache of ${item.title}?",
                text = "$description The installed app is kept.",
                confirmLabel = "Remove cache",
                onConfirm = {
                    model.removeCache(item)
                    removeCacheTarget = null
                },
                onDismiss = { removeCacheTarget = null },
            )
        }

        model.errorMessage?.let { (title, message) ->
            ErrorDialog(title, message) { model.errorMessage = null }
        }
    }
}
