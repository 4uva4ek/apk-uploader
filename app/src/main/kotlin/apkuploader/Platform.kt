package apkuploader

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
}
