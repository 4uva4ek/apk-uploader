package apkuploader.install

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import apkuploader.adb.AdbClient
import apkuploader.adb.AdbException
import apkuploader.adb.DeviceQueries
import apkuploader.adb.formatBytes
import apkuploader.apk.ApkInfo
import apkuploader.apk.SplitBundle
import apkuploader.cache.CacheKind
import apkuploader.cache.CachePlan
import apkuploader.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.Closeable

sealed class ItemState {
    object Queued : ItemState()
    object WaitingForDevice : ItemState()
    object NeedsInput : ItemState()
    data class Uploading(val sent: Long, val total: Long, val bytesPerSecond: Double) : ItemState() {
        val fraction: Float get() = if (total > 0) (sent.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
        val remainingSeconds: Long? get() = if (bytesPerSecond > 1) ((total - sent) / bytesPerSecond).toLong() else null
    }
    data class Working(val label: String) : ItemState()
    data class Done(val message: String) : ItemState()
    data class Failed(val message: String, val offerUninstall: Boolean = false) : ItemState()
    object Cancelled : ItemState()

    val isActive: Boolean get() = this is Uploading || this is Working
    val isFinished: Boolean get() = this is Done || this is Failed || this is Cancelled
}

sealed class QueueItem(val id: Int) {
    var state by mutableStateOf<ItemState>(ItemState.Queued)
    var note by mutableStateOf<String?>(null)
    abstract val title: String
    abstract val subtitle: String
    abstract val packageName: String?
    abstract val totalBytes: Long
    abstract val icon: ByteArray?

    /** Installable items: a plain APK or a split package. */
    sealed class Installable(id: Int) : QueueItem(id) {
        @Volatile
        var uninstallFirst = false
        abstract override val packageName: String
        abstract val versionName: String
        abstract val versionCode: Long
        abstract val fileName: String
    }

    class Apk(id: Int, val info: ApkInfo) : Installable(id) {
        override val title: String get() = info.label
        override val subtitle: String get() = "${info.packageName} · ${info.versionName} (${info.versionCode}) · ${formatBytes(info.size)}"
        override val packageName: String get() = info.packageName
        override val versionName: String get() = info.versionName
        override val versionCode: Long get() = info.versionCode
        override val fileName: String get() = info.file.name
        override val totalBytes: Long get() = info.size
        override val icon: ByteArray? get() = info.icon
    }

    class Bundle(id: Int, val bundle: SplitBundle) : Installable(id) {
        override val title: String get() = bundle.label
        override val subtitle: String
            get() = "${bundle.format} · ${bundle.packageName} · ${bundle.versionName} (${bundle.versionCode}) · base + ${bundle.splits.size} split(s)" +
                (if (bundle.obbs.isNotEmpty()) " · ${bundle.obbs.size} OBB" else "") + " · ${formatBytes(bundle.totalBytes)}"
        override val packageName: String get() = bundle.packageName
        override val versionName: String get() = bundle.versionName
        override val versionCode: Long get() = bundle.versionCode
        override val fileName: String get() = bundle.file.name
        override val totalBytes: Long get() = bundle.totalBytes
        override val icon: ByteArray? get() = bundle.icon
    }

    class Cache(id: Int, initial: CachePlan) : QueueItem(id) {
        var plan by mutableStateOf(initial)
        override val title: String get() = plan.root.name
        override val subtitle: String
            get() = "${plan.kind.label} · ${plan.files.size} file(s) · ${formatBytes(plan.totalBytes)} → " +
                (if (!plan.needsPackage) plan.remoteRoot("") else plan.packageName?.let { plan.remoteRoot(it) } ?: "choose target")
        override val packageName: String? get() = plan.packageName
        override val totalBytes: Long get() = plan.totalBytes
        override val icon: ByteArray? get() = null

        fun resolve(packageName: String, kind: CacheKind) {
            plan = plan.copy(packageName = packageName, kind = kind, confident = true)
            state = ItemState.Queued
        }
    }

    class Uninstall(id: Int, override val packageName: String, val label: String) : QueueItem(id) {
        override val title: String get() = "Uninstall $label"
        override val subtitle: String get() = "$packageName · app data is removed, /sdcard/Android folders are kept"
        override val totalBytes: Long get() = 0
        override val icon: ByteArray? get() = null
    }

