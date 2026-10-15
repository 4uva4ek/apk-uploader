package apkuploader

import java.awt.Toolkit
import java.io.File

/**
 * Linux: registers a desktop entry and icon in the XDG data directory for the packaged launcher,
 * so the application menu and the taskbar show the app with its icon. Development runs
 * (no `jpackage.app-path`) are not registered.
 */
object LinuxDesktopEntry {
    private const val ID = "apk-uploader"

    private val dataDir: File
        get() = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") }?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")

    /**
     * Referenced by absolute path rather than by theme name: a running Plasma caches failed theme
     * lookups, so an icon installed on first launch would stay generic until the next login.
     */
    private val iconFile: File get() = File(dataDir, "icons/hicolor/512x512/apps/$ID.png")

    /**
     * Sets the X11 WM_CLASS that window managers match against StartupWMClass. Without it AWT
     * derives the class from the launching thread ("java-lang-Thread"). Must run before the
     * first window opens; needs `--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED`.
     */
    fun setWindowClass() {
        try {
            val toolkit = Toolkit.getDefaultToolkit()
            val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
            field.isAccessible = true
            field.set(toolkit, ID)
        } catch (e: Exception) {
            AppLog.warn("Cannot set the window class: ${e.message}")
        }
    }

    fun register() {
        try {
            val launcher = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile } ?: return
            val icon = checkNotNull(javaClass.getResourceAsStream("/app-icon.png")).use { it.readBytes() }
            var changed = write(iconFile, icon)
            val entry = """
                [Desktop Entry]
                Type=Application
                Name=${BuildInfo.APP_NAME}
                Comment=Install APK files and game caches on Samsung Galaxy XR over ADB
                Exec=${quote(launcher.absolutePath)} %F
                Icon=${iconFile.absolutePath}
                Terminal=false
                Categories=Utility;
                StartupWMClass=$ID
            """.trimIndent() + "\n"
            changed = write(File(dataDir, "applications/$ID.desktop"), entry.toByteArray()) || changed
            if (changed) AppLog.info("Desktop entry registered for ${launcher.path}")
        } catch (e: Exception) {
            AppLog.warn("Desktop entry was not registered: ${e.message}")
        }
    }

    /** Desktop notification through notify-send (libnotify); silently skipped when it is missing. */
    fun notify(title: String, message: String, success: Boolean) {
        try {
            Platform.external(ProcessBuilder(
                "notify-send", "--app-name=${BuildInfo.APP_NAME}", "--icon=${iconFile.absolutePath}",
                "--urgency=${if (success) "normal" else "critical"}", "--", title, message,
            )).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            AppLog.warn("Notification not shown: ${e.message}")
        }
    }

    private fun write(file: File, bytes: ByteArray): Boolean {
        if (file.isFile && file.readBytes().contentEquals(bytes)) return false
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        return true
    }

    /** Quotes a path for the Exec key (Desktop Entry spec). */
    internal fun quote(path: String): String = buildString {
        append('"')
        for (c in path) {
            when (c) {
                '"', '`', '$', '\\' -> append("\\\\").append(c)
                '%' -> append("%%")
                else -> append(c)
            }
        }
        append('"')
    }
}
