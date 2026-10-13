package apkuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apkuploader.AppModel
import apkuploader.adb.ServerStatus
import kotlinx.coroutines.launch

@Composable
fun ConnectDialog(model: AppModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(model.settings.value.wifiAddress) }
    var pairingAddress by remember { mutableStateOf(model.settings.value.pairingAddress) }
    var pairingCode by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> String) {
        if (busy) return
        busy = true
        result = "Working…"
        scope.launch {
            result = try {
                block()
            } catch (e: Exception) {
                e.message ?: "Failed"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect the headset") },
        text = {
            Column(Modifier.widthIn(min = 420.dp, max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Devices (select the one to install to)", style = MaterialTheme.typography.titleSmall)
                if (model.devices.isEmpty()) {
                    Text(
                        "No devices. Connect the headset over USB and allow USB debugging on it, or connect over Wi-Fi below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                model.devices.forEach { device ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model.selected?.serial == device.serial, onClick = { model.select(device) })
                        Column {
                            Text("${device.displayName} · ${device.transportLabel}")
                            Text(
                                "${device.serial} · ${stateLabel(device)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                model.details?.let { Text(it.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                HorizontalDivider()
                Text("Wi-Fi", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Address (ip:port)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy && address.isNotBlank(), onClick = { run { model.connectWifi(address) } }) { Text("Connect") }
                }
                val usbDevice = model.devices.firstOrNull { it.isReady && !it.isWifi }
                OutlinedButton(
                    enabled = !busy && usbDevice != null,
                    onClick = { usbDevice?.let { device -> run { model.switchToWifi(device) } } },
                ) { Text("Switch the USB device to Wi-Fi (port 5555, until the headset reboots)") }

                Text("Pairing (wireless debugging)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Headset: Settings → Developer options → Wireless debugging → Pair device with pairing code. " +
                        "Enter the pairing address and code shown there, then connect to the address from the main Wireless debugging screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pairingAddress,
                        onValueChange = { pairingAddress = it },
                        label = { Text("Pairing address") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("Code") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !busy && pairingAddress.isNotBlank() && pairingCode.length == 6,
                        onClick = { run { model.pair(pairingAddress, pairingCode) } },
                    ) { Text("Pair") }
                }

                HorizontalDivider()
                val status = model.serverStatus
                Text(
                    when (status) {
                        is ServerStatus.Starting -> "adb server: starting…"
                        is ServerStatus.Running -> "adb server: running (protocol ${status.protocolVersion})" +
                            (status.binary?.let { "\n${it.path}" } ?: "")
                        is ServerStatus.Failed -> "adb server: ${status.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(enabled = !busy, onClick = { run { model.restartServer(); "adb server restarted" } }) { Text("Restart adb server") }
                if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
