package apkuploader.adb

object DeviceQueries {
    fun details(client: AdbClient, serial: String): DeviceDetails {
        val result = client.shell(
            serial,
            "getprop ro.product.model; getprop ro.product.manufacturer; getprop ro.build.version.release; getprop ro.build.version.sdk",
        )
        val lines = result.stdout.lines().map { it.trim() }
        return DeviceDetails(
            model = lines.getOrNull(0).orEmpty().ifEmpty { "Android device" },
            manufacturer = lines.getOrNull(1).orEmpty(),
            androidVersion = lines.getOrNull(2).orEmpty().ifEmpty { "?" },
            sdk = lines.getOrNull(3)?.toIntOrNull(),
            freeBytes = freeSpace(client, serial),
        )
    }

    /** Free bytes on /data (apps, OBB and app data share it). */
    fun freeSpace(client: AdbClient, serial: String): Long? = try {
        parseDfAvailableKb(client.shell(serial, "df -k /data").stdout)?.times(1024)
    } catch (_: Exception) {
        null
    }

    /** `df -k /data` may list several bind mounts on Android 14; prefer the row mounted on /data. */
    fun parseDfAvailableKb(output: String): Long? {
        val rows = output.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("Filesystem") }
            .map { it.split(Regex("\\s+")) }.filter { it.size >= 6 }
        // Filesystem 1K-blocks Used Available Use% Mounted
        val row = rows.firstOrNull { it.last() == "/data" } ?: rows.lastOrNull() ?: return null
        return row[3].toLongOrNull()
    }

    /** Address of the device's wlan0 interface, for switching from USB to Wi-Fi. */
    fun wifiAddress(client: AdbClient, serial: String): String? {
        val output = client.shell(serial, "ip -4 addr show wlan0").stdout
        return Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(output)?.groupValues?.get(1)
    }
}
