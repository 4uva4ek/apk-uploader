package apkuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apkuploader.AppDirs
import apkuploader.AppLog
import apkuploader.AppModel

@Composable
fun SettingsDialog(model: AppModel, onDismiss: () -> Unit) {
    val settings = model.settings.value
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.widthIn(min = 420.dp, max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Installation", style = MaterialTheme.typography.titleSmall)
                SettingSwitch("Reinstall over the existing app, keeping its data (-r)", settings.reinstall) { v -> model.settings.update { copy(reinstall = v) } }
                SettingSwitch("Always allow downgrades (-d)", settings.allowDowngrade) { v -> model.settings.update { copy(allowDowngrade = v) } }
                SettingSwitch("Grant all runtime permissions on install (-g)", settings.grantPermissions) { v -> model.settings.update { copy(grantPermissions = v) } }
                Text("Cache", style = MaterialTheme.typography.titleSmall)
                SettingSwitch("Verify file sizes on the headset after copying", settings.verifyAfterPush) { v -> model.settings.update { copy(verifyAfterPush = v) } }
                SettingSwitch("Verify MD5 checksums instead (slower for large caches)", settings.verifyMd5) { v -> model.settings.update { copy(verifyMd5 = v) } }
                Text("Notifications", style = MaterialTheme.typography.titleSmall)
                SettingSwitch("Show system notifications", settings.notifications) { v -> model.settings.update { copy(notifications = v) } }
                Text("adb", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = settings.adbPath,
                    onValueChange = { v -> model.settings.update { copy(adbPath = v) } },
                    label = { Text("adb.exe path (empty = bundled adb)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Changing the adb path takes effect after \"Restart adb server\" in the Connect dialog.\n" +
                        "Settings and logs: ${AppDirs.root}" + (AppLog.path?.let { "\nCurrent log: ${it.name}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
