package apkuploader.adb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Minimal adb server emulation: host services, shell v2, exec install (single and session) and sync on a local directory. */
class FakeAdbServer(private val root: File) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val requests = CopyOnWriteArrayList<String>()
    val shellHandlers = mutableMapOf<String, Pair<String, Int>>()

    /** Consulted for shell commands without an exact handler; return null for "command not found". */
    var shellFallback: ((String) -> Pair<String, Int>?)? = null
    var devicesText = "EMU123\tdevice product:fake model:Fake_Device device:fake transport_id:1\n"
    val installedBytes = AtomicLong()
    var installCommand: String? = null
    val sessionWrites = CopyOnWriteArrayList<Pair<String, Long>>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                thread(isDaemon = true) { socket.use { handle(it) } }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        fun okay() {
            output.write("OKAY".toByteArray()); output.flush()
        }
        fun fail(message: String) {
            output.write("FAIL".toByteArray()); writeLengthPrefixed(output, message)
        }
        try {
            var request = readRequest(input)
            requests += request
            when {
                request == "host:version" -> { okay(); writeLengthPrefixed(output, "0029") }
                request == "host:devices-l" -> { okay(); writeLengthPrefixed(output, devicesText) }
                request == "host:track-devices-l" -> {
                    okay(); writeLengthPrefixed(output, devicesText)
                    while (input.read() >= 0) { /* keep open */ }
                }
                request.startsWith("host:connect:") -> { okay(); writeLengthPrefixed(output, "connected to ${request.removePrefix("host:connect:")}") }
                request == "host:kill" -> { okay(); server.close() }
                request.startsWith("host:transport:") -> {
                    okay()
                    request = readRequest(input)
                    requests += request
                    deviceService(request, input, output, ::okay, ::fail)
                }
                else -> fail("unknown host service")
            }
        } catch (_: IOException) {
        }
    }

    private fun deviceService(request: String, input: InputStream, output: OutputStream, okay: () -> Unit, fail: (String) -> Unit) {
        when {
            request.startsWith("shell,v2,raw:") -> {
                val command = request.removePrefix("shell,v2,raw:")
                val (stdout, exit) = shellHandlers[command] ?: shellFallback?.invoke(command)
                    ?: ("sh: ${command.substringBefore(' ')}: not found\n" to 127)
                okay()
                writeFrame(output, 1, stdout.toByteArray())
                writeFrame(output, 3, byteArrayOf(exit.toByte()))
                output.flush()
            }
            request.startsWith("exec:") -> {
                val command = request.removePrefix("exec:")
                val size = Regex("-S (\\d+)").find(command)!!.groupValues[1].toLong()
                okay()
                var count = 0L
                val buffer = ByteArray(65536)
                while (count < size) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), size - count).toInt())
                    if (read < 0) break
                    count += read
                }
                if (command.contains("install-write")) {
                    sessionWrites += command.substringAfter("$size ").split(' ')[1] to count
                    output.write("Success: Streamed $count bytes\n".toByteArray())
                } else {
                    installCommand = command
                    installedBytes.set(count)
                    output.write((if (count == size) "Success\n" else "Failure [INSTALL_FAILED_INVALID_APK: short read]\n").toByteArray())
                }
                output.flush()
            }
            request == "sync:" -> {
                okay()
                sync(input, output)
            }
            else -> fail("unknown device service")
        }
    }

    private fun sync(input: InputStream, output: OutputStream) {
        while (true) {
            val header = ByteBuffer.wrap(readExact(input, 8)).order(ByteOrder.LITTLE_ENDIAN)
            val id = String(ByteArray(4).also { header.get(it) }, Charsets.US_ASCII)
            val length = header.int
            when (id) {
                "QUIT" -> return
                "STA2" -> {
                    val path = String(readExact(input, length), Charsets.UTF_8)
                    val file = localFile(path)
                    val body = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
                    body.put("STA2".toByteArray())
                    if (!file.exists()) {
                        body.putInt(2) // ENOENT
                        body.put(ByteArray(64))
                    } else {
                        body.putInt(0).putLong(0).putLong(0)
                        body.putInt(if (file.isDirectory) 0x41ED else 0x81A4).putInt(1).putInt(2000).putInt(2000)
                        body.putLong(file.length()).putLong(0).putLong(file.lastModified() / 1000).putLong(0)
                    }
                    output.write(body.array()); output.flush()
                }
                "SEND" -> {
                    val spec = String(readExact(input, length), Charsets.UTF_8)
                    val file = localFile(spec.substringBeforeLast(','))
                    file.parentFile.mkdirs()
                    file.outputStream().use { out ->
                        while (true) {
                            val chunk = ByteBuffer.wrap(readExact(input, 8)).order(ByteOrder.LITTLE_ENDIAN)
                            val chunkId = String(ByteArray(4).also { chunk.get(it) }, Charsets.US_ASCII)
                            val chunkLength = chunk.int
                            if (chunkId == "DONE") break
                            check(chunkId == "DATA") { "unexpected $chunkId" }
                            out.write(readExact(input, chunkLength))
                        }
                    }
                    output.write("OKAY".toByteArray()); output.write(ByteArray(4)); output.flush()
                }
                else -> {
                    output.write("FAIL".toByteArray())
                    val message = "unknown sync command $id".toByteArray()
                    output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(message.size).array())
                    output.write(message); output.flush()
                    return
                }
            }
        }
    }

    fun localFile(remotePath: String): File = File(root, remotePath.trimStart('/').replace('/', File.separatorChar))

    private fun readRequest(input: InputStream): String {
        val length = String(readExact(input, 4), Charsets.US_ASCII).toInt(16)
        return String(readExact(input, length), Charsets.UTF_8)
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw IOException("closed")
            offset += read
        }
        return buffer
    }

    private fun writeLengthPrefixed(output: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.write("%04x".format(bytes.size).toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun writeFrame(output: OutputStream, id: Int, payload: ByteArray) {
        output.write(id)
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array())
        output.write(payload)
    }

    override fun close() = server.close()
}
