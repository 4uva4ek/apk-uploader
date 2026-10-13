package apkuploader.cache

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallScriptTest {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("install-script").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(path: String) = File(root, path).apply { parentFile.mkdirs(); writeBytes(ByteArray(3)) }

    @Test
    fun honoursOnlyPushLinesInsideTheFolder() {
        file("Game/game.apk")
        file("Game/com.foo.bar/main.1.com.foo.bar.obb")
        file("Game/extra/save.dat")
        file("Game/loose.bin")
        File(root, "Game/install.txt").writeText(
            """
            adb install -r "game.apk"
            adb push "com.foo.bar" /sdcard/Android/obb/
            adb push extra /sdcard/Android/data/com.foo.bar/files
            adb push loose.bin "/storage/emulated/0/Android/data/com.foo.bar/files/"
            adb push ../outside /sdcard/
            adb push loose.bin /data/local/tmp/
            adb shell pm grant com.foo.bar android.permission.RECORD_AUDIO
            """.trimIndent(),
        )
        val plan = CacheLayout.analyze(File(root, "Game"), listOf("com.foo.bar")).single()
        assertEquals(CacheKind.CUSTOM, plan.kind)
        assertEquals("com.foo.bar", plan.packageName)
        assertTrue(plan.confident)
        assertEquals(
            setOf(
                "/sdcard/Android/obb/com.foo.bar/main.1.com.foo.bar.obb",
                "/sdcard/Android/data/com.foo.bar/files/save.dat",
                "/sdcard/Android/data/com.foo.bar/files/loose.bin",
            ),
            plan.files.map { plan.remotePath("com.foo.bar", it) }.toSet(),
        )
        assertEquals("/sdcard/Android", plan.remoteRoot("com.foo.bar"))
        assertTrue(plan.notes.any { it.contains("adb install") })
        assertTrue(plan.notes.any { it.contains("pm grant") })
        assertTrue(plan.notes.any { it.contains("../outside") })
        assertTrue(plan.notes.any { it.contains("/data/local/tmp") })
    }

    @Test
    fun fallsBackToLayoutDetectionWithoutPushLines() {
        file("Game/com.foo.bar/main.1.com.foo.bar.obb")
        File(root, "Game/install.txt").writeText("adb install -r game.apk\n")
        val plan = CacheLayout.analyze(File(root, "Game"), emptyList()).single()
        assertEquals(CacheKind.OBB, plan.kind)
        assertEquals("com.foo.bar", plan.packageName)
        assertTrue(plan.notes.any { it.contains("no usable push") })
    }
}
