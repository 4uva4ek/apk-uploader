package apkuploader.adb

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class RemoteStat(val mode: Int, val size: Long, val mtimeSeconds: Long) {
    val exists: Boolean get() = mode != 0
    val isDirectory: Boolean get() = (mode and 0xF000) == 0x4000
    val isFile: Boolean get() = (mode and 0xF000) == 0x8000
}

/** The adb `sync:` service: file transfer with byte-accurate progress. */
class SyncSession(private val connection: AdbConnection) : Closeable {

    /** STA2 (64-bit stat). A missing path returns a stat with mode 0. */
    fun stat(path: String): RemoteStat {
        sendRequest("STA2", path.toByteArray(Charsets.UTF_8))
        val id = String(connection.readExact(4), Charsets.US_ASCII)
        when (id) {
            "STA2" -> {
                val body = ByteBuffer.wrap(connection.readExact(68)).order(ByteOrder.LITTLE_ENDIAN)
                val error = body.int
                body.long; body.long // dev, ino
                val mode = body.int
                body.int; body.int; body.int // nlink, uid, gid
                val size = body.long
                body.long // atime
                val mtime = body.long
                return if (error != 0) RemoteStat(0, 0, 0) else RemoteStat(mode, size, mtime)
            }
            "FAIL" -> throw AdbException(String(connection.readExact(readLeInt()), Charsets.UTF_8))
            else -> throw AdbException("Unexpected sync response: $id")
        }
    }

    /** Uploads [local] to [remotePath]; see the stream overload. */
    fun send(local: File, remotePath: String, digest: MessageDigest? = null, progress: (Long) -> Unit) {
        local.inputStream().use { send(it, local.lastModified() / 1000, remotePath, digest, progress) }
    }

    /**
     * Uploads [input] to [remotePath]. [progress] receives the running byte count and [digest],
     * when given, is updated with every byte sent. Missing parent directories are created by the
     * device. Throws [AdbException] on FAIL.
     */
    fun send(input: InputStream, mtimeSeconds: Long, remotePath: String, digest: MessageDigest? = null, progress: (Long) -> Unit) {
        val mode = 0x8000 or 0b110100100 // regular file, 0644
        sendRequest("SEND", "$remotePath,$mode".toByteArray(Charsets.UTF_8))
        val buffer = ByteArray(CHUNK)
        var sent = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            writeHeader("DATA", read)
            connection.write(buffer, 0, read)
            digest?.update(buffer, 0, read)
            sent += read
            progress(sent)
        }
        writeHeader("DONE", mtimeSeconds.toInt())
        connection.flush()
        readResult()
    }

    private fun readResult() {
        val id = String(connection.readExact(4), Charsets.US_ASCII)
        val length = readLeInt()
        when (id) {
            "OKAY" -> return
            "FAIL" -> throw AdbException(String(connection.readExact(length), Charsets.UTF_8))
            else -> throw AdbException("Unexpected sync response: $id")
        }
    }

    private fun sendRequest(id: String, payload: ByteArray) {
        writeHeader(id, payload.size)
        connection.write(payload)
        connection.flush()
    }

    private fun writeHeader(id: String, value: Int) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put(id.toByteArray(Charsets.US_ASCII))
        header.putInt(value)
        connection.write(header.array())
    }

    private fun readLeInt(): Int = ByteBuffer.wrap(connection.readExact(4)).order(ByteOrder.LITTLE_ENDIAN).int

    override fun close() {
        try {
            writeHeader("QUIT", 0)
            connection.flush()
        } catch (_: IOException) {
        }
        connection.close()
    }

    companion object {
        const val CHUNK = 64 * 1024
    }
}
