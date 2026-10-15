package apkuploader

import java.awt.Desktop
import java.io.File

enum class Os { WINDOWS, MAC, LINUX }

/** Host operating system and the names that depend on it. */
object Platform {
    val os: Os = System.getProperty("os.name").orEmpty().lowercase().let {
        when {
            it.startsWith("windows") -> Os.WINDOWS
            it.startsWith("mac") -> Os.MAC
            else -> Os.LINUX
        }
    }

    /** Compose resource directory name; also where `fetchPlatformTools` stages adb in development. */
    val resourceDir: String = when (os) {
        Os.WINDOWS -> "windows"
        Os.MAC -> "macos"
        Os.LINUX -> "linux"
    }

    val adbName: String = if (os == Os.WINDOWS) "adb.exe" else "adb"

    /** Suffix of this platform's release asset: `apk-uploader-<version>-<suffix>`. */
    val releaseAsset: String = when (os) {
        Os.WINDOWS -> "windows-x64.zip"
        Os.MAC -> "macos-arm64.zip"
        Os.LINUX -> "linux-x64.tar.gz"
    }

    /**
     * Removes what the Linux jpackage launcher adds to our environment before it re-executes
     * itself: `_JPACKAGE_LAUNCHER` and its `lib/app` directory in LD_LIBRARY_PATH. A program that
     * inherits them (a file manager opened on the update folder) would start the next ApkUploader
     * as a bare JVM without its main class.
     */
    fun external(builder: ProcessBuilder): ProcessBuilder {
        if (os != Os.LINUX) return builder
        val env = builder.environment()
        env.remove("_JPACKAGE_LAUNCHER")
        val appLib = System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile?.parentFile }
            ?.let { File(it, "lib/app").path }
        val paths = env["LD_LIBRARY_PATH"]?.split(':')?.filter { it.isNotEmpty() && it != appLib }
        if (paths.isNullOrEmpty()) env.remove("LD_LIBRARY_PATH") else env["LD_LIBRARY_PATH"] = paths.joinToString(":")
        return builder
    }

    /** Opens [dir] in the file manager; on Linux through xdg-open with a clean environment. */
    fun openFolder(dir: File) {
        if (os == Os.LINUX) {
            try {
                external(ProcessBuilder("xdg-open", dir.absolutePath))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                return
            } catch (e: Exception) {
                AppLog.warn("xdg-open failed: ${e.message}")
            }
        }
        Desktop.getDesktop().open(dir)
    }
}
