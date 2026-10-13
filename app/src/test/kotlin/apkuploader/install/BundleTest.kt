package apkuploader.install

import apkuploader.adb.AdbClient
import apkuploader.adb.FakeAdbServer
import apkuploader.apk.BundleReader
import apkuploader.cache.CacheKind
import apkuploader.cache.CacheLayout
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Split packages: reading an XAPK, choosing splits per ABI, installing through a session and pushing OBBs. */
class BundleTest {
    private lateinit var root: File
    private lateinit var server: FakeAdbServer
    private lateinit var client: AdbClient
    private val testApk: ByteArray = checkNotNull(javaClass.getResourceAsStream("/testapp.apk")) { "testapp.apk fixture missing" }.use { it.readBytes() }

    @BeforeTest
    fun start() {
        root = Files.createTempDirectory("bundle-test").toFile()
        server = FakeAdbServer(root)
        client = AdbClient("127.0.0.1", server.port)
    }

    @AfterTest
    fun stop() {
        server.close()
        root.deleteRecursively()
    }

    private fun xapk(): File {
        val obb = Random(3).nextBytes(100_000)
        val file = File(root, "game.xapk")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            entry("manifest.json", """{"package_name":"dev.apkuploader.testapp","version_code":"1","expansions":[{"file":"Android/obb/dev.apkuploader.testapp/main.1.dev.apkuploader.testapp.obb","install_location":"EXTERNAL_STORAGE","install_path":"Android/obb/dev.apkuploader.testapp/main.1.dev.apkuploader.testapp.obb"}]}""".toByteArray())
            entry("config.en.apk", testApk)
            entry("config.x86.apk", testApk)
            entry("config.arm64_v8a.apk", testApk)
            entry("dev.apkuploader.testapp.apk", testApk)
            entry("Android/obb/dev.apkuploader.testapp/main.1.dev.apkuploader.testapp.obb", obb)
            entry("icon.png", byteArrayOf(1, 2, 3))
        }
        return file
    }

    @Test
    fun readsXapkAndSelectsSplitsForDeviceAbi() {
        val bundle = BundleReader.read(xapk())
        assertEquals("XAPK", bundle.format)
        assertEquals("dev.apkuploader.testapp", bundle.packageName)
        assertEquals(1L, bundle.versionCode)
        assertEquals("dev.apkuploader.testapp.apk", bundle.base.fileName)
        assertEquals(listOf("config.arm64_v8a.apk", "config.en.apk", "config.x86.apk"), bundle.splits.map { it.fileName }.sorted())
        assertEquals(1, bundle.obbs.size)
        assertEquals("/sdcard/Android/obb/dev.apkuploader.testapp/main.1.dev.apkuploader.testapp.obb", bundle.obbs.single().remotePath)
        val selected = bundle.select(listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(listOf("config.arm64_v8a.apk", "config.en.apk", "dev.apkuploader.testapp.apk"), selected.map { it.fileName }.sorted())
    }

    @Test
    fun installsThroughSessionAndPushesObb() {
        val bundle = BundleReader.read(xapk())
        server.shellFallback = { command ->
            when {
                command.startsWith("cmd package install-create") -> "Success: created install session [77]\n" to 0
                command == "cmd package install-commit 77" -> "Success\n" to 0
                command.startsWith("mkdir") || command.startsWith("rm ") -> "" to 0
                else -> null
            }
        }
        var last = 0L
        val result = BundleInstaller(client).install("EMU123", bundle, InstallFlags(), listOf("arm64-v8a")) { last = it }
        assertTrue(result.success, result.message)
        assertEquals(3L * testApk.size, last)
        assertEquals(listOf("0_config.en.apk", "1_config.arm64_v8a.apk", "2_dev.apkuploader.testapp.apk").sorted(), server.sessionWrites.map { it.first }.sorted())
        assertTrue(server.sessionWrites.all { it.second == testApk.size.toLong() })
        assertTrue(server.requests.any { it.contains("install-create -r -S ${3L * testApk.size}") })

        val pushed = CachePusher(client).pushBundleObbs("EMU123", bundle, VerifyMode.SIZE) {}
        assertEquals(1, pushed.copied)
        val onDevice = server.localFile(bundle.obbs.single().remotePath)
        assertEquals(100_000L, onDevice.length())
        assertContentEquals(Random(3).nextBytes(100_000), onDevice.readBytes())
    }

    @Test
    fun md5VerificationUsesDeviceChecksums() {
        val folder = File(root, "com.foo.bar").apply { mkdirs() }
        File(folder, "files").mkdirs()
        val payload = Random(5).nextBytes(70_000)
        File(folder, "files/a.bin").writeBytes(payload)
        val plan = CacheLayout.analyze(folder, emptyList()).single()
        assertEquals(CacheKind.DATA, plan.kind)
        server.shellFallback = { command ->
            when {
                command.startsWith("md5sum ") -> {
                    val path = command.removePrefix("md5sum ").trim('\'')
                    val file = server.localFile(path)
                    "${CachePusher.md5(file.inputStream())}  $path\n" to 0
                }
                command.startsWith("mkdir") || command.startsWith("rm ") -> "" to 0
                else -> null
            }
        }
        val result = CachePusher(client).push("EMU123", plan, "com.foo.bar", VerifyMode.MD5) {}
        assertEquals(VerifyMode.MD5, result.verified)
        assertEquals(1, result.copied)
        // Corrupt the copy on the "device": verification of the skipped (same size) file must now fail.
        server.localFile("/sdcard/Android/data/com.foo.bar/files/a.bin").writeBytes(Random(6).nextBytes(70_000))
        val failure = runCatching { CachePusher(client).push("EMU123", plan, "com.foo.bar", VerifyMode.MD5) {} }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("MD5 verification failed"), failure?.message)
    }
}
