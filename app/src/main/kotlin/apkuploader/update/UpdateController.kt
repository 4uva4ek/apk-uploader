package apkuploader.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import apkuploader.AppLog
import apkuploader.BuildInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateController(val installer: AppUpdater) {
    var available by mutableStateOf<AppUpdate?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var checking by mutableStateOf(false)
        private set
    var status by mutableStateOf("Updates are checked automatically.")
        private set
    var progress by mutableStateOf<Float?>(null)
        private set

    suspend fun check() {
        if (checking || busy) return
        checking = true
        try {
            available = withContext(Dispatchers.IO) { UpdateClient.latest(BuildInfo.VERSION, installer.platform) }
            status = available?.let { "Version ${it.version} is available." } ?: "You have the latest version."
            AppLog.info("Update check: $status")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = e.message ?: "Could not check for updates."
            AppLog.warn("Update check failed: $status")
        } finally {
            checking = false
        }
    }

    suspend fun install() {
        val update = available ?: return
        if (busy || checking) return
        busy = true
        progress = 0f
        status = "Downloading ${update.version}…"
        try {
            val file = withContext(Dispatchers.IO) {
                UpdateClient.download(update, installer.downloadDirectory) { progress = it }
            }
            progress = null
            status = "Preparing installation…"
            AppLog.info("Update ${update.version} downloaded to ${file.path}; installing")
            status = installer.install(file, update)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = e.message ?: "Update failed. Your current installation is unchanged."
            AppLog.error("Update failed: $status")
        } finally {
            progress = null
            busy = false
        }
    }
}
