package apkuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Full-window drop zone shown while the queue is empty. */
@Composable
fun DropCard(onAddFiles: () -> Unit, onAddFolder: () -> Unit, modifier: Modifier = Modifier) {
    val strokeColor = LocalContentColor.current
    Card(
        modifier = modifier
            .padding(16.dp)
            .drawBehind {
                drawRoundRect(
                    color = strokeColor,
                    style = Stroke(
                        width = 4.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 16.dp.toPx()), 16.dp.toPx()),
                    ),
                    cornerRadius = CornerRadius(32.dp.toPx()),
                )
            }
            .clip(RoundedCornerShape(32.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.padding(16.dp).size(64.dp))
            Text("Install apps on Samsung Galaxy XR", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("Drop APK files or cache folders here", textAlign = TextAlign.Center)
            Text(
                "APKs are installed over ADB. OBB and data folders are copied to /sdcard/Android.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onAddFiles) {
                    Icon(Icons.Default.Android, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add APK files")
                }
                FilledTonalButton(onClick = onAddFolder) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add cache folder")
                }
            }
        }
    }
}

/** Compact drop hint shown above the queue. */
@Composable
fun DropStrip(onAddFiles: () -> Unit, onAddFolder: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Drop more APK files or cache folders anywhere in this window",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = onAddFiles) { Text("Add APK files") }
            TextButton(onClick = onAddFolder) { Text("Add cache folder") }
        }
    }
}
