package apkuploader.apk

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.io.IOException

class ApkInfo(
    val file: File,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val label: String,
    val minSdk: Int?,
    val icon: ByteArray?,
) {
    val size: Long get() = file.length()
}

/** Reads package metadata and the launcher icon from an APK without unpacking it. */
object ApkReader {
    fun read(file: File): ApkInfo {
        if (!file.isFile) throw IOException("${file.name} is not a file")
        ApkModule.loadApkFile(file).use { module ->
            val manifest = module.androidManifest ?: throw IOException("${file.name} has no AndroidManifest.xml")
            val packageName = manifest.packageName?.takeIf { it.isNotBlank() }
                ?: throw IOException("${file.name} has no package name")
            val versionCode = manifest.versionCode?.toLong() ?: 0L
            val versionName = manifest.versionName?.takeIf { it.isNotBlank() } ?: versionCode.toString()
            val label = manifest.applicationLabelString?.takeIf { it.isNotBlank() }
                ?: runCatching { resolveLabel(module, manifest) }.getOrNull()
                ?: packageName
            val icon = runCatching { loadIcon(module, manifest) }.getOrNull()
            return ApkInfo(file, packageName, versionName, versionCode, label, manifest.minSdkVersion, icon)
        }
    }

    private fun resolveLabel(module: ApkModule, manifest: AndroidManifestBlock): String? {
        val reference = manifest.applicationLabelReference ?: return null
        return module.tableBlock?.getEntries(reference)?.asSequence()?.mapNotNull { it.valueAsString }?.firstOrNull()
    }

    private fun loadIcon(module: ApkModule, manifest: AndroidManifestBlock): ByteArray? {
        val iconId = manifest.iconResourceId.takeIf { it != 0 }
            ?: manifest.roundIconResourceId.takeIf { it != 0 }
            ?: return null
        val files = module.listResFiles(iconId, null) ?: return null
        for (density in listOf("-xxxhdpi", "-xxhdpi", "-xhdpi", "-hdpi", "-mdpi", "")) {
            for (extension in listOf(".png", ".webp")) {
                val match = files.firstOrNull { it.filePath.contains(density) && it.filePath.endsWith(extension) } ?: continue
                return match.inputSource.openStream().use { it.readBytes() }
            }
        }
        return null
    }
}
