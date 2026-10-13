package apkuploader.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstalledAppsTest {
    @Test
    fun parsesPackageListAndDeviceLoopOutput() {
        val codes = InstalledApps.parsePackageList(
            "package:com.beatgames.beatsaber versionCode:1408\npackage:dev.apkuploader.testapp versionCode:1\npackage:com.old.format\n",
        )
        assertEquals(mapOf("com.beatgames.beatsaber" to 1408L, "dev.apkuploader.testapp" to 1L, "com.old.format" to null), codes)

        val apps = InstalledApps.parseDetails(
            "com.beatgames.beatsaber|1.40.8|1843200|20480\n" +
                "dev.apkuploader.testapp||0|0\n" +
                "garbage line\n" +
                "com.old.format|2.0|0|12\n",
            codes,
        )
        assertEquals(3, apps.size)
        val beatSaber = apps.first { it.packageName == "com.beatgames.beatsaber" }
        assertEquals("1.40.8 (1408)", beatSaber.version)
        assertEquals(1843200L, beatSaber.obbKb)
        assertTrue(beatSaber.hasCache)
        assertEquals("/sdcard/Android/obb/com.beatgames.beatsaber", beatSaber.obbRoot)
        val test = apps.first { it.packageName == "dev.apkuploader.testapp" }
        assertNull(test.versionName)
        assertEquals("(1)", test.version)
        assertFalse(test.hasCache)
        assertEquals(12L, apps.first { it.packageName == "com.old.format" }.dataKb)
    }
}
