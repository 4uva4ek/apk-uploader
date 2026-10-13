package apkuploader.adb

import java.io.ByteArrayOutputStream
import java.io.IOException

class ShellResult(val stdout: String, val stderr: String, val exitCode: Int) {
    val ok: Boolean get() = exitCode == 0
    val output: String get() = (stdout + stderr).trim()
}

/**
 * Client for the adb server protocol. Every call opens its own connection, so the client
 * can be shared between threads. Device services (shell/exec/sync) go through
 * `host:transport:<serial>`.
 */
class AdbClient(val host: String = "127.0.0.1", val port: Int = DEFAULT_PORT) {

    private fun open(): AdbConnection = AdbConnection(host, port)

    fun isServerRunning(): Boolean = try {
        version(); true
    } catch (_: IOException) {
        false
    }

    fun version(): Int = open().use { connection ->
        connection.request("host:version")
        connection.readLengthPrefixed().trim().toInt(16)
    }

    fun devices(): List<DeviceInfo> = open().use { connection ->
        connection.request("host:devices-l")
        DeviceInfo.parseList(connection.readLengthPrefixed())
    }

    /** Streams device-list updates until the server closes the connection. Blocks the caller. */
    fun trackDevices(onUpdate: (List<DeviceInfo>) -> Unit) {
        open().use { connection ->
            connection.readTimeoutMs = 0
            connection.request("host:track-devices-l")
            while (true) onUpdate(DeviceInfo.parseList(connection.readLengthPrefixed()))
        }
    }

    fun connect(address: String): String = hostCommand("host:connect:$address")

    fun disconnect(address: String): String = hostCommand("host:disconnect:$address")

    fun pair(address: String, code: String): String = hostCommand("host:pair:$code:$address")

    fun kill() {
        try {
            open().use { it.request("host:kill") }
        } catch (_: IOException) {
        }
    }

    private fun hostCommand(service: String): String = open().use { connection ->
        connection.readTimeoutMs = 60_000
        connection.request(service)
        connection.readLengthPrefixed().trim()
    }

    /** Opens a connection bound to the device; the caller sends the device service request. */
    fun transport(serial: String): AdbConnection {
        val connection = open()
        try {
            connection.request("host:transport:$serial")
            return connection
        } catch (e: Throwable) {
            connection.close()
            throw e
        }
    }

    /** Runs a shell command with the v2 shell protocol (separate stderr and an exit code). */
    fun shell(serial: String, command: String, timeoutMs: Int = 60_000): ShellResult {
        val connection = transport(serial)
        connection.readTimeoutMs = timeoutMs
        try {
            connection.request("shell,v2,raw:$command")
        } catch (e: AdbException) {
            connection.close()
            if (!e.message.orEmpty().contains("shell", ignoreCase = true)) throw e
            return legacyShell(serial, command, timeoutMs)
        }
        connection.use {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            var exitCode = -1
            while (true) {
                val header = try {
                    it.readExact(5)
                } catch (_: AdbException) {
                    break
                }
                val length = (header[1].toInt() and 0xff) or ((header[2].toInt() and 0xff) shl 8) or
                    ((header[3].toInt() and 0xff) shl 16) or ((header[4].toInt() and 0xff) shl 24)
                val payload = it.readExact(length)
                when (header[0].toInt()) {
                    1 -> stdout.write(payload)
                    2 -> stderr.write(payload)
                    3 -> {
                        exitCode = payload.firstOrNull()?.toInt()?.and(0xff) ?: -1
                        break
                    }
                }
            }
            return ShellResult(stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8), exitCode)
        }
    }

    private fun legacyShell(serial: String, command: String, timeoutMs: Int): ShellResult = transport(serial).use {
        it.readTimeoutMs = timeoutMs
        it.request("shell:$command")
        ShellResult(it.readToEnd(), "", -1)
    }

    /** Raw stdin/stdout stream of a device command; the caller writes input and reads until EOF. */
    fun exec(serial: String, command: String): AdbConnection {
        val connection = transport(serial)
        try {
            connection.request("exec:$command")
            return connection
        } catch (e: Throwable) {
            connection.close()
            throw e
        }
    }

    fun sync(serial: String): SyncSession {
        val connection = transport(serial)
        try {
            connection.request("sync:")
            return SyncSession(connection)
        } catch (e: Throwable) {
            connection.close()
            throw e
        }
    }

    fun tcpip(serial: String, port: Int): String = transport(serial).use {
        it.request("tcpip:$port")
        it.readToEnd().trim()
    }

    companion object {
        const val DEFAULT_PORT = 5037

        /** Quotes one argument for /system/bin/sh. */
        fun quote(argument: String): String = "'" + argument.replace("'", "'\\''") + "'"

        fun command(vararg arguments: String): String = arguments.joinToString(" ") { quote(it) }
    }
}
