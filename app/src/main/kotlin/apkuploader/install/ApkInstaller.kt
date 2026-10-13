package apkuploader.install

import apkuploader.adb.AdbClient
import apkuploader.adb.AdbConnection
import java.io.File

class InstalledPackage(val versionName: String?, val versionCode: Long?)

class InstallResult(val success: Boolean, val message: String) {
    /** The existing app must be removed before this APK can be installed. */
    val needsUninstall: Boolean
        get() = !success && UNINSTALL_REASONS.any { message.contains(it) }

    companion object {
        private val UNINSTALL_REASONS = listOf(
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            "INSTALL_FAILED_VERSION_DOWNGRADE",
            "INSTALL_FAILED_ALREADY_EXISTS",
            "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE",
        )

        fun parse(output: String): InstallResult {
            val text = output.trim()
            if (text.lines().any { it.trim().startsWith("Success") }) return InstallResult(true, "Success")
            val failure = Regex("Failure \\[(.*)]", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()
            return InstallResult(false, failure ?: text.ifEmpty { "The installer returned no result." })
        }
    }
}

class InstallFlags(val reinstall: Boolean = true, val allowDowngrade: Boolean = false, val grantPermissions: Boolean = false) {
    fun arguments(): List<String> = buildList {
        if (reinstall) add("-r")
        if (allowDowngrade) add("-d")
        if (grantPermissions) add("-g")
    }
}

/** Installs APKs the way `adb install` does: streamed into `cmd package install -S <size>`. */
class ApkInstaller(private val client: AdbClient) {

    fun installed(serial: String, packageName: String): InstalledPackage? {
        val path = client.shell(serial, "pm path ${AdbClient.quote(packageName)}")
        if (!path.stdout.contains("package:")) return null
        val dump = client.shell(serial, "dumpsys package ${AdbClient.quote(packageName)}").stdout
        return parseDumpsys(dump)
    }

    fun install(
        serial: String,
        apk: File,
        flags: InstallFlags,
        onConnection: (AdbConnection?) -> Unit = {},
        progress: (Long) -> Unit,
    ): InstallResult {
        val size = apk.length()
        val command = buildString {
            append("cmd package install")
            flags.arguments().forEach { append(' ').append(it) }
            append(" -S ").append(size)
        }
        client.exec(serial, command).use { connection ->
            onConnection(connection)
            try {
                connection.readTimeoutMs = INSTALL_TIMEOUT_MS
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                apk.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        connection.write(buffer, 0, read)
                        sent += read
                        progress(sent)
                    }
                }
                connection.flush()
                return InstallResult.parse(connection.readToEnd())
            } finally {
                onConnection(null)
            }
        }
    }

    fun uninstall(serial: String, packageName: String): InstallResult =
        InstallResult.parse(client.shell(serial, "pm uninstall ${AdbClient.quote(packageName)}", INSTALL_TIMEOUT_MS).output)

    companion object {
        const val INSTALL_TIMEOUT_MS = 15 * 60 * 1000

        fun parseDumpsys(dump: String): InstalledPackage? {
            if (dump.isBlank() || dump.contains("Unable to find package")) return null
            val versionCode = Regex("versionCode=(\\d+)").find(dump)?.groupValues?.get(1)?.toLongOrNull()
            val versionName = Regex("versionName=(\\S+)").find(dump)?.groupValues?.get(1)
            if (versionCode == null && versionName == null) return null
            return InstalledPackage(versionName, versionCode)
        }
    }
}
