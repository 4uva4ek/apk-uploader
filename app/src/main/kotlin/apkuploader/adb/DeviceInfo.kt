package apkuploader.adb

/** One line of `adb devices -l`. */
data class DeviceInfo(
    val serial: String,
    val state: String,
    val model: String? = null,
    val product: String? = null,
    val transportId: String? = null,
) {
    val isWifi: Boolean get() = serial.contains(':') || serial.startsWith("adb-")
    val isReady: Boolean get() = state == "device"
    val isUnauthorized: Boolean get() = state == "unauthorized"
    val displayName: String get() = model?.replace('_', ' ') ?: serial
    val transportLabel: String get() = if (isWifi) "Wi-Fi" else "USB"

    companion object {
        fun parseList(text: String): List<DeviceInfo> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val attributes = parts.drop(2).mapNotNull {
                    val index = it.indexOf(':')
                    if (index > 0) it.substring(0, index) to it.substring(index + 1) else null
                }.toMap()
                DeviceInfo(parts[0], parts[1], attributes["model"], attributes["product"], attributes["transport_id"])
            }
            .toList()
    }
}

data class DeviceDetails(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdk: Int?,
    val freeBytes: Long?,
) {
    val summary: String
        get() = buildString {
            append(manufacturer.replaceFirstChar { it.uppercase() }).append(' ').append(model)
            append(" · Android ").append(androidVersion)
            sdk?.let { append(" (API ").append(it).append(')') }
            freeBytes?.let { append(" · ").append(formatBytes(it)).append(" free") }
        }
}

fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
}
