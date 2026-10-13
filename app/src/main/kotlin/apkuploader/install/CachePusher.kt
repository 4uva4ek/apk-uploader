package apkuploader.install

import apkuploader.adb.AdbClient
import apkuploader.adb.AdbException
import apkuploader.adb.SyncSession
import apkuploader.cache.CacheFile
import apkuploader.cache.CachePlan
import java.io.Closeable

class PushResult(val copied: Int, val skipped: Int, val remoteRoot: String, val viaDownloadFolder: Boolean) {
    val summary: String
        get() = buildString {
            append("$copied file(s) copied")
            if (skipped > 0) append(", $skipped already present")
            append(" → ").append(remoteRoot)
            if (viaDownloadFolder) append(" (via Download folder)")
        }
}

/**
 * Copies a cache folder to the headset with the sync service. If the device refuses direct
 * writes into /sdcard/Android/{obb,data}, files go through /sdcard/Download and are moved.
 */
class CachePusher(private val client: AdbClient, private val log: (String) -> Unit = {}) {

    fun push(
        serial: String,
        plan: CachePlan,
        packageName: String,
        verify: Boolean,
        onConnection: (Closeable?) -> Unit = {},
        progress: (Long) -> Unit,
    ): PushResult {
        val remoteRoot = plan.remoteRoot(packageName)
        val mkdir = client.shell(serial, "mkdir -p ${AdbClient.quote(remoteRoot)}")
        if (!mkdir.ok && mkdir.output.isNotEmpty()) log("mkdir $remoteRoot: ${mkdir.output}")

        var done = 0L
        var copied = 0
        var skipped = 0
        var staging: String? = null

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
            for (file in plan.files) {
                val remote = plan.remotePath(packageName, file)
                val existing = runCatching { session.stat(remote) }.getOrNull()
                if (existing != null && existing.isFile && existing.size == file.size) {
                    skipped++
                    done += file.size
                    progress(done)
                    continue
                }
                val base = done
                if (staging == null) {
                    session.send(file.local, remote) { progress(base + it) }
                } else {
                    val temporary = "$staging/${file.relativePath}"
                    session.send(file.local, temporary) { progress(base + it) }
                    val parent = remote.substringBeforeLast('/')
                    val move = client.shell(serial, "mkdir -p ${AdbClient.quote(parent)} && mv -f ${AdbClient.quote(temporary)} ${AdbClient.quote(remote)}")
                    if (!move.ok) throw AdbException("Cannot place ${file.relativePath} into $parent: ${move.output.ifEmpty { "mv failed" }}")
                }
                copied++
                done += file.size
                progress(done)
            }
        } finally {
            onConnection(null)
            session.close()
            staging?.let { client.shell(serial, "rm -rf ${AdbClient.quote(it)}") }
        }

        if (verify) verifySizes(serial, plan, packageName, staging != null)
        return PushResult(copied, skipped, remoteRoot, staging != null)
    }

    private fun verifySizes(serial: String, plan: CachePlan, packageName: String, useShell: Boolean) {
        val mismatched = mutableListOf<String>()
        if (!useShell) {
            try {
                client.sync(serial).use { sync ->
                    for (file in plan.files) {
                        val stat = sync.stat(plan.remotePath(packageName, file))
                        if (!stat.isFile || stat.size != file.size) mismatched += file.relativePath
                    }
                }
                if (mismatched.isNotEmpty()) throw AdbException("Verification failed for: ${mismatched.take(5).joinToString()}")
                return
            } catch (e: AdbException) {
                if (!isPermissionError(e)) throw e
            }
        }
        for (batch in plan.files.chunked(40)) {
            val command = batch.joinToString("; ") { "stat -c %s ${AdbClient.quote(plan.remotePath(packageName, it))} 2>/dev/null || echo missing" }
            val sizes = client.shell(serial, command).stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            batch.forEachIndexed { index, file ->
                if (sizes.getOrNull(index)?.toLongOrNull() != file.size) mismatched += file.relativePath
            }
        }
        if (mismatched.isNotEmpty()) throw AdbException("Verification failed for: ${mismatched.take(5).joinToString()}")
    }

    companion object {
        fun isPermissionError(e: Exception): Boolean {
            val message = e.message.orEmpty()
            return message.contains("Permission denied", true) || message.contains("EACCES") || message.contains("Operation not permitted", true)
        }
    }

    private object ProbeFile {
        val file: java.io.File by lazy {
            java.io.File.createTempFile("apk-uploader-probe", ".tmp").apply {
                writeText("probe")
                deleteOnExit()
            }
        }
    }
}

@Suppress("unused")
private fun CacheFile.describe() = "$relativePath (${size} bytes)"
