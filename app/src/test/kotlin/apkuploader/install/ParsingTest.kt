package apkuploader.install

import apkuploader.adb.AdbClient
import apkuploader.adb.DeviceInfo
import apkuploader.adb.DeviceQueries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {
    @Test
    fun installResults() {
        assertTrue(InstallResult.parse("Success\n").success)
        val failure = InstallResult.parse("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.x signatures do not match newer version; ignoring!]\n")
        assertFalse(failure.success)
        assertTrue(failure.needsUninstall)
        assertTrue(failure.message.startsWith("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertFalse(InstallResult.parse("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]").needsUninstall)
        assertEquals("The installer returned no result.", InstallResult.parse("").message)
        assertTrue(InstallQueue.friendlyError("Failure INSTALL_FAILED_OLDER_SDK").contains("newer Android"))
    }

    @Test
    fun dumpsysAndDeviceParsing() {
        val dump = "Packages:\n  Package [com.x] (abc):\n    versionCode=120 minSdk=29 targetSdk=34\n    versionName=1.2.0\n"
        val installed = ApkInstaller.parseDumpsys(dump)!!
        assertEquals(120L, installed.versionCode)
        assertEquals("1.2.0", installed.versionName)
        assertNull(ApkInstaller.parseDumpsys("Unable to find package: com.x"))

        assertEquals(12345678L, DeviceQueries.parseDfAvailableKb("Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-9 115000000 90000000  12345678  88% /data\n"))
        assertEquals(
            207427012L,
            DeviceQueries.parseDfAvailableKb(
                "Filesystem      1K-blocks     Used Available Use% Mounted on\n" +
                    "/dev/block/dm-47 228369372 20811288 207427012  10% /data\n" +
                    "/dev/block/dm-47 228369372 20811288 207427012  10% /storage/emulated/0/Android/obb\n",
            ),
        )

        val devices = DeviceInfo.parseList(
            "R3GYB0E898N            device product:xrvst2ue model:SM_I610 device:xrvst2 transport_id:3\n" +
                "192.168.88.46:5555     unauthorized transport_id:4\n" +
                "adb-R3GYB0E898N-AbCdEf._adb-tls-connect._tcp offline transport_id:5\n",
        )
        assertEquals(3, devices.size)
        assertEquals("SM I610", devices[0].displayName)
        assertFalse(devices[0].isWifi)
        assertTrue(devices[1].isWifi)
        assertTrue(devices[1].isUnauthorized)
        assertTrue(devices[2].isWifi)
        assertEquals("'it''s'".replace("''", "'\\''"), AdbClient.quote("it's"))
    }
}
