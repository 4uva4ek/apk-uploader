package apkuploader.update

import apkuploader.AppDirs
import apkuploader.BuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Replaces the portable installation in place: the package is extracted next to the app,
 * a PowerShell helper waits for this process to exit, keeps the previous version as a
 * backup, swaps the directories and restarts the app. [beforeExit] stops the adb server
 * so no adb.exe keeps the application directory open.
 */
class WindowsAppUpdater(private val beforeExit: suspend () -> Unit, private val exit: () -> Unit) : AppUpdater {
    override val platform = "windows-x64.zip"
    override val downloadDirectory: File = AppDirs.updates
    override val installLabel = "Update and restart"
    override val installDescription =
        "${BuildInfo.APP_NAME} will close and restart. A backup of the previous version is kept beside the app. Settings are kept."

    override suspend fun install(file: File, update: AppUpdate): String {
        withContext(Dispatchers.IO) {
            require(System.getProperty("os.name").startsWith("Windows")) { "Automatic installation is available on Windows only." }
            val launcher = System.getProperty("jpackage.app-path")?.let(::File)?.canonicalFile
                ?: error("Run the packaged ${BuildInfo.LAUNCHER} to install updates.")
            val target = launcher.parentFile
            require(
                launcher.name == BuildInfo.LAUNCHER && launcher.isFile && File(target, "app").isDirectory &&
                    File(target, "runtime/bin/server/jvm.dll").isFile,
            ) { "Cannot identify the portable installation." }
            val stage = Files.createTempDirectory(target.parentFile.toPath(), ".${BuildInfo.PACKAGE_NAME}-update-").toFile()
            extract(file, stage)
            val script = File(stage, "update.ps1")
            script.writeBytes(checkNotNull(javaClass.getResourceAsStream("/update-windows.ps1")).use { it.readBytes() })
            val ready = File(stage, "ready")
            val log = File(downloadDirectory, "last-install.txt")
            fun quote(value: String) = "'" + value.replace("'", "''") + "'"
            val command = "& ${quote(script.path)} -Target ${quote(target.path)} -Stage ${quote(stage.path)} " +
                "-ParentProcessId ${ProcessHandle.current().pid()} -Log ${quote(log.path)}"
            val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))
            val powershell = File(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe")
            beforeExit()
            val process = ProcessBuilder(
                powershell.path, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
            )
                .directory(stage) // Do not hold the application's directory open while replacing it.
                .redirectErrorStream(true).redirectOutput(File(stage, "helper.log")).start()
            var attempts = 0
            while (!ready.isFile && process.isAlive && attempts++ < 100) delay(100)
            check(ready.isFile && process.isAlive) { "Could not start the update helper. Current app is unchanged. See ${stage.path}." }
        }
        exit()
        return "Restarting…"
    }

    companion object {
        private const val ROOT = BuildInfo.PACKAGE_NAME

        internal fun extract(zip: File, directory: File) {
            val root = directory.canonicalFile.toPath()
            var total = 0L
            var count = 0
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    require(++count <= 30000) { "Too many update files." }
                    val name = entry.name.replace('\\', '/')
                    require(
                        name.startsWith("$ROOT/") && !name.contains(':') &&
                            name.split('/').none { it == ".." || it.endsWith('.') || it.endsWith(' ') },
                    ) { "Invalid update archive path." }
                    val out = File(directory, name).canonicalFile
                    require(out.toPath().startsWith(root) && out.toPath() != root) { "Update archive escapes its directory." }
                    if (name.endsWith('/')) {
                        check(out.mkdirs() || out.isDirectory)
                    } else {
                        check(out.parentFile.mkdirs() || out.parentFile.isDirectory)
                        check(out.createNewFile()) { "Duplicate file in update archive." }
                        out.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= 2L * 1024 * 1024 * 1024) { "Update archive is too large." }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            val app = File(directory, ROOT)
            require(
                File(app, BuildInfo.LAUNCHER).isFile && File(app, "runtime/bin/server/jvm.dll").isFile &&
                    File(app, "app/$ROOT.cfg").isFile,
            ) { "Incomplete Windows update." }
        }
    }
}
