package apkuploader.adb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** The adb server reported FAIL, or the stream ended unexpectedly. */
class AdbException(message: String) : IOException(message)

/**
 * One TCP connection to the adb server (host:port, usually 127.0.0.1:5037) speaking the
 * server protocol: requests are "%04x" hex length + payload; replies are OKAY or FAIL + message.
 */
class AdbConnection(host: String, port: Int, connectTimeoutMs: Int = 5000) : Closeable {
    private val socket = Socket()

    init {
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.tcpNoDelay = true
        socket.soTimeout = 30_000
    }

    private val input = BufferedInputStream(socket.getInputStream(), 1 shl 16)
    private val output = BufferedOutputStream(socket.getOutputStream(), 1 shl 16)

    /** Socket read timeout in milliseconds; 0 waits forever. */
    var readTimeoutMs: Int
        get() = socket.soTimeout
        set(value) {
            socket.soTimeout = value
        }

    /** Sends a service request and waits for OKAY. Throws [AdbException] on FAIL. */
    fun request(service: String) {
        val bytes = service.toByteArray(Charsets.UTF_8)
        output.write("%04x".format(bytes.size).toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
        expectOkay()
    }

    fun expectOkay() {
        when (val status = String(readExact(4), Charsets.US_ASCII)) {
            "OKAY" -> return
            "FAIL" -> throw AdbException(readLengthPrefixed())
            else -> throw AdbException("Unexpected adb response: $status")
        }
    }

    fun readLengthPrefixed(): String {
        val length = String(readExact(4), Charsets.US_ASCII).toInt(16)
        return String(readExact(length), Charsets.UTF_8)
    }

    fun readExact(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw AdbException("adb connection closed")
            offset += read
        }
        return buffer
    }

    fun readToEnd(): String {
        val out = ByteArrayOutputStream()
        input.copyTo(out)
        return out.toString(Charsets.UTF_8)
    }

    fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) = output.write(buffer, offset, length)

    fun flush() = output.flush()

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
