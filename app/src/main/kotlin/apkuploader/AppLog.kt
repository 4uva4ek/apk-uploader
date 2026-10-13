package apkuploader

import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Session log: stderr plus %LOCALAPPDATA%\ApkUploader\logs\session-*.log. */
object AppLog {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val file: File? by lazy {
        try {
            AppDirs.logs.mkdirs()
            pruneOldLogs(AppDirs.logs, keep = 10)
            File(AppDirs.logs, "session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log")
        } catch (_: Exception) {
            null
        }
    }

    val path: File? get() = file

    fun info(message: String) = write("INFO ", message)

    fun warn(message: String) = write("WARN ", message)

    fun error(message: String, error: Throwable? = null) {
        write("ERROR", if (error != null && error.message != null && !message.contains(error.message!!)) "$message: ${error.message}" else message)
        if (error != null && error !is java.io.IOException) write("ERROR", error.stackTraceToString())
    }

    @Synchronized
    private fun write(level: String, message: String) {
        val line = "${LocalTime.now().format(timeFormat)} $level $message"
        System.err.println(line)
        try {
            file?.appendText(line + System.lineSeparator())
        } catch (_: Exception) {
        }
    }

    private fun pruneOldLogs(directory: File, keep: Int) {
        directory.listFiles { f -> f.isFile && f.name.startsWith("session-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name }?.drop(keep - 1)?.forEach { it.delete() }
    }
}
