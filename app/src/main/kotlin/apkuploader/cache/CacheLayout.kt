package apkuploader.cache

import java.io.File

enum class CacheKind(val label: String, val remoteRoot: String) {
    OBB("OBB cache", "/sdcard/Android/obb"),
    DATA("Data cache", "/sdcard/Android/data"),
    MIRROR("Android folder", "/sdcard/Android"),
    CUSTOM("install.txt", "/sdcard"),
}

/** A local file and its path relative to the plan root; [remote] overrides the computed destination. */
class CacheFile(val local: File, val relativePath: String, val remote: String? = null) {
    val size: Long get() = local.length()
}

/**
 * Where a dropped folder goes on the headset. [packageName] is null when it could not be
 * determined; [confident] is false when the user should confirm the target.
 */
data class CachePlan(
    val root: File,
    val kind: CacheKind,
    val packageName: String?,
    val confident: Boolean,
    val files: List<CacheFile>,
    val notes: List<String> = emptyList(),
) {
    val totalBytes: Long = files.sumOf { it.size }

    /** The plan needs no package name when every file carries its own destination. */
    val needsPackage: Boolean get() = kind != CacheKind.CUSTOM

    fun remoteRoot(packageName: String): String = when (kind) {
        CacheKind.MIRROR -> CacheKind.MIRROR.remoteRoot
        CacheKind.CUSTOM -> commonRemoteRoot()
        else -> "${kind.remoteRoot}/$packageName"
    }

    fun remotePath(packageName: String, file: CacheFile): String = file.remote ?: (remoteRoot(packageName) + "/" + file.relativePath)

    private fun commonRemoteRoot(): String {
        val parents = files.mapNotNull { it.remote?.substringBeforeLast('/') }
        if (parents.isEmpty()) return CacheKind.CUSTOM.remoteRoot
        var prefix = parents.first().split('/')
        for (parent in parents.drop(1)) {
            val parts = parent.split('/')
            prefix = prefix.zip(parts).takeWhile { it.first == it.second }.map { it.first }
        }
        return prefix.joinToString("/").ifEmpty { "/" }.let { if (it.length < "/sdcard".length) "/sdcard" else it }
    }
}

/** Detection rules for cache folders (documented in docs/CACHE-LAYOUT.md). */
object CacheLayout {
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val obbPattern = Regex("^(main|patch)\\.\\d+\\.(.+)\\.obb$", RegexOption.IGNORE_CASE)
    private val remotePackagePattern = Regex("^/sdcard/Android/(?:obb|data)/([^/]+)")
    private val junk = setOf("thumbs.db", "desktop.ini", ".ds_store", "install.txt")

    fun looksLikePackage(name: String): Boolean = packagePattern.matches(name)

    fun analyze(folder: File, packageHints: List<String>): List<CachePlan> {
        val notes = mutableListOf<String>()
        val hint = packageHints.distinct().singleOrNull()

        // 0. install.txt with push commands: explicit destinations.
        val script = File(folder, "install.txt")
        if (script.isFile) {
            val planned = InstallScript.plan(folder, InstallScript.parse(script.readText()))
            if (planned.files.isNotEmpty()) {
                val packageName = planned.files.firstNotNullOfOrNull { file ->
                    file.remote?.let { remotePackagePattern.find(it)?.groupValues?.get(1) }?.takeIf(::looksLikePackage)
                } ?: hint
                return listOf(CachePlan(folder, CacheKind.CUSTOM, packageName, true, planned.files, planned.notes))
            }
            notes += "install.txt has no usable push commands; the layout was detected from the folder instead."
            notes += planned.notes
        }

        // 1. An unpacked archive with Android/obb or Android/data inside, or the Android folder itself.
        val android = when {
            folder.name.equals("Android", true) && hasAndroidChildren(folder) -> folder
            File(folder, "Android").let { it.isDirectory && hasAndroidChildren(it) } -> File(folder, "Android")
            else -> null
        }
        if (android != null) {
            val packages = listOf("obb", "data").flatMap { child ->
                File(android, child).listFiles()?.filter { it.isDirectory && looksLikePackage(it.name) }?.map { it.name } ?: emptyList()
            }.distinct()
            if (packages.size > 1) notes += "Contains several packages: ${packages.joinToString()}"
            return listOf(CachePlan(folder, CacheKind.MIRROR, packages.firstOrNull() ?: hint ?: "android", true, collect(android), notes))
        }

        // 2. A folder named obb or data holding package folders.
        val namedKind = when (folder.name.lowercase()) {
            "obb" -> CacheKind.OBB
            "data" -> CacheKind.DATA
            else -> null
        }
        if (namedKind != null) {
            val packageDirs = folder.listFiles()?.filter { it.isDirectory && looksLikePackage(it.name) }.orEmpty()
            if (packageDirs.isNotEmpty()) return packageDirs.map { CachePlan(it, namedKind, it.name, true, collect(it), notes) }
        }

        // 3. OBB files (directly inside, or one level down such as Rookie's <pkg>/main.*.obb).
        val obbDirs = obbDirectories(folder)
        if (obbDirs.isNotEmpty()) {
            return obbDirs.map { dir ->
                val fromName = dir.listFiles()?.firstNotNullOfOrNull { file ->
                    obbPattern.matchEntire(file.name)?.groupValues?.get(2)?.takeIf(::looksLikePackage)
                }
                val packageName = fromName
                    ?: dir.name.takeIf(::looksLikePackage)
                    ?: folder.name.takeIf(::looksLikePackage)
                    ?: hint
                CachePlan(dir, CacheKind.OBB, packageName, packageName != null, collect(dir), notes)
            }
        }

        // 4. A folder named like a package: application data.
        if (looksLikePackage(folder.name)) return listOf(CachePlan(folder, CacheKind.DATA, folder.name, true, collect(folder), notes))

        // 5. Package-named subfolders.
        val packageDirs = folder.listFiles()?.filter { it.isDirectory && looksLikePackage(it.name) }.orEmpty()
        if (packageDirs.isNotEmpty()) return packageDirs.map { CachePlan(it, CacheKind.DATA, it.name, true, collect(it), notes) }

        // 6. Unknown layout: ask.
        notes += "The target folder could not be determined; confirm the package and location."
        return listOf(CachePlan(folder, CacheKind.DATA, hint, false, collect(folder), notes))
    }

    private fun hasAndroidChildren(dir: File) = File(dir, "obb").isDirectory || File(dir, "data").isDirectory

    private fun obbDirectories(folder: File): List<File> {
        fun hasObb(dir: File) = dir.listFiles()?.any { it.isFile && it.name.endsWith(".obb", true) } == true
        if (hasObb(folder)) return listOf(folder)
        return folder.listFiles()?.filter { it.isDirectory && hasObb(it) }.orEmpty().sortedBy { it.name }
    }

    /** All files under [root] (relative paths with '/'), without APKs and known junk. */
    fun collect(root: File): List<CacheFile> = root.walkTopDown()
        .filter { it.isFile && !it.name.endsWith(".apk", true) && it.name.lowercase() !in junk }
        .map { CacheFile(it, it.relativeTo(root).invariantSeparatorsPath) }
        .sortedBy { it.relativePath }
        .toList()
}
