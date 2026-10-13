package apkuploader

import apkuploader.adb.AdbClient
import apkuploader.adb.AdbServer
import apkuploader.adb.DeviceQueries
import apkuploader.apk.ApkReader
import apkuploader.apk.BundleReader
import apkuploader.cache.CacheKind
import apkuploader.cache.CacheLayout
import apkuploader.install.ApkInstaller
import apkuploader.install.BundleInstaller
import apkuploader.install.CachePusher
import apkuploader.install.InstallFlags
import apkuploader.install.VerifyMode
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end checks against a real device. They run only when APK_UPLOADER_TEST_APK (plain APK)
 * or APK_UPLOADER_TEST_XAPK (split package with OBB) points to a package that is NOT installed
 * on the device; everything the tests install or copy is removed at the end.
 * Optional: APK_UPLOADER_TEST_SERIAL (default: the single ready device),
 * APK_UPLOADER_TEST_ADB (adb.exe used to start a server when none is running).
 */
class DeviceIntegrationTest {
    private fun connect(): Pair<AdbClient, String> {
        val client = AdbClient()
        if (!client.isServerRunning()) AdbServer.start(AdbServer.locate(System.getenv("APK_UPLOADER_TEST_ADB").orEmpty()))
        val serial = System.getenv("APK_UPLOADER_TEST_SERIAL")?.takeIf { it.isNotBlank() }
            ?: client.devices().filter { it.isReady }.single().serial
        println("Device: ${DeviceQueries.details(client, serial).summary}")
        return client to serial
    }

    @Test
    fun installApkAndObbCacheOnRealDevice() {
        val apkPath = System.getenv("APK_UPLOADER_TEST_APK")
        if (apkPath.isNullOrBlank()) {
            println("skipped: APK_UPLOADER_TEST_APK is not set")
            return
        }
        val (client, serial) = connect()
        val info = ApkReader.read(File(apkPath))
        println("APK: ${info.packageName} ${info.versionName} (${info.versionCode}), ${info.size} bytes, label ${info.label}")
        val installer = ApkInstaller(client)
        assertNull(installer.installed(serial, info.packageName), "${info.packageName} is already installed; use another APK")

        val obbRoot = "/sdcard/Android/obb/${info.packageName}"
        val cacheDir = Files.createTempDirectory("apk-uploader-cache").toFile()
        var installed = false
        try {
            var last = 0L
            val result = installer.install(serial, info.file, InstallFlags()) { last = it }
            assertTrue(result.success, result.message)
            installed = true
            assertEquals(info.size, last)
            val onDevice = installer.installed(serial, info.packageName)
            assertEquals(info.versionCode, onDevice?.versionCode)
            println("Installed: ${onDevice?.versionName} (${onDevice?.versionCode})")

            File(cacheDir, "main.${info.versionCode}.${info.packageName}.obb").writeBytes(Random(7).nextBytes(3 * 1024 * 1024 + 17))
            File(cacheDir, "patch.${info.versionCode}.${info.packageName}.obb").writeBytes(Random(8).nextBytes(512 * 1024 + 5))
            val plan = CacheLayout.analyze(cacheDir, listOf(info.packageName)).single()
            assertEquals(CacheKind.OBB, plan.kind)
            assertEquals(info.packageName, plan.packageName)

            val pushed = CachePusher(client) { println("pusher: $it") }.push(serial, plan, info.packageName, VerifyMode.MD5) {}
            println("Push: ${pushed.summary}")
            assertEquals(2, pushed.copied)
            val again = CachePusher(client).push(serial, plan, info.packageName, VerifyMode.SIZE) {}
            assertEquals(2, again.skipped)
            println(client.shell(serial, "ls -l ${AdbClient.quote(obbRoot)}").output)
        } finally {
            if (installed) println("Uninstall: ${installer.uninstall(serial, info.packageName).message}")
            client.shell(serial, "rm -rf ${AdbClient.quote(obbRoot)}")
            cacheDir.deleteRecursively()
        }
        assertNull(installer.installed(serial, info.packageName))
        assertFalse(client.shell(serial, "ls ${AdbClient.quote(obbRoot)}").ok)
        println("Cleanup verified: package removed, $obbRoot removed")
    }

    @Test
    fun installXapkWithSplitAndObbOnRealDevice() {
        val xapkPath = System.getenv("APK_UPLOADER_TEST_XAPK")
        if (xapkPath.isNullOrBlank()) {
            println("skipped: APK_UPLOADER_TEST_XAPK is not set")
            return
        }
        val (client, serial) = connect()
        val bundle = BundleReader.read(File(xapkPath))
        println("Bundle: ${bundle.format} ${bundle.packageName} ${bundle.versionName} (${bundle.versionCode}); APKs ${bundle.apks.map { it.fileName }}; OBB ${bundle.obbs.map { it.remotePath }}")
        val installer = ApkInstaller(client)
        assertNull(installer.installed(serial, bundle.packageName), "${bundle.packageName} is already installed; use another package")
        val bundleInstaller = BundleInstaller(client)
        val abis = bundleInstaller.deviceAbis(serial)
        println("Device ABIs: $abis")
        val obbRoot = "/sdcard/Android/obb/${bundle.packageName}"
        var installed = false
        try {
            var last = 0L
            val result = bundleInstaller.install(serial, bundle, InstallFlags(), abis, log = { println("install: $it") }) { last = it }
            assertTrue(result.success, result.message)
            installed = true
            assertEquals(bundle.select(abis).sumOf { it.size }, last)
            val paths = client.shell(serial, "pm path ${AdbClient.quote(bundle.packageName)}").stdout.lines().filter { it.startsWith("package:") }
            println(paths.joinToString("\n"))
            assertEquals(bundle.select(abis).size, paths.size, "base and splits should be installed")
            val pushed = CachePusher(client) { println("pusher: $it") }.pushBundleObbs(serial, bundle, VerifyMode.MD5) {}
            println("OBB: ${pushed.summary}")
            assertEquals(bundle.obbs.size, pushed.copied)
            println(client.shell(serial, "ls -l ${AdbClient.quote(obbRoot)}").output)
        } finally {
            if (installed) println("Uninstall: ${installer.uninstall(serial, bundle.packageName).message}")
            client.shell(serial, "rm -rf ${AdbClient.quote(obbRoot)}")
        }
        assertNull(installer.installed(serial, bundle.packageName))
        assertFalse(client.shell(serial, "ls ${AdbClient.quote(obbRoot)}").ok)
        println("Cleanup verified: package removed, $obbRoot removed")
    }
}
