package apkuploader.update

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateTest {
    private val platform = "windows-x64.zip"

    private fun release(version: String = "0.1.1"): GithubRelease {
        val name = "apk-uploader-$version-$platform"
        return GithubRelease(
            tagName = "v$version",
            assets = listOf(ReleaseAsset(name, "${UpdateClient.REPOSITORY}/releases/download/v$version/$name", 3, "sha256:" + "0".repeat(64))),
        )
    }

    @Test
    fun choosesOnlyNewStableWindowsRelease() {
        assertEquals("0.1.1", UpdateClient.select(release(), "0.1.0", platform)?.version)
        assertNull(UpdateClient.select(release(), "0.1.1", platform))
        assertNull(UpdateClient.select(release(), "0.2.0", platform))
        assertNull(UpdateClient.select(release().copy(prerelease = true), "0.1.0", platform))
        assertNull(UpdateClient.select(release().copy(draft = true), "0.1.0", platform))
        assertNull(UpdateClient.select(release().copy(tagName = "galaxyxr-v0.1.1"), "0.1.0", platform))
        assertFails { UpdateClient.select(release().copy(assets = emptyList()), "0.1.0", platform) }
        val wrong = release().assets.single().copy(url = "https://example.com/app")
        assertFails { UpdateClient.select(release().copy(assets = listOf(wrong)), "0.1.0", platform) }
        assertTrue(UpdateClient.newer("0.10.0", "0.9.9"))
        assertFalse(UpdateClient.newer("0.1.20", "0.2.0"))
    }

    @Test
    fun rejectsCorruptTruncatedAndOversizeDownloads() {
        val file = File.createTempFile("apk-uploader-test", ".bin")
        try {
            val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            UpdateClient.copyVerified("abc".byteInputStream(), file, 3, hash) {}
            assertEquals("abc", file.readText())
            assertFails { UpdateClient.copyVerified("abd".byteInputStream(), file, 3, hash) {} }
            assertFails { UpdateClient.copyVerified("ab".byteInputStream(), file, 3, hash) {} }
            assertFails { UpdateClient.copyVerified("abcd".byteInputStream(), file, 3, hash) {} }
        } finally {
            file.delete()
        }
    }

    @Test
    fun extractsOnlyCompleteConfinedWindowsPackages() {
        val root = Files.createTempDirectory("apk-uploader-archive-test").toFile()
        fun archive(names: List<String>): File {
            val zip = File(root, "update.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                names.forEach { name ->
                    out.putNextEntry(ZipEntry(name)); out.write(byteArrayOf(1)); out.closeEntry()
                }
            }
            return zip
        }
        try {
            val files = listOf("ApkUploader\\runtime\\", "ApkUploader/ApkUploader.exe", "ApkUploader/runtime/bin/server/jvm.dll", "ApkUploader/app/ApkUploader.cfg")
            WindowsAppUpdater.extract(archive(files), File(root, "ok").apply { mkdir() })
            for ((index, name) in listOf("ApkUploader/../outside", "ApkUploader/C:/outside", "elsewhere/file").withIndex()) {
                assertFails { WindowsAppUpdater.extract(archive(listOf(name)), File(root, "bad$index").apply { mkdir() }) }
            }
            assertFails { WindowsAppUpdater.extract(archive(listOf(files.first())), File(root, "incomplete").apply { mkdir() }) }
        } finally {
            root.deleteRecursively()
        }
    }
}
