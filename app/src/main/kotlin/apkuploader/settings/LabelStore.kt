package apkuploader.settings

import androidx.compose.runtime.mutableStateMapOf
import apkuploader.AppDirs
import apkuploader.AppLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Remembers the display label of every package installed through this app (adb cannot read labels). */
class LabelStore(private val file: File = File(AppDirs.root, "labels.json")) {
    private val json = Json { prettyPrint = true }
    private val labels = mutableStateMapOf<String, String>().apply { putAll(load()) }

    operator fun get(packageName: String): String? = labels[packageName]

    fun put(packageName: String, label: String) {
        if (label.isBlank() || labels[packageName] == label) return
        labels[packageName] = label
        try {
            file.parentFile.mkdirs()
            file.writeText(json.encodeToString(labels.toSortedMap().toMap()))
        } catch (e: Exception) {
            AppLog.warn("Cannot save labels to ${file.path}: ${e.message}")
        }
    }

    private fun load(): Map<String, String> = try {
        if (file.isFile) json.decodeFromString<Map<String, String>>(file.readText()) else emptyMap()
    } catch (e: Exception) {
        AppLog.warn("Cannot read labels from ${file.path}: ${e.message}")
        emptyMap()
    }
}
