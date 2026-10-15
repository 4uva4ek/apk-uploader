package apkuploader.adb

import apkuploader.AppDirs
import apkuploader.AppLog
import apkuploader.Os
import apkuploader.Platform
import java.io.File
import java.util.concurrent.TimeUnit

/** Locates the adb binary and starts the adb server process. */
object AdbServer {

    /** Bundled platform-tools: `adb` in the packaged app's resources, `build/platform-tools/<os>` in development. */
    fun bundledBinary(): File? {
        val name = Platform.adbName
        val candidates = mutableListOf<File>()
        System.getProperty("compose.application.resources.dir")?.let { candidates += File(it, name) }
        candidates += File("app/build/platform-tools/${Platform.resourceDir}/$name")
        candidates += File("build/platform-tools/${Platform.resourceDir}/$name")
        return candidates.firstOrNull { it.isFile }?.absoluteFile?.also(::ensureExecutable)
    }

    fun fromPath(): File? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it.trim(), Platform.adbName) }.firstOrNull { it.isFile && it.canExecute() }

    /** Archive extraction can drop the executable bit on Linux and macOS. */
    private fun ensureExecutable(file: File) {
        if (Platform.os != Os.WINDOWS && !file.canExecute() && !file.setExecutable(true)) {
            AppLog.warn("${file.path} is not executable; run chmod +x on it or set an adb path in Settings")
        }
    }

    fun locate(override: String): File {
        if (override.isNotBlank()) {
            val file = File(override)
            require(file.isFile) { "adb was not found at $override. Fix the path in Settings or clear it to use the bundled adb." }
            return file
        }
        return bundledBinary() ?: fromPath()
            ?: error("The bundled adb is missing. Reinstall ${apkuploader.BuildInfo.APP_NAME} or set an adb path in Settings.")
    }

    /** Runs `adb start-server`; the server keeps running as a separate process. */
    fun start(binary: File) {
        AppDirs.logs.mkdirs()
        val log = File(AppDirs.logs, "adb-server.log")
        val process = ProcessBuilder(binary.path, "start-server")
            .directory(binary.parentFile)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("adb start-server did not finish in 60 seconds. See ${log.path}.")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("adb start-server failed (exit ${process.exitValue()}). See ${log.path}.")
        }
        AppLog.info("adb server started from ${binary.path}")
    }

    fun binaryVersion(binary: File): String? = try {
        val process = ProcessBuilder(binary.path, "version").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS)
        Regex("Version ([^\\s]+)").find(text)?.groupValues?.get(1)
    } catch (_: Exception) {
        null
    }
}
