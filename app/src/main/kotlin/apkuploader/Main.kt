package apkuploader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import apkuploader.ui.AppContent
import apkuploader.update.WindowsAppUpdater
import com.formdev.flatlaf.util.SystemFileChooser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.DataFlavor
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val model = AppModel(scope)
    model.start()

    application {
        val iconPainter = remember { BitmapPainter(useResource("app-icon.png") { loadImageBitmap(it) }) }
        val trayState = rememberTrayState()
        model.notifier = { title, message, success ->
            trayState.sendNotification(Notification(title, message, if (success) Notification.Type.Info else Notification.Type.Warning))
        }

        fun quit() {
            model.shutdown()
            exitApplication()
        }

        Tray(
            icon = iconPainter,
            state = trayState,
            tooltip = BuildInfo.APP_NAME,
            menu = { Item("Quit ${BuildInfo.APP_NAME}", onClick = ::quit) },
        )

        Window(
            onCloseRequest = ::quit,
            title = BuildInfo.APP_NAME,
            icon = iconPainter,
            state = rememberWindowState(width = 980.dp, height = 700.dp),
        ) {
            val dropTarget = remember {
                object : DragAndDropTarget {
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        val files = event.awtTransferable
                            .takeIf { it.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }
                            ?.getTransferData(DataFlavor.javaFileListFlavor)
                            ?.let { it as? List<*> }
                            ?.filterIsInstance<File>()
                            .orEmpty()
                        if (files.isEmpty()) return false
                        model.addPaths(files)
                        return true
                    }
                }
            }
            var lastDirectory by remember { mutableStateOf(System.getProperty("user.home")) }

            fun pick(title: String, directories: Boolean) {
                val chooser = SystemFileChooser(title).apply {
                    fileSelectionMode = if (directories) SystemFileChooser.DIRECTORIES_ONLY else SystemFileChooser.FILES_ONLY
                    isMultiSelectionEnabled = true
                    currentDirectory = File(lastDirectory)
                }
                if (chooser.showOpenDialog(null) != SystemFileChooser.APPROVE_OPTION) return
                val chosen = chooser.selectedFiles?.toList().orEmpty().ifEmpty { listOfNotNull(chooser.selectedFile) }
                if (chosen.isEmpty()) return
                lastDirectory = (chosen.first().parentFile ?: chosen.first()).absolutePath
                model.addPaths(chosen)
            }

            LaunchedEffect(args) {
                if (args.isNotEmpty()) model.addPaths(args.map(::File))
            }

            Box(
                modifier = Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget),
            ) {
                AppContent(
                    model = model,
                    updater = remember {
                        WindowsAppUpdater(beforeExit = { runBlocking { model.shutdown() } }, exit = ::exitApplication)
                    },
                    pickFiles = { pick("Add APK files", directories = false) },
                    pickFolder = { pick("Add cache folder", directories = true) },
                )
            }
        }
    }
}
