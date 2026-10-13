package apkuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import apkuploader.AppDirs
import apkuploader.AppModel
import java.awt.Desktop

/** Recent log lines at the bottom of the window. */
@Composable
fun LogPanel(model: AppModel, onHide: () -> Unit) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val lines = model.logLines
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Log", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("Copy") }
                    TextButton(onClick = { runCatching { Desktop.getDesktop().open(AppDirs.logs) } }) { Text("Open log folder") }
                    TextButton(onClick = onHide) { Text("Hide") }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(170.dp).padding(16.dp, 0.dp, 16.dp, 8.dp)) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = when {
                            line.contains(" ERROR ") -> MaterialTheme.colorScheme.error
                            line.contains(" WARN ") -> WarningAmber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
