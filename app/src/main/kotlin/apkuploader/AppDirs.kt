package apkuploader

import java.io.File

object AppDirs {
    val root: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), BuildInfo.PACKAGE_NAME)
    val updates: File get() = File(root, "updates")
    val logs: File get() = File(root, "logs")
    val settingsFile: File get() = File(root, "settings.json")
}
