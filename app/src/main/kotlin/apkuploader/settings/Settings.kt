package apkuploader.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import apkuploader.AppDirs
import apkuploader.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Settings(
    val wifiAddress: String = "",
    val pairingAddress: String = "",
    val preferredSerial: String = "",
    val reinstall: Boolean = true,
    val allowDowngrade: Boolean = false,
    val grantPermissions: Boolean = false,
    val verifyAfterPush: Boolean = true,
    val notifications: Boolean = true,
    val adbPath: String = "",
)

class SettingsStore(private val file: File = AppDirs.settingsFile) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    var value by mutableStateOf(load())
        private set

    fun update(change: Settings.() -> Settings) {
        value = value.change()
        try {
            file.parentFile.mkdirs()
            file.writeText(json.encodeToString(value))
        } catch (e: Exception) {
            AppLog.error("Cannot save settings to ${file.path}", e)
        }
    }

    private fun load(): Settings = try {
        if (file.isFile) json.decodeFromString(file.readText()) else Settings()
    } catch (e: Exception) {
        AppLog.error("Cannot read settings from ${file.path}; using defaults", e)
        Settings()
    }
}