    /** Removes a cache: a whole package folder under /sdcard/Android/{obb,data}, or listed files. */
    class RemoveCache(id: Int, val label: String, val root: String?, val files: List<String>) : QueueItem(id) {
        override val title: String get() = "Remove cache $label"
        override val subtitle: String get() = root ?: "${files.size} file(s) under ${files.firstOrNull()?.substringBeforeLast('/') ?: "/sdcard"}"
        override val packageName: String? get() = null
        override val totalBytes: Long get() = 0
        override val icon: ByteArray? get() = null
    }
}

interface QueueListener {
    fun notify(title: String, message: String, success: Boolean)
    fun needsInput(item: QueueItem.Cache)
    fun log(message: String)

    /** A package was installed through this app; remember its label. */
    fun installed(packageName: String, label: String) {}

    /** Packages or caches on the device changed; refresh any installed-apps listing. */
    fun packagesChanged() {}
}

/** Sequential install/copy worker for the selected device. */
class InstallQueue(
    private val scope: CoroutineScope,
    private val client: AdbClient,
    private val deviceSerial: StateFlow<String?>,
    private val settings: () -> Settings,
    private val listener: QueueListener,
) {
    val items = mutableStateListOf<QueueItem>()
    var current by mutableStateOf<QueueItem?>(null)
        private set
    private var nextId = 1
    private var worker: Job? = null

    @Volatile
    private var activeConnection: Closeable? = null

    @Volatile
    private var cancelRequested = false

    val isBusy: Boolean get() = current != null

    fun add(apks: List<ApkInfo>, bundles: List<SplitBundle>, caches: List<CachePlan>) {
        apks.forEach { items += QueueItem.Apk(nextId++, it) }
        bundles.forEach { items += QueueItem.Bundle(nextId++, it) }
        caches.forEach { items += QueueItem.Cache(nextId++, it) }
        ensureWorker()
    }

    fun addUninstall(packageName: String, label: String) {
        items += QueueItem.Uninstall(nextId++, packageName, label)
        ensureWorker()
    }

    fun addRemoveCache(item: QueueItem.Cache) {
        val plan = item.plan
        val packageName = plan.packageName ?: ""
        val removal = when (plan.kind) {
            CacheKind.OBB, CacheKind.DATA -> QueueItem.RemoveCache(nextId++, item.title, plan.remoteRoot(packageName), emptyList())
            else -> QueueItem.RemoveCache(nextId++, item.title, null, plan.files.map { plan.remotePath(packageName, it) })
        }
        items += removal
        ensureWorker()
    }

    fun addRemoveBundleObbs(item: QueueItem.Bundle) {
        items += QueueItem.RemoveCache(nextId++, "${item.title} OBB", null, item.bundle.obbs.map { it.remotePath })
        ensureWorker()
    }

    /** Removes a package folder under /sdcard/Android/obb or /sdcard/Android/data. */
    fun addRemoveCacheRoot(label: String, root: String) {
        items += QueueItem.RemoveCache(nextId++, label, root, emptyList())
        ensureWorker()
    }

    fun remove(item: QueueItem) {
        if (item !== current) items.remove(item)
    }

    fun clearFinished() {
        items.removeAll { it.state.isFinished && it !== current }
    }

    fun retry(item: QueueItem) {
        if (item.state.isFinished || item.state is ItemState.NeedsInput) {
            item.state = ItemState.Queued
            ensureWorker()
        }
    }

    fun uninstallAndRetry(item: QueueItem.Installable) {
        item.uninstallFirst = true
        retry(item)
    }

    fun cancelCurrent() {
        cancelRequested = true
        activeConnection?.let { runCatching { it.close() } }
    }

    fun ensureWorker() {
        if (worker?.isActive != true) worker = scope.launch(Dispatchers.IO) { run() }
    }

    private suspend fun run() {
        while (true) {
            val next = items.firstOrNull { it.state is ItemState.Queued } ?: break
            val serial = deviceSerial.value
            if (serial == null) {
                items.filter { it.state is ItemState.Queued }.forEach { it.state = ItemState.WaitingForDevice }
                deviceSerial.filterNotNull().first()
                items.filter { it.state is ItemState.WaitingForDevice }.forEach { it.state = ItemState.Queued }
                continue
            }
            current = next
            cancelRequested = false
            try {
                when (next) {
                    is QueueItem.Apk -> installApk(next, serial)
                    is QueueItem.Bundle -> installBundle(next, serial)
                    is QueueItem.Cache -> pushCache(next, serial)
                    is QueueItem.Uninstall -> uninstall(next, serial)
                    is QueueItem.RemoveCache -> removeCache(next, serial)
                }
            } catch (e: CancellationException) {
                next.state = ItemState.Cancelled
                throw e
            } catch (e: Throwable) {
                val message = e.message ?: e.javaClass.simpleName
                if (cancelRequested) {
                    next.state = ItemState.Cancelled
                    listener.log("${next.title}: cancelled")
                } else {
                    val friendly = friendlyError(message)
                    next.state = ItemState.Failed(friendly)
                    listener.log("${next.title}: $message")
                    listener.notify("Failed: ${next.title}", friendly, false)
                }
            } finally {
                activeConnection = null
                current = null
            }
        }
    }

    /** Uninstall-first handling, installed-version check and flags shared by APKs and bundles. */
    private fun prepareInstall(item: QueueItem.Installable, serial: String, installer: ApkInstaller): Pair<InstalledPackage?, InstallFlags>? {
        val packageName = item.packageName
        if (item.uninstallFirst) {
            item.state = ItemState.Working("Uninstalling the previous version…")
            val removed = installer.uninstall(serial, packageName)
            listener.log("$packageName uninstall: ${removed.message}")
            item.uninstallFirst = false
            if (installer.installed(serial, packageName) != null) {
                item.state = ItemState.Failed("Could not uninstall the previous version: ${removed.message}")
                return null
            }
        }
        item.state = ItemState.Working("Checking the installed version…")
        val installed = runCatching { installer.installed(serial, packageName) }.getOrNull()
        val configured = settings()
        var flags = InstallFlags(configured.reinstall, configured.allowDowngrade, configured.grantPermissions)
        item.note = when {
            installed == null -> null
            installed.versionCode != null && installed.versionCode > item.versionCode -> {
                flags = InstallFlags(flags.reinstall, true, flags.grantPermissions)
                "Installed ${installed.versionName ?: "?"} (${installed.versionCode}) is newer: this is a downgrade."
            }
            else -> "Updating installed ${installed.versionName ?: "?"} (${installed.versionCode ?: "?"})."
        }
        DeviceQueries.freeSpace(client, serial)?.let { free ->
            if (free < item.totalBytes * 2) item.note = listOfNotNull(item.note, "Low free space: ${formatBytes(free)}.").joinToString(" ")
        }
        return installed to flags
    }

    private fun finishInstall(item: QueueItem.Installable, installed: InstalledPackage?, result: InstallResult, extra: String = "") {
        if (result.success) {
            val replaced = installed?.versionName?.let { " (replaced $it)" } ?: ""
            item.state = ItemState.Done("Installed ${item.versionName}$replaced$extra")
            listener.log("${item.packageName} installed: ${item.versionName} (${item.versionCode})$extra")
            listener.installed(item.packageName, item.title)
            listener.packagesChanged()
            listener.notify("Installed", "${item.title} ${item.versionName}", true)
        } else {
            val friendly = friendlyError(result.message)
            item.state = ItemState.Failed(friendly, offerUninstall = result.needsUninstall)
            listener.log("${item.packageName} install failed: ${result.message}")
            listener.notify("Install failed", "${item.title}: $friendly", false)
        }
    }

    private fun installApk(item: QueueItem.Apk, serial: String) {
        val installer = ApkInstaller(client)
        val (installed, flags) = prepareInstall(item, serial, installer) ?: return
        val total = item.totalBytes
        val meter = SpeedMeter()
        item.state = ItemState.Uploading(0, total, 0.0)
        val result = installer.install(serial, item.info.file, flags, onConnection = { activeConnection = it }) { sent ->
            if (sent >= total) item.state = ItemState.Working("Installing on the headset…")
            else if (meter.shouldPublish()) item.state = ItemState.Uploading(sent, total, meter.rate(sent))
        }
        if (cancelRequested) {
            item.state = ItemState.Cancelled
            return
        }
        finishInstall(item, installed, result)
    }

    private fun installBundle(item: QueueItem.Bundle, serial: String) {
        val installer = ApkInstaller(client)
        val (installed, flags) = prepareInstall(item, serial, installer) ?: return
        val bundle = item.bundle
        val bundleInstaller = BundleInstaller(client)
        val abis = bundleInstaller.deviceAbis(serial)
        val apkTotal = bundle.select(abis).sumOf { it.size }
        val total = apkTotal + bundle.obbBytes
        val meter = SpeedMeter()
        item.state = ItemState.Uploading(0, total, 0.0)
        val result = bundleInstaller.install(serial, bundle, flags, abis, onConnection = { activeConnection = it }, log = { listener.log("${item.title}: $it") }) { sent ->
            if (sent >= apkTotal && bundle.obbs.isEmpty()) item.state = ItemState.Working("Installing on the headset…")
            else if (meter.shouldPublish()) item.state = ItemState.Uploading(sent, total, meter.rate(sent))
        }
        if (cancelRequested) {
            item.state = ItemState.Cancelled
            return
        }
        if (!result.success || bundle.obbs.isEmpty()) {
            finishInstall(item, installed, result)
            return
        }
        val verify = verifyMode()
        val pusher = CachePusher(client) { listener.log("${item.title}: $it") }
        val pushed = pusher.pushBundleObbs(serial, bundle, verify, onConnection = { activeConnection = it }) { done ->
            val sent = apkTotal + done
            if (sent >= total) item.state = ItemState.Working(if (verify != VerifyMode.NONE) "Verifying OBB…" else "Finishing…")
            else if (meter.shouldPublish()) item.state = ItemState.Uploading(sent, total, meter.rate(sent))
        }
        if (cancelRequested) {
            item.state = ItemState.Cancelled
            return
        }
        finishInstall(item, installed, result, " + ${pushed.copied + pushed.skipped} OBB file(s)")
    }

    private fun pushCache(item: QueueItem.Cache, serial: String) {
        val plan = item.plan
        val packageName = plan.packageName ?: if (plan.needsPackage) null else ""
        if (!plan.confident || packageName == null) {
            item.state = ItemState.NeedsInput
            listener.needsInput(item)
            return
        }
        val total = plan.totalBytes
        val meter = SpeedMeter()
        val verify = verifyMode()
        item.state = ItemState.Uploading(0, total, 0.0)
        val pusher = CachePusher(client) { listener.log("${item.title}: $it") }
        val result = pusher.push(serial, plan, packageName, verify, onConnection = { activeConnection = it }) { done ->
            if (done >= total) item.state = ItemState.Working(if (verify == VerifyMode.MD5) "Verifying (MD5)…" else if (verify == VerifyMode.SIZE) "Verifying…" else "Finishing…")
            else if (meter.shouldPublish()) item.state = ItemState.Uploading(done, total, meter.rate(done))
        }
        if (cancelRequested) {
            item.state = ItemState.Cancelled
            return
        }
        item.state = ItemState.Done(result.summary)
        listener.log("${item.title}: ${result.summary}")
        listener.notify("Cache copied", "${item.title} → ${result.remoteRoot}", true)
    }

    private fun uninstall(item: QueueItem.Uninstall, serial: String) {
        val installer = ApkInstaller(client)
        item.state = ItemState.Working("Uninstalling…")
        val result = installer.uninstall(serial, item.packageName)
        if (installer.installed(serial, item.packageName) == null) {
            item.state = ItemState.Done("Uninstalled ${item.packageName}")
            listener.log("${item.packageName} uninstalled")
            listener.packagesChanged()
            listener.notify("Uninstalled", item.label, true)
        } else {
            item.state = ItemState.Failed(friendlyError(result.message))
            listener.log("${item.packageName} uninstall failed: ${result.message}")
            listener.notify("Uninstall failed", "${item.label}: ${result.message}", false)
        }
    }

    private fun removeCache(item: QueueItem.RemoveCache, serial: String) {
        item.state = ItemState.Working("Removing…")
        val root = item.root
        if (root != null) {
            require(PACKAGE_DIR.matches(root)) { "Refusing to remove $root: not a package folder under /sdcard/Android." }
            val result = client.shell(serial, "rm -rf ${AdbClient.quote(root)}")
            if (client.shell(serial, "ls ${AdbClient.quote(root)}").ok) throw AdbException("Could not remove $root: ${result.output}")
            item.state = ItemState.Done("Removed $root")
        } else {
            val files = item.files.filter { it.startsWith("/sdcard/") && it.split('/').none { part -> part == ".." } }
            require(files.size == item.files.size) { "Refusing to remove paths outside /sdcard." }
            for (batch in files.chunked(40)) {
                val result = client.shell(serial, "rm -f " + batch.joinToString(" ") { AdbClient.quote(it) })
                if (!result.ok) throw AdbException("Could not remove files: ${result.output}")
            }
            files.map { it.substringBeforeLast('/') }.distinct().sortedByDescending { it.length }.forEach { dir ->
                if (PACKAGE_DIR.matches(dir)) client.shell(serial, "rmdir ${AdbClient.quote(dir)} 2>/dev/null")
            }
            item.state = ItemState.Done("Removed ${files.size} file(s)")
        }
        listener.log("Removed cache: ${item.subtitle}")
        listener.packagesChanged()
        listener.notify("Cache removed", item.label, true)
    }

    private fun verifyMode(): VerifyMode {
        val configured = settings()
        return when {
            configured.verifyMd5 -> VerifyMode.MD5
            configured.verifyAfterPush -> VerifyMode.SIZE
            else -> VerifyMode.NONE
        }
    }

    companion object {
        private val PACKAGE_DIR = Regex("^/sdcard/Android/(obb|data)/[A-Za-z][A-Za-z0-9_.]*$")

        private val EXPLANATIONS = listOf(
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "The APK is signed with a different key than the installed app. Uninstall the installed app first (its data is removed).",
            "INSTALL_FAILED_VERSION_DOWNGRADE" to "The installed version is newer. Uninstall it first to install this older APK.",
            "INSTALL_FAILED_ALREADY_EXISTS" to "The app is already installed. Enable reinstall in Settings or uninstall it first.",
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "Not enough free space on the headset.",
            "INSTALL_FAILED_OLDER_SDK" to "The APK requires a newer Android version than the headset has.",
            "INSTALL_FAILED_NEWER_SDK" to "The APK targets an older Android version than the headset allows.",
            "INSTALL_FAILED_NO_MATCHING_ABIS" to "The APK has no native libraries for this device (ARM64 is required).",
            "INSTALL_FAILED_USER_RESTRICTED" to "The headset blocked the installation. Confirm the prompt on the headset or allow installs via USB in developer options.",
            "INSTALL_FAILED_VERIFICATION_FAILURE" to "The headset's app verification rejected the APK.",
            "INSTALL_FAILED_TEST_ONLY" to "The APK is marked test-only.",
            "INSTALL_PARSE_FAILED" to "The APK is damaged or is not a valid Android package.",
            "INSTALL_FAILED_INVALID_APK" to "The APK is damaged or is not a valid Android package.",
            "INSTALL_FAILED_MISSING_SPLIT" to "This package needs additional split APKs that are not in the bundle.",
            "INSTALL_FAILED_BAD_SIGNATURE" to "The split APKs are not all signed with the same key.",
        )

        fun friendlyError(message: String): String {
            EXPLANATIONS.firstOrNull { message.contains(it.first) }?.let { return "${it.second} (${it.first})" }
            if (message.contains("Socket closed", true) || message.contains("connection closed", true) ||
                message.contains("device offline", true) || message.contains("device '", true) && message.contains("not found", true)
            ) return "The connection to the headset was lost. Reconnect and retry."
            if (message.contains("Permission denied", true)) return "The headset refused to write to that location (Permission denied)."
            return message
        }
    }
}

/** Throttles UI progress updates and smooths the transfer rate. */
class SpeedMeter(private val intervalMs: Long = 150) {
    private val start = System.nanoTime()
    private var lastPublish = 0L

    fun shouldPublish(): Boolean {
        val now = System.nanoTime()
        if (now - lastPublish < intervalMs * 1_000_000) return false
        lastPublish = now
        return true
    }

    fun rate(bytes: Long): Double {
        val seconds = (System.nanoTime() - start) / 1e9
        return if (seconds > 0.2) bytes / seconds else 0.0
    }
}
