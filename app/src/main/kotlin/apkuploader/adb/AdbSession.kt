package apkuploader.adb

import apkuploader.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class ServerStatus {
    object Starting : ServerStatus()
    data class Running(val protocolVersion: Int, val binary: File?, val startedHere: Boolean) : ServerStatus()
    data class Failed(val message: String) : ServerStatus()
}

/** Keeps the adb server running and tracks the device list. */
class AdbSession(private val scope: CoroutineScope, private val adbPath: () -> String) {
    val client = AdbClient()

    private val statusFlow = MutableStateFlow<ServerStatus>(ServerStatus.Starting)
    val status: StateFlow<ServerStatus> = statusFlow

    private val devicesFlow = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = devicesFlow

    @Volatile
    private var startedHere = false

    @Volatile
    private var binary: File? = null

    /** Set before this app stops the server itself, so the tracking loop does not report an error. */
    @Volatile
    private var expectedStop = false
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            try {
                ensureServer()
                client.trackDevices { list ->
                    devicesFlow.value = list
                }
                AppLog.warn("adb device tracking ended; reconnecting")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (expectedStop) {
                    expectedStop = false
                    AppLog.info("adb server stopped")
                    statusFlow.value = ServerStatus.Starting
                } else {
                    AppLog.error("adb server: ${e.message}")
                    statusFlow.value = ServerStatus.Failed(e.message ?: "adb server is unavailable")
                }
            }
            devicesFlow.value = emptyList()
            delay(2000)
        }
    }

    private fun ensureServer() {
        if (!client.isServerRunning()) {
            statusFlow.value = ServerStatus.Starting
            val found = AdbServer.locate(adbPath())
            binary = found
            AdbServer.start(found)
            startedHere = true
            if (!client.isServerRunning()) error("adb server did not answer after start.")
        } else if (binary == null) {
            binary = runCatching { AdbServer.locate(adbPath()) }.getOrNull()
        }
        statusFlow.value = ServerStatus.Running(client.version(), binary, startedHere)
    }

    /** Kills the server; the tracking loop restarts it. */
    suspend fun restart() = withContext(Dispatchers.IO) {
        AppLog.info("Restarting adb server")
        expectedStop = true
        client.kill()
        startedHere = false
    }

    /** Stops the server if this app started it, so no adb.exe keeps the app directory busy. */
    fun shutdown() {
        expectedStop = true
        job?.cancel()
        if (startedHere) {
            client.kill()
            startedHere = false
        }
    }
}
