package apkuploader.cache

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheLayoutTest {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("cache-layout").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(path: String, size: Int = 10): File = File(root, path).apply {
        parentFile.mkdirs()
        writeBytes(ByteArray(size))
    }

    @Test
    fun rookieLayoutWithApkAndObbFolder() {
        file("Game/game.apk")
        file("Game/com.foo.bar/main.12.com.foo.bar.obb", 100)
        file("Game/install.txt")
        val plan = CacheLayout.analyze(File(root, "Game"), emptyList()).single()
        assertEquals(CacheKind.OBB, plan.kind)
        assertEquals("com.foo.bar", plan.packageName)
        assertTrue(plan.confident)
        assertEquals(listOf("main.12.com.foo.bar.obb"), plan.files.map { it.relativePath })
        assertEquals("/sdcard/Android/obb/com.foo.bar/main.12.com.foo.bar.obb", plan.remotePath("com.foo.bar", plan.files.single()))
        assertTrue(plan.notes.any { it.contains("install.txt") })
    }

    @Test
    fun obbFilesInArbitraryFolderUsePackageFromFileName() {
        file("Downloads/main.3.com.x.y.obb", 50)
        file("Downloads/patch.3.com.x.y.obb", 20)
        file("Downloads/Thumbs.db")
        val plan = CacheLayout.analyze(File(root, "Downloads"), emptyList()).single()
        assertEquals(CacheKind.OBB, plan.kind)
        assertEquals("com.x.y", plan.packageName)
        assertEquals(2, plan.files.size)
        assertEquals(70L, plan.totalBytes)
    }

    @Test
    fun packageNamedFolderIsApplicationData() {
        file("com.foo.bar/files/level1.dat")
        file("com.foo.bar/files/sub/level2.dat")
        val plan = CacheLayout.analyze(File(root, "com.foo.bar"), emptyList()).single()
        assertEquals(CacheKind.DATA, plan.kind)
        assertEquals("com.foo.bar", plan.packageName)
        assertEquals(listOf("files/level1.dat", "files/sub/level2.dat"), plan.files.map { it.relativePath })
        assertEquals("/sdcard/Android/data/com.foo.bar/files/sub/level2.dat", plan.remotePath("com.foo.bar", plan.files[1]))
    }

    @Test
    fun unpackedArchiveMirrorsAndroidFolder() {
        file("Unpacked/Android/obb/com.foo.bar/main.1.com.foo.bar.obb")
        file("Unpacked/Android/data/com.foo.bar/files/save.bin")
        val plan = CacheLayout.analyze(File(root, "Unpacked"), emptyList()).single()
        assertEquals(CacheKind.MIRROR, plan.kind)
        assertEquals("com.foo.bar", plan.packageName)
        assertEquals(
            setOf("/sdcard/Android/obb/com.foo.bar/main.1.com.foo.bar.obb", "/sdcard/Android/data/com.foo.bar/files/save.bin"),
            plan.files.map { plan.remotePath("com.foo.bar", it) }.toSet(),
        )
    }

    @Test
    fun unknownLayoutAsksButKeepsApkHint() {
        file("Stuff/a.bin")
        file("Stuff/b/c.bin")
        val withHint = CacheLayout.analyze(File(root, "Stuff"), listOf("com.hint.app")).single()
        assertFalse(withHint.confident)
        assertEquals("com.hint.app", withHint.packageName)
        assertEquals(2, withHint.files.size)
        val withoutHint = CacheLayout.analyze(File(root, "Stuff"), emptyList()).single()
        assertNull(withoutHint.packageName)
        assertFalse(withoutHint.confident)
    }
}
