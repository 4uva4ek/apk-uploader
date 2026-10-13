package apkuploader.install

import apkuploader.adb.AdbClient
import apkuploader.adb.AdbConnection
import apkuploader.adb.AdbException
import apkuploader.apk.SplitBundle
import java.util.zip.ZipFile

/** Installs split packages the way `adb install-multiple` does: one package-manager session. */
class BundleInstaller(private val client: AdbClient) {

    fun deviceAbis(serial: String): List<String> =
        client.shell(serial, "getprop ro.product.cpu.abilist").stdout.trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun install(
        serial: String,
        bundle: SplitBundle,
        flags: InstallFlags,
        deviceAbis: List<String>,
        onConnection: (AdbConnection?) -> Unit = {},
        log: (String) -> Unit = {},
        progress: (Long) -> Unit,
    ): InstallResult {
        val selected = bundle.select(deviceAbis)
        val skipped = bundle.apks - selected.toSet()
        if (skipped.isNotEmpty()) log("Skipping splits for other ABIs: ${skipped.joinToString { it.fileName }}")
        val total = selected.sumOf { it.size }
        val createCommand = buildString {
            append("cmd package install-create")
            flags.arguments().forEach { append(' ').append(it) }
            append(" -S ").append(total)
        }
        val created = client.shell(serial, createCommand, ApkInstaller.INSTALL_TIMEOUT_MS).output
        val session = Regex("\\[(\\d+)]").find(created)?.groupValues?.get(1)
            ?: return InstallResult.parse(created).let { if (it.success) InstallResult(false, "No session id in: $created") else it }
        log("Install session $session: ${selected.size} APK(s), $total bytes")
        try {
            var done = 0L
            ZipFile(bundle.file).use { zip ->
                selected.forEachIndexed { index, apk ->
                    val entry = zip.getEntry(apk.entryName) ?: throw AdbException("${apk.entryName} vanished from ${bundle.file.name}")
                    val name = "${index}_" + apk.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    client.exec(serial, "cmd package install-write -S ${apk.size} $session $name -").use { connection ->
                        onConnection(connection)
                        try {
                            connection.readTimeoutMs = ApkInstaller.INSTALL_TIMEOUT_MS
                            val buffer = ByteArray(64 * 1024)
                            var sent = 0L
                            zip.getInputStream(entry).use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    connection.write(buffer, 0, read)
                                    sent += read
                                    progress(done + sent)
                                }
                            }
                            connection.flush()
                            val written = connection.readToEnd().trim()
                            if (!written.contains("Success")) throw AdbException("install-write ${apk.fileName}: ${written.ifEmpty { "no response" }}")
                        } finally {
                            onConnection(null)
                        }
                    }
                    done += apk.size
                }
            }
            return InstallResult.parse(client.shell(serial, "cmd package install-commit $session", ApkInstaller.INSTALL_TIMEOUT_MS).output)
        } catch (e: Exception) {
            runCatching { client.shell(serial, "cmd package install-abandon $session") }
            throw e
        }
    }
}
