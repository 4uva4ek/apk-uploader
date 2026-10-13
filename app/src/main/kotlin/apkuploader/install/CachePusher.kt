package apkuploader.install

import apkuploader.adb.AdbClient
import apkuploader.adb.AdbException
import apkuploader.apk.SplitBundle
import apkuploader.cache.CachePlan
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

enum class VerifyMode { NONE, SIZE, MD5 }

class PushResult(val copied: Int, val skipped: Int, val remoteRoot: String, val viaDownloadFolder: Boolean, val verified: VerifyMode) {
    val summary: String
        get() = buildString {
            append("$copied file(s) copied")
            if (skipped > 0) append(", $skipped already present")
            append(" → ").append(remoteRoot)
            if (viaDownloadFolder) append(" (via Download folder)")
            when (verified) {
                VerifyMode.SIZE -> append(", sizes verified")
                VerifyMode.MD5 -> append(", MD5 verified")
                VerifyMode.NONE -> {}
            }
        }
}

/** One file to upload: a local file or a ZIP entry, with its destination. */
private class Upload(val name: String, val remote: String, val size: Long, val mtimeSeconds: Long, val open: () -> InputStream, val local: File?)

/**
 * Copies cache files to the headset with the sync service. If the device refuses direct
 * writes into /sdcard/Android/{obb,data}, files go through /sdcard/Download and are moved.
 */
class CachePusher(private val client: AdbClient, private val log: (String) -> Unit = {}) {

    fun push(
        serial: String,
        plan: CachePlan,
        packageName: String,
        verify: VerifyMode,
        onConnection: (Closeable?) -> Unit = {},
        progress: (Long) -> Unit,
    ): PushResult {
        val uploads = plan.files.map { file ->
            Upload(file.relativePath, plan.remotePath(packageName, file), file.size, file.local.lastModified() / 1000, { file.local.inputStream() }, file.local)
        }
        return push(serial, plan.remoteRoot(packageName), uploads, verify, onConnection, progress)
    }

    /** OBB expansions inside a split package, streamed straight from the archive. */
    fun pushBundleObbs(
        serial: String,
        bundle: SplitBundle,
        verify: VerifyMode,
        onConnection: (Closeable?) -> Unit = {},
        progress: (Long) -> Unit,
    ): PushResult {
        if (bundle.obbs.isEmpty()) return PushResult(0, 0, "", false, VerifyMode.NONE)
        ZipFile(bundle.file).use { zip ->
            val uploads = bundle.obbs.map { obb ->
                Upload(obb.entryName, obb.remotePath, obb.size, obb.mtimeSeconds, { zip.getInputStream(zip.getEntry(obb.entryName)) }, null)
            }
            val root = uploads.map { it.remote.substringBeforeLast('/') }.distinct().singleOrNull() ?: "/sdcard/Android"
            return push(serial, root, uploads, verify, onConnection, progress)
        }
    }

