package apkuploader

import java.io.File

/** Per-user data: %LOCALAPPDATA% on Windows, ~/Library/Application Support on macOS, XDG data home on Linux. */
object AppDirs {
    val root: File = File(base(), BuildInfo.PACKAGE_NAME)
    val updates: File get() = File(root, "updates")
    val logs: File get() = File(root, "logs")
    val settingsFile: File get() = File(root, "settings.json")

    private fun base(): File {
        val home = System.getProperty("user.home")
        return when (Platform.os) {
            Os.WINDOWS -> File(System.getenv("LOCALAPPDATA") ?: home)
            Os.MAC -> File(home, "Library/Application Support")
            Os.LINUX -> System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") }?.let(::File) ?: File(home, ".local/share")
        }
    }
}
