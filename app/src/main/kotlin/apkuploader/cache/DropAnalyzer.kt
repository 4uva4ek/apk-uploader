package apkuploader.cache

import apkuploader.apk.ApkInfo
import apkuploader.apk.ApkReader
import apkuploader.apk.BundleReader
import apkuploader.apk.SplitBundle
import java.io.File

class DropResult(val apks: List<ApkInfo>, val bundles: List<SplitBundle>, val caches: List<CachePlan>, val errors: List<String>) {
    val count: Int get() = apks.size + bundles.size + caches.size
}

/** Turns dropped files and folders into APKs and split packages to install and cache folders to copy. */
object DropAnalyzer {
    fun analyze(paths: List<File>): DropResult {
        val apkFiles = mutableListOf<File>()
        val bundleFiles = mutableListOf<File>()
        val folders = mutableListOf<File>()
        val errors = mutableListOf<String>()
        for (path in paths) {
            when {
                path.isDirectory -> {
                    folders += path
                    val inside = path.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }
                    apkFiles += inside.filter { it.name.endsWith(".apk", true) }
                    bundleFiles += inside.filter { BundleReader.isBundle(it) }
                }
                path.isFile && path.name.endsWith(".apk", true) -> apkFiles += path
                path.isFile && BundleReader.isBundle(path) -> bundleFiles += path
                path.isFile -> errors += "${path.name}: drop APK, XAPK/APKS/APKM files or cache folders."
                else -> errors += "${path.name}: not found."
            }
        }
        val apks = apkFiles.distinct().mapNotNull { file ->
            try {
                ApkReader.read(file)
            } catch (e: Exception) {
                errors += "${file.name}: cannot read APK (${e.message ?: e.javaClass.simpleName})."
                null
            }
        }
        val bundles = bundleFiles.distinct().mapNotNull { file ->
            try {
                BundleReader.read(file)
            } catch (e: Exception) {
                errors += "${file.name}: cannot read split package (${e.message ?: e.javaClass.simpleName})."
                null
            }
        }
        val hints = (apks.map { it.packageName } + bundles.map { it.packageName }).distinct()
        val caches = folders.flatMap { CacheLayout.analyze(it, hints) }.filter { it.files.isNotEmpty() }
        for (folder in folders) {
            val hasPackages = apkFiles.any { it.parentFile == folder } || bundleFiles.any { it.parentFile == folder }
            if (!hasPackages && caches.none { it.root == folder || it.root.startsWith(folder) }) {
                errors += "${folder.name}: the folder has no files to copy."
            }
        }
        return DropResult(apks, bundles, caches, errors)
    }
}