    private fun push(
        serial: String,
        remoteRoot: String,
        uploads: List<Upload>,
        verify: VerifyMode,
        onConnection: (Closeable?) -> Unit,
        progress: (Long) -> Unit,
    ): PushResult {
        val mkdir = client.shell(serial, "mkdir -p ${AdbClient.quote(remoteRoot)}")
        if (!mkdir.ok && mkdir.output.isNotEmpty()) log("mkdir $remoteRoot: ${mkdir.output}")

        var done = 0L
        var copied = 0
        var skipped = 0
        var staging: String? = null
        val digests = mutableMapOf<String, String>()

        client.sync(serial).use { sync ->
            onConnection(sync)
            // Probe before uploading gigabytes: can adb write there directly?
            val probe = "$remoteRoot/.apk-uploader-probe"
            val direct = try {
                sync.send(ProbeFile.file, probe) {}
                true
            } catch (e: AdbException) {
                if (!isPermissionError(e)) throw e
                false
            }
            if (direct) {
                client.shell(serial, "rm -f ${AdbClient.quote(probe)}")
            } else {
                staging = "/sdcard/Download/.apk-uploader-" + java.lang.Long.toHexString(System.nanoTime())
                log("Direct write to $remoteRoot was denied; copying through $staging")
            }
            onConnection(null)
        }

        val session = client.sync(serial)
        onConnection(session)
        try {
            for (upload in uploads) {
                val existing = runCatching { session.stat(upload.remote) }.getOrNull()
                if (existing != null && existing.isFile && existing.size == upload.size) {
                    skipped++
                    done += upload.size
                    if (verify == VerifyMode.MD5) digests[upload.remote] = upload.open().use { md5(it) }
                    progress(done)
                    continue
                }
                val base = done
                val digest = if (verify == VerifyMode.MD5) MessageDigest.getInstance("MD5") else null
                if (staging == null) {
                    upload.open().use { session.send(it, upload.mtimeSeconds, upload.remote, digest) { progress(base + it) } }
                } else {
                    val temporary = "$staging/${upload.name}"
                    upload.open().use { session.send(it, upload.mtimeSeconds, temporary, digest) { progress(base + it) } }
                    val parent = upload.remote.substringBeforeLast('/')
                    val move = client.shell(serial, "mkdir -p ${AdbClient.quote(parent)} && mv -f ${AdbClient.quote(temporary)} ${AdbClient.quote(upload.remote)}")
                    if (!move.ok) throw AdbException("Cannot place ${upload.name} into $parent: ${move.output.ifEmpty { "mv failed" }}")
                }
                digest?.let { digests[upload.remote] = hex(it.digest()) }
                copied++
                done += upload.size
                progress(done)
            }
        } finally {
            onConnection(null)
            session.close()
            staging?.let { client.shell(serial, "rm -rf ${AdbClient.quote(it)}") }
        }

        when (verify) {
            VerifyMode.NONE -> {}
            VerifyMode.SIZE -> verifySizes(serial, uploads, staging != null)
            VerifyMode.MD5 -> verifyMd5(serial, uploads, digests)
        }
        return PushResult(copied, skipped, remoteRoot, staging != null, verify)
    }

    private fun verifySizes(serial: String, uploads: List<Upload>, useShell: Boolean) {
        val mismatched = mutableListOf<String>()
        if (!useShell) {
            try {
                client.sync(serial).use { sync ->
                    for (upload in uploads) {
                        val stat = sync.stat(upload.remote)
                        if (!stat.isFile || stat.size != upload.size) mismatched += upload.name
                    }
                }
                if (mismatched.isNotEmpty()) throw AdbException("Verification failed for: ${mismatched.take(5).joinToString()}")
                return
            } catch (e: AdbException) {
                if (!isPermissionError(e)) throw e
            }
        }
        for (batch in uploads.chunked(40)) {
            val command = batch.joinToString("; ") { "stat -c %s ${AdbClient.quote(it.remote)} 2>/dev/null || echo missing" }
            val sizes = client.shell(serial, command).stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            batch.forEachIndexed { index, upload ->
                if (sizes.getOrNull(index)?.toLongOrNull() != upload.size) mismatched += upload.name
            }
        }
        if (mismatched.isNotEmpty()) throw AdbException("Verification failed for: ${mismatched.take(5).joinToString()}")
    }

    private fun verifyMd5(serial: String, uploads: List<Upload>, expected: Map<String, String>) {
        val mismatched = mutableListOf<String>()
        for (batch in uploads.chunked(20)) {
            val command = "md5sum " + batch.joinToString(" ") { AdbClient.quote(it.remote) }
            val output = client.shell(serial, command, ApkInstaller.INSTALL_TIMEOUT_MS).stdout
            val remote = output.lines().mapNotNull { line ->
                val match = Regex("^([0-9a-fA-F]{32})\\s+(.+)$").find(line.trim()) ?: return@mapNotNull null
                match.groupValues[2] to match.groupValues[1].lowercase()
            }.toMap()
            for (upload in batch) {
                if (remote[upload.remote] == null || remote[upload.remote] != expected[upload.remote]) mismatched += upload.name
            }
        }
        if (mismatched.isNotEmpty()) throw AdbException("MD5 verification failed for: ${mismatched.take(5).joinToString()}")
    }

    companion object {
        fun isPermissionError(e: Exception): Boolean {
            val message = e.message.orEmpty()
            return message.contains("Permission denied", true) || message.contains("EACCES") || message.contains("Operation not permitted", true)
        }

        fun md5(input: InputStream): String {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }

    private object ProbeFile {
        val file: File by lazy {
            File.createTempFile("apk-uploader-probe", ".tmp").apply {
                writeText("probe")
                deleteOnExit()
            }
        }
    }
}
