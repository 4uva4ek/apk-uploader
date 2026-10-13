package apkuploader.cache

import apkuploader.apk.ApkInfo
import apkuploader.apk.ApkReader
import java.io.File

class DropResult(val apks: List<ApkInfo>, val caches: List<CachePlan>, val errors: List<String>)

/** Turns dropped files and folders into APKs to install and cache folders to copy. */
object DropAnalyzer {
    fun analyze(paths: List<File>): DropResult {
        val apkFiles = mutableListOf<File>()
        val folders = mutableListOf<File>()
        val errors = mutableListOf<String>()
        for (path in paths) {
            when {
                path.isDirectory -> {
                    folders += path
                    apkFiles += path.listFiles()?.filter { it.isFile && it.name.endsWith(".apk", true) }.orEmpty().sortedBy { it.name }
                }
                path.isFile && path.name.endsWith(".apk", true) -> apkFiles += path
                path.isFile && Regex(".*\\.(xapk|apks|apkm)$", RegexOption.IGNORE_CASE).matches(path.name) ->
                    errors += "${path.name}: split packages are not supported yet. Extract the APK and OBB files first."
                path.isFile -> errors += "${path.name}: drop APK files or cache folders."
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
        val hints = apks.map { it.packageName }.distinct()
        val caches = folders.flatMap { CacheLayout.analyze(it, hints) }.filter { it.files.isNotEmpty() }
        for (folder in folders) {
            if (caches.none { it.root == folder || it.root.startsWith(folder) } && apkFiles.none { it.parentFile == folder }) {
                errors += "${folder.name}: the folder has no files to copy."
            }
        }
        return DropResult(apks, caches, errors)
    }
}
