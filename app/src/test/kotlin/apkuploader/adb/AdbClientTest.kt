package apkuploader.adb

import apkuploader.install.ApkInstaller
import apkuploader.install.InstallFlags
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbClientTest {
    private lateinit var root: File
    private lateinit var server: FakeAdbServer
    private lateinit var client: AdbClient

    @BeforeTest
    fun start() {
        root = Files.createTempDirectory("fake-device").toFile()
        server = FakeAdbServer(root)
        client = AdbClient("127.0.0.1", server.port)
    }

    @AfterTest
    fun stop() {
        server.close()
        root.deleteRecursively()
    }

    @Test
    fun listsDevicesAndRunsShellCommands() {
        assertTrue(client.isServerRunning())
        val device = client.devices().single()
        assertEquals("EMU123", device.serial)
        assertEquals("Fake Device", device.displayName)
        assertTrue(device.isReady)
        assertFalse(device.isWifi)

        server.shellHandlers["getprop ro.product.model"] = "SM-I610\n" to 0
        val result = client.shell("EMU123", "getprop ro.product.model")
        assertEquals("SM-I610", result.stdout.trim())
        assertEquals(0, result.exitCode)
        assertEquals(127, client.shell("EMU123", "missing-command").exitCode)
    }

    @Test
    fun pushesFilesWithByteProgressAndStats() {
        val local = File(root, "local.bin").apply { writeBytes(Random(1).nextBytes(200_000 + 123)) }
        val remote = "/sdcard/Android/obb/com.example.game/main.1.com.example.game.obb"
        val progress = mutableListOf<Long>()
        client.sync("EMU123").use { sync ->
            assertFalse(sync.stat(remote).exists)
            sync.send(local, remote) { progress += it }
            val stat = sync.stat(remote)
            assertTrue(stat.isFile)
            assertEquals(local.length(), stat.size)
        }
        assertEquals(local.length(), progress.last())
        assertTrue(progress.size >= 4)
        assertContentEquals(local.readBytes(), File(root, "sdcard/Android/obb/com.example.game/main.1.com.example.game.obb").readBytes())
    }

    @Test
    fun streamsWholeApkIntoPackageInstaller() {
        val apk = File(root, "app.apk").apply { writeBytes(Random(2).nextBytes(150_000)) }
        var last = 0L
        val result = ApkInstaller(client).install("EMU123", apk, InstallFlags(reinstall = true, grantPermissions = true)) { last = it }
        assertTrue(result.success, result.message)
        assertEquals(apk.length(), last)
        assertEquals(apk.length(), server.installedBytes.get())
        assertEquals("cmd package install -r -g -S ${apk.length()}", server.installCommand)
    }
}
