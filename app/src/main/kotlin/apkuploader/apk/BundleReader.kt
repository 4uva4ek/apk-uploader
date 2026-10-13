package apkuploader.apk

import com.reandroid.apk.ApkModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** One APK inside a split package. [config] is the ABI, density or language of a config split. */
class BundleApk(val entryName: String, val size: Long, val isBase: Boolean, val config: String?) {
    val fileName: String get() = entryName.substringAfterLast('/')
    val isAbiSplit: Boolean get() = config != null && config in BundleReader.ABIS
}

/** An OBB (or other `Android/...`) file inside a split package. */
class BundleObb(val entryName: String, val remotePath: String, val size: Long, val mtimeSeconds: Long)

/** XAPK, APKS or APKM: a base APK, config/feature splits and optional OBB expansions. */
class SplitBundle(
    val file: File,
    val format: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val label: String,
    val minSdk: Int?,
    val icon: ByteArray?,
    val apks: List<BundleApk>,
    val obbs: List<BundleObb>,
) {
    val base: BundleApk get() = apks.first { it.isBase }
    val splits: List<BundleApk> get() = apks.filter { !it.isBase }
    val apkBytes: Long get() = apks.sumOf { it.size }
    val obbBytes: Long get() = obbs.sumOf { it.size }
    val totalBytes: Long get() = apkBytes + obbBytes

    /** APKs to install on a device with [deviceAbis] (as reported by ro.product.cpu.abilist). */
    fun select(deviceAbis: List<String>): List<BundleApk> {
        val supported = deviceAbis.map { it.replace('-', '_') }.toSet()
        return apks.filter { it.isBase || !it.isAbiSplit || it.config in supported }
    }
}

object BundleReader {
    val ABIS = setOf("armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64")
    val EXTENSIONS = listOf("xapk", "apks", "apkm")
    private val json = Json { ignoreUnknownKeys = true }
    private val configPattern = Regex("^(?:split_config\\.|config\\.|base-)([A-Za-z0-9_]+)\\.apk$", RegexOption.IGNORE_CASE)

    fun isBundle(file: File): Boolean = file.extension.lowercase() in EXTENSIONS

    fun read(file: File): SplitBundle {
        if (!file.isFile) throw IOException("${file.name} is not a file")
        val format = file.extension.uppercase()
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            val apkEntries = entries.filter { it.name.endsWith(".apk", true) && !it.name.startsWith("META-INF/") }
            if (apkEntries.isEmpty()) throw IOException("${file.name} contains no APK files")

            val expansions = readExpansions(zip, entries)
            val obbs = (expansions + entries.filter { it.name.startsWith("Android/obb/") || it.name.startsWith("Android/data/") }
                .map { BundleObb(it.name, "/sdcard/" + it.name, it.size, it.time / 1000) })
                .distinctBy { it.entryName }

            // Base: by conventional name first, then the first APK whose manifest is not a split.
            val ordered = apkEntries.sortedBy { entry ->
                val name = entry.name.substringAfterLast('/').lowercase()
                when {
                    name == "base.apk" || name == "base-master.apk" -> 0
                    configPattern.matches(name) || name.startsWith("split_") -> 2
                    else -> 1
                }
            }
            val temp = Files.createTempDirectory("apk-uploader-bundle").toFile()
            try {
                var baseEntry: ZipEntry? = null
                var info: ApkInfo? = null
                for (entry in ordered) {
                    val extracted = File(temp, "candidate.apk")
                    zip.getInputStream(entry).use { input -> extracted.outputStream().use { input.copyTo(it) } }
                    val isSplit = ApkModule.loadApkFile(extracted).use { module -> module.androidManifest?.isSplit ?: false }
                    if (!isSplit) {
                        baseEntry = entry
                        info = ApkReader.read(extracted)
                        break
                    }
                    extracted.delete()
                }
                val base = baseEntry ?: throw IOException("${file.name} has no base APK (only splits)")
                val baseInfo = info!!
                val apks = apkEntries.map { entry ->
                    val fileName = entry.name.substringAfterLast('/')
                    BundleApk(entry.name, entry.size, entry === base, configPattern.matchEntire(fileName)?.groupValues?.get(1)?.lowercase())
                }
                return SplitBundle(
                    file, format, baseInfo.packageName, baseInfo.versionName, baseInfo.versionCode, baseInfo.label,
                    baseInfo.minSdk, baseInfo.icon, apks, obbs,
                )
            } finally {
                temp.deleteRecursively()
            }
        }
    }

    /** XAPK manifest.json expansions with an explicit install path. */
    private fun readExpansions(zip: ZipFile, entries: List<ZipEntry>): List<BundleObb> {
        val manifest = entries.firstOrNull { it.name == "manifest.json" } ?: return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(zip.getInputStream(manifest).use { it.readBytes() }.decodeToString()).jsonObject
            root["expansions"]?.jsonArray.orEmpty().mapNotNull { element ->
                val expansion = element.jsonObject
                val entryName = expansion["file"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val installPath = expansion["install_path"]?.jsonPrimitive?.content ?: entryName
                val entry = zip.getEntry(entryName) ?: return@mapNotNull null
                val remote = "/sdcard/" + installPath.replace('\\', '/').trimStart('/')
                if (!remote.startsWith("/sdcard/Android/")) return@mapNotNull null
                BundleObb(entryName, remote, entry.size, entry.time / 1000)
            }
        }.getOrDefault(emptyList())
    }
}
