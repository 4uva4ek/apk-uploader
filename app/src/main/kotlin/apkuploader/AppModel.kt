package apkuploader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import apkuploader.adb.AdbSession
import apkuploader.adb.DeviceDetails
import apkuploader.adb.DeviceInfo
import apkuploader.adb.DeviceQueries
import apkuploader.adb.ServerStatus
import apkuploader.cache.DropAnalyzer
import apkuploader.install.InstallQueue
import apkuploader.install.QueueItem
import apkuploader.install.QueueListener
import apkuploader.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Application state shared by the window, the tray and the background workers. */
class AppModel(private val scope: CoroutineScope) : QueueListener {
    val settings = SettingsStore()
    val session = AdbSession(scope) { settings.value.adbPath }

    /** Serial of the device the queue installs to; null while no ready device is selected. */
    val selectedSerial = MutableStateFlow<String?>(null)

    var devices by mutableStateOf<List<DeviceInfo>>(emptyList())
        private set
    var serverStatus by mutableStateOf<ServerStatus>(ServerStatus.Starting)
        private set
    var selected by mutableStateOf<DeviceInfo?>(null)
        private set
    var details by mutableStateOf<DeviceDetails?>(null)
        private set

    val queue = InstallQueue(scope, session.client, selectedSerial, { settings.value }, this)
    val inputRequests = mutableStateListOf<QueueItem.Cache>()
    var errorMessage by mutableStateOf<Pair<String, String>?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Recent log lines for the window's log panel. */
    val logLines = mutableStateListOf<String>()

    /** Set by the window to show system notifications. */
    var notifier: ((title: String, message: String, success: Boolean) -> Unit)? = null

    fun start() {
        AppLog.listener = { line ->
            scope.launch {
                logLines += line
                if (logLines.size > LOG_LIMIT) logLines.removeRange(0, logLines.size - LOG_LIMIT)
            }
        }
        AppLog.info("${BuildInfo.APP_NAME} ${BuildInfo.VERSION} starting; data in ${AppDirs.root}")
        session.start()
        scope.launch { session.devices.collect { list -> devices = list; reselect(list) } }
        scope.launch { session.status.collect { serverStatus = it } }
    }

    /**
     * Keeps the device the user picked (settings.preferredSerial) while it is listed, even when
     * it is not ready yet; otherwise the first ready device, otherwise any listed device.
     */
    private fun reselect(list: List<DeviceInfo>) {
        val preferred = settings.value.preferredSerial
        val choice = list.firstOrNull { it.serial == preferred }
            ?: list.firstOrNull { it.serial == selectedSerial.value && it.isReady }
            ?: list.firstOrNull { it.isReady }
            ?: list.firstOrNull()
        selected = choice
        val readySerial = choice?.takeIf { it.isReady }?.serial
        if (selectedSerial.value == readySerial) return
        val previous = selectedSerial.value
        selectedSerial.value = readySerial
        details = null
        if (readySerial != null && choice != null) {
            AppLog.info("Device ready: ${choice.serial} (${choice.displayName}, ${choice.transportLabel})")
            refreshDetails(readySerial)
            notify("Headset connected", "${choice.displayName} via ${choice.transportLabel}", true)
            queue.ensureWorker()
        } else if (previous != null) {
            AppLog.info("Device gone: $previous")
            notify("Headset disconnected", previous, false)
        }
    }

    private fun refreshDetails(serial: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { DeviceQueries.details(session.client, serial) }
                .onSuccess { if (selectedSerial.value == serial) details = it }
                .onFailure { AppLog.warn("Cannot read device details: ${it.message}") }
        }
    }

    /** Explicit choice from the device list; remembered across restarts. */
    fun select(device: DeviceInfo) {
        settings.update { copy(preferredSerial = device.serial) }
        AppLog.info("Selected device ${device.serial} (${device.displayName}, ${device.transportLabel}, ${device.state})")
        reselect(devices)
    }

    fun addPaths(paths: List<File>) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { DropAnalyzer.analyze(paths) }
            if (result.errors.isNotEmpty()) {
                errorMessage = "Some items were skipped" to result.errors.joinToString("\n")
            }
            if (result.count > 0) {
                queue.add(result.apks, result.bundles, result.caches)
                messages.tryEmit(if (result.count == 1) "Added 1 item to the queue" else "Added ${result.count} items to the queue")
                result.apks.forEach { AppLog.info("Queued APK ${it.file.name}: ${it.packageName} ${it.versionName} (${it.versionCode})") }
                result.bundles.forEach { AppLog.info("Queued ${it.format} ${it.file.name}: ${it.packageName} ${it.versionName} (${it.versionCode}), ${it.apks.size} APK(s), ${it.obbs.size} OBB") }
                result.caches.forEach { AppLog.info("Queued cache ${it.root.path}: ${it.kind} ${it.packageName ?: "?"} ${it.files.size} files") }
            }
        }
    }

    fun uninstall(item: QueueItem.Installable) {
        AppLog.info("Queued uninstall of ${item.packageName}")
        queue.addUninstall(item.packageName, item.title)
    }

    fun removeCache(item: QueueItem) {
        when (item) {
            is QueueItem.Cache -> queue.addRemoveCache(item)
            is QueueItem.Bundle -> queue.addRemoveBundleObbs(item)
            else -> return
        }
        AppLog.info("Queued cache removal for ${item.title}")
    }

    suspend fun connectWifi(address: String): String = withContext(Dispatchers.IO) {
        val trimmed = address.trim().let { if (it.contains(':')) it else "$it:5555" }
        settings.update { copy(wifiAddress = trimmed) }
        AppLog.info("adb connect $trimmed")
        session.client.connect(trimmed)
    }

    suspend fun disconnectWifi(address: String): String = withContext(Dispatchers.IO) { session.client.disconnect(address) }

    suspend fun pair(address: String, code: String): String = withContext(Dispatchers.IO) {
        settings.update { copy(pairingAddress = address.trim()) }
        AppLog.info("adb pair ${address.trim()}")
        session.client.pair(address.trim(), code.trim())
    }

    /** Enables adbd TCP mode on a USB device (until its next reboot) and connects over Wi-Fi. */
    suspend fun switchToWifi(device: DeviceInfo): String = withContext(Dispatchers.IO) {
        val ip = DeviceQueries.wifiAddress(session.client, device.serial)
            ?: error("The headset reports no Wi-Fi address on wlan0. Connect it to Wi-Fi first.")
        AppLog.info("adb tcpip 5555 on ${device.serial}; address $ip")
        session.client.tcpip(device.serial, 5555)
        var last = ""
        repeat(6) {
            delay(1500)
            last = runCatching { session.client.connect("$ip:5555") }.getOrElse { it.message ?: "connect failed" }
            if (last.contains("connected", true) && !last.contains("failed", true)) {
                settings.update { copy(wifiAddress = "$ip:5555") }
                return@withContext last
            }
        }
        last
    }

    suspend fun restartServer() = session.restart()

    override fun notify(title: String, message: String, success: Boolean) {
        if (settings.value.notifications) notifier?.invoke(title, message, success)
    }

    override fun needsInput(item: QueueItem.Cache) {
        if (inputRequests.none { it === item }) inputRequests += item
    }

    override fun log(message: String) = AppLog.info(message)

    fun shutdown() {
        queue.cancelCurrent()
        session.shutdown()
    }

    companion object {
        const val LOG_LIMIT = 400
    }
}
