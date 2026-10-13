package apkuploader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import apkuploader.AppModel
import apkuploader.adb.DeviceInfo
import apkuploader.adb.ServerStatus

/** Connection status in the top bar: click to pick one of the adb devices or open the Connect dialog. */
@Composable
fun DeviceChip(model: AppModel, onConnect: () -> Unit) {
    val device = model.selected
    val status = model.serverStatus
    var expanded by remember { mutableStateOf(false) }
    val (label, icon) = when {
        status is ServerStatus.Failed -> "adb unavailable" to Icons.Default.Warning
        device == null && status is ServerStatus.Starting -> "Starting adb…" to Icons.Default.LinkOff
        device == null -> "No headset" to Icons.Default.LinkOff
        device.isUnauthorized -> "Allow debugging on the headset" to Icons.Default.Warning
        !device.isReady -> "${device.displayName} · ${device.state}" to Icons.Default.Warning
        device.isWifi -> "${model.details?.model ?: device.displayName} · Wi-Fi" to Icons.Default.Wifi
        else -> "${model.details?.model ?: device.displayName} · USB" to Icons.Default.Usb
    }
    val others = model.devices.size - 1
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(if (others > 0) "$label  (+$others)" else label) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose device") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (model.devices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No adb devices. Connect the headset over USB or Wi-Fi.") },
                    onClick = { expanded = false; onConnect() },
                )
            }
            model.devices.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${entry.displayName} · ${entry.transportLabel}")
                            Text(
                                "${entry.serial} · ${stateLabel(entry)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { Icon(if (entry.isWifi) Icons.Default.Wifi else Icons.Default.Usb, contentDescription = null) },
                    trailingIcon = {
                        if (model.selected?.serial == entry.serial) Icon(Icons.Default.Check, contentDescription = "Selected")
                    },
                    onClick = {
                        model.select(entry)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Connect over Wi-Fi, pair, adb server…") },
                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                onClick = {
                    expanded = false
                    onConnect()
                },
            )
        }
    }
}

fun stateLabel(device: DeviceInfo): String = when {
    device.isReady -> "ready"
    device.isUnauthorized -> "allow USB debugging on the headset"
    else -> device.state
}
