package apkuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apkuploader.BuildInfo
import apkuploader.update.UpdateController
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(controller: UpdateController, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!controller.busy) onDismiss() },
        title = { Text("${BuildInfo.APP_NAME} updates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Installed: ${BuildInfo.VERSION}")
                Text(controller.status)
                if (controller.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                controller.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                if (controller.available != null) Text(controller.installer.installDescription)
                TextButton(onClick = { scope.launch { controller.check() } }, enabled = !controller.busy && !controller.checking) {
                    Text("Check for updates")
                }
            }
        },
        confirmButton = {
            if (controller.available != null) {
                Button(
                    enabled = !controller.busy && !controller.checking,
                    onClick = { scope.launch { controller.install() } },
                ) { Text(controller.installer.installLabel) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !controller.busy) { Text("Close") } },
    )
}
