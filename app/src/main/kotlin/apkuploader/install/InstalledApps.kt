package apkuploader.install

import apkuploader.adb.AdbClient

/** A third-party package on the device with the size of its OBB and data folders on /sdcard. */
data class InstalledApp(val packageName: String, val versionName: String?, val versionCode: Long?, val obbKb: Long, val dataKb: Long) {
    val obbRoot: String get() = "/sdcard/Android/obb/$packageName"
    val dataRoot: String get() = "/sdcard/Android/data/$packageName"
    val hasCache: Boolean get() = obbKb > 0 || dataKb > 0
    val version: String get() = listOfNotNull(versionName, versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "?" }
}

/** Lists user-installed packages (`pm list packages -3`); system apps are never listed. */
object InstalledApps {
    private const val BATCH = 25

    fun list(client: AdbClient, serial: String, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<InstalledApp> {
        val codes = parsePackageList(client.shell(serial, "pm list packages -3 --show-versioncode", 120_000).stdout)
        if (codes.isEmpty()) return emptyList()
        val packages = codes.keys.sorted()
        val result = mutableListOf<InstalledApp>()
        packages.chunked(BATCH).forEachIndexed { index, batch ->
            val names = batch.joinToString(" ") { AdbClient.quote(it) }
            val script = "for p in $names; do " +
                "v=${'$'}(dumpsys package \"${'$'}p\" 2>/dev/null | grep -m1 'versionName=' | sed 's/.*versionName=//'); " +
                "o=${'$'}(du -sk \"/sdcard/Android/obb/${'$'}p\" 2>/dev/null | cut -f1); " +
                "d=${'$'}(du -sk \"/sdcard/Android/data/${'$'}p\" 2>/dev/null | cut -f1); " +
                "echo \"${'$'}p|${'$'}v|${'$'}{o:-0}|${'$'}{d:-0}\"; done"
            result += parseDetails(client.shell(serial, script, 300_000).stdout, codes)
            progress(minOf((index + 1) * BATCH, packages.size), packages.size)
        }
        val seen = result.map { it.packageName }.toSet()
        packages.filter { it !in seen }.forEach { result += InstalledApp(it, null, codes[it], 0, 0) }
        return result.sortedBy { it.packageName }
    }

    /** `package:com.x versionCode:123` (or just `package:com.x`) per line. */
    fun parsePackageList(text: String): Map<String, Long?> = text.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith("package:")) return@mapNotNull null
        val body = trimmed.removePrefix("package:")
        val name = body.substringBefore(' ').trim()
        if (name.isEmpty()) return@mapNotNull null
        val code = Regex("versionCode:(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
        name to code
    }.toMap()

    /** `pkg|versionName|obbKb|dataKb` per line, as produced by the device-side loop. */
    fun parseDetails(text: String, codes: Map<String, Long?>): List<InstalledApp> = text.lines().mapNotNull { line ->
        val parts = line.trim().split('|')
        if (parts.size < 4 || parts[0].isEmpty()) return@mapNotNull null
        InstalledApp(
            packageName = parts[0],
            versionName = parts[1].trim().ifEmpty { null },
            versionCode = codes[parts[0]],
            obbKb = parts[2].trim().toLongOrNull() ?: 0,
            dataKb = parts[3].trim().toLongOrNull() ?: 0,
        )
    }
}
