package apkuploader.update

import apkuploader.AppDirs
import apkuploader.AppLog
import apkuploader.BuildInfo
import apkuploader.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * Linux and macOS: the verified package is downloaded and its folder opened; the user replaces
 * the application. In-place replacement stays Windows-only ([WindowsAppUpdater]).
 */
class DownloadAppUpdater : AppUpdater {
    override val platform = Platform.releaseAsset
    override val downloadDirectory: File = AppDirs.updates
    override val installLabel = "Download"
    override val installDescription =
        "The verified package is downloaded and its folder opened. Quit ${BuildInfo.APP_NAME} and replace it with the new version. Settings are kept."

    override suspend fun install(file: File, update: AppUpdate): String = withContext(Dispatchers.IO) {
        try {
            Desktop.getDesktop().open(file.parentFile)
        } catch (e: Exception) {
            AppLog.warn("Cannot open ${file.parentFile.path}: ${e.message}")
        }
        "Version ${update.version} downloaded to ${file.path}."
    }
}
