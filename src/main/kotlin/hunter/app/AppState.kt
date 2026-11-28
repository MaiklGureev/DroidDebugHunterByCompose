package hunter.app

import androidx.compose.runtime.*
import hunter.core.AppFiles
import hunter.core.ApplicationLog
import hunter.core.tr
import hunter.features.connection.ConnectionViewModel
import hunter.features.connection.FridaNext
import hunter.features.help.HelpViewModel
import hunter.features.logcat.LogcatViewModel
import hunter.features.settings.Settings
import hunter.features.settings.SettingsViewModel
import hunter.features.tools.ToolSetupViewModel
import hunter.features.traffic.CaptureMode
import hunter.features.traffic.TrafficViewModel
import java.io.File
import kotlinx.coroutines.*

enum class Page(private val labelKey: String, private val captionKey: String) {
    Connection("Connection", "Android device and traffic capture"),
    Traffic("Traffic", "HTTP requests and responses"),
    Devices("Devices", "Device diagnostics"),
    Apps("Apps", "Processes, permissions and data"),
    Logcat("Logcat", "Live Android logs"),
    Tools("ADB tools", "Device network and diagnostics"),
    Settings("Settings", "Language and behavior"),
    Help("Help", "Setup and user guide");

    val label
        get() = tr(labelKey)

    val caption
        get() = tr(captionKey)
}

data class OutputWindow(val id: Long = System.nanoTime(), val title: String, val text: String)

data class Confirmation(val title: String, val text: String, val run: () -> Unit)

class AppState(
    initialSettings: Settings,
    val applicationLog: ApplicationLog = ApplicationLog(AppFiles.applicationLog),
) {
    private val coroutineErrors = CoroutineExceptionHandler { _, failure ->
        applicationLog.error("coroutine", "Unhandled coroutine failure", failure)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + applicationLog + coroutineErrors)
    val settings =
        SettingsViewModel(
            initial = initialSettings,
            scope = scope,
            onChanged = ::onSettingsChanged,
            showStatus = { status = it },
            showError = { error = it },
        )
    val help = HelpViewModel(
        scope, initialSettings.language, File(AppFiles.runtime, "help"),
        showError = { error = it },
    )

    var initialized by mutableStateOf(false)
        private set

    private var currentPage by mutableStateOf(Page.Connection)
    var page: Page
        get() = currentPage
        set(value) {
            if (value == currentPage) return
            currentPage = value
            applicationLog.info("navigation", value.name)
        }
    var palette by mutableStateOf(false)
    private var currentStatus by mutableStateOf(tr("Preparing local resources…"))
    var status: String
        get() = currentStatus
        set(value) {
            if (currentStatus == value) return
            currentStatus = value
            if (value.isNotBlank()) applicationLog.info("status", value)
        }
    private var currentError by mutableStateOf("")
    var error: String
        get() = currentError
        set(value) {
            currentError = value
            if (value.isNotBlank()) applicationLog.error("application", value)
        }
    var busy by mutableStateOf("")
        private set

    val connection: ConnectionViewModel =
        ConnectionViewModel(
            settings = { settings.uiState.value },
            saveSettings = settings::replace,
            runTask = ::task,
            stopStreams = ::stopStreams,
            stopCapture = { traffic.stop() },
            isCapturing = { capturing },
            confirm = { title, text, action -> confirmation = Confirmation(title, text, action) },
            report = ::report,
            showStatus = { status = it },
            showError = { error = it },
            showOutput = { title, text -> outputs += OutputWindow(title = title, text = text) },
        )

    val traffic: TrafficViewModel =
        TrafficViewModel(
            scope = scope,
            settings = { settings.uiState.value },
            appName = { packageName ->
                connection.uiState.value.apps.firstOrNull { it.packageName == packageName }?.name.orEmpty()
            },
            runTask = ::task,
            report = ::report,
            showStatus = { status = it },
            showError = { error = it },
            showTraffic = { page = Page.Traffic },
        )

    val logcat =
        LogcatViewModel(
            scope = scope,
            settings = { settings.uiState.value },
            serial = connection::serial,
            selectedApp = { connection.uiState.value.selectedApp },
            runTask = ::task,
            report = ::report,
            showStatus = { status = it },
        )

    private val capturing get() = traffic.uiState.value.running
    private val logging get() = logcat.uiState.value.running

    val toolSetup =
        ToolSetupViewModel(
            settings = { settings.uiState.value },
            saveSettings = settings::replace,
            runTask = ::task,
            cancelTask = ::cancelOperation,
            beforePrepare = {
                check(!capturing && !logging && !connection.uiState.value.ownsFridaServer) {
                    tr("Stop capture, Logcat and the owned Frida server before preparing tools.")
                }
                connection.invalidateDiagnostics()
            },
            refreshDevices = { connection.refreshDevices(showError = true) },
            report = ::report,
            showStatus = { status = it },
            showOutput = { title, text -> outputs += OutputWindow(title = title, text = text) },
        )

    var confirmation by mutableStateOf<Confirmation?>(null)
    val journal = mutableStateListOf<String>()
    val outputs = mutableStateListOf<OutputWindow>()
    private var operation: Job? = null

    init {
        applicationLog.start(scope) { lines ->
            withContext(Dispatchers.Main.immediate) {
                journal.addAll(lines)
                if (journal.size > 2000) journal.subList(0, journal.size - 2000).clear()
            }
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    applicationLog.info("lifecycle", StartupDiagnostics.summary(initialSettings))
                    AppFiles.extract()
                }
                help.onBundledFilesReady()
                toolSetup.initializeFolders()
                initialized = true
                status = tr("Ready. Connect Android or import a HAR file.")
                connection.refreshDevices(showError = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentError = tr("Resource preparation: {0}", e.message)
                applicationLog.error("startup", currentError, e)
            }
        }
        scope.launch { connection.autoRefresh { initialized && busy.isEmpty() } }
    }

    fun report(message: String) {
        applicationLog.info("diagnostic", message)
    }

    private fun onSettingsChanged(value: Settings) {
        help.setLanguage(value.language)
        connection.invalidateDiagnostics()
        toolSetup.onSettingsChanged(value)
    }

    fun task(title: String, block: suspend () -> Unit) = runOperation(title, logLifecycle = true, block)

    private fun runOperation(title: String, logLifecycle: Boolean, block: suspend () -> Unit) {
        if (busy.isNotEmpty()) return
        busy = title
        error = ""
        operation =
            scope.launch {
                if (logLifecycle) applicationLog.info("operation", "Started · $title")
                try {
                    block()
                    if (status == title) status = tr("Done")
                    if (logLifecycle) applicationLog.info("operation", "Completed · $title")
                } catch (e: CancellationException) {
                    currentStatus = tr("Operation cancelled")
                    if (logLifecycle) applicationLog.info("operation", "Cancelled · $title")
                } catch (e: Exception) {
                    currentError = e.message ?: e.toString()
                    applicationLog.error("operation", title, e)
                } finally {
                    busy = ""
                    operation = null
                }
            }
    }

    fun exportApplicationLog(file: File) = task(tr("Exporting application log")) {
        applicationLog.export(file)
        status = tr("Saved: {0}", file.absolutePath)
    }

    fun confirmClearApplicationLog() {
        confirmation = Confirmation(
            tr("Clear application log?"),
            tr("This deletes the application log and its previous file."),
        ) {
            runOperation(tr("Clearing application log"), logLifecycle = false) {
                applicationLog.clear { withContext(Dispatchers.Main.immediate) { journal.clear() } }
                currentStatus = tr("Application log cleared")
            }
        }
    }

    fun cancelOperation() {
        operation?.cancel()
    }

    private suspend fun stopStreams() {
        traffic.stop()
        logcat.stop()
    }

    fun checkCaptureSetup() = task(tr("Checking capture readiness")) {
        toolSetup.refreshFindings()
        connection.checkCapture()
    }

    fun toggleCapture() =
        task(if (capturing) tr("Stopping capture") else tr("Starting capture")) {
            if (capturing) {
                traffic.stop()
                status = tr("Capture stopped")
                return@task
            }
            val (config, selectedDevice, app, mode) = connection.captureTarget()
            val device = selectedDevice?.takeIf { it.state == "device" }
            if (device == null) {
                page = Page.Connection
                status = tr("Select an authorized Android device")
                return@task
            }
            if (mode.capturesSingleApp && app == null) {
                page = Page.Traffic
                status = tr("Select an app")
                return@task
            }
            if (mode.isFrida && connection.uiState.value.fridaDiagnosis?.next != FridaNext.Ready &&
                connection.refreshFridaDiagnosis()?.next != FridaNext.Ready) {
                page = Page.Connection
                return@task
            }
            try {
                traffic.start(config, device.serial, mode, app)
                status = tr("{0}: capture active", mode.title)
            } catch (e: Exception) {
                if (mode.isFrida) connection.invalidateDiagnostics()
                throw e
            }
        }

    fun saveText(file: File, text: String) =
        task(tr("Saving file")) {
            withContext(Dispatchers.IO) {
                AppFiles.atomicWrite(file, text.toByteArray(Charsets.UTF_8))
            }
            status = tr("Saved: {0}", file.absolutePath)
        }

    suspend fun shutdown() {
        applicationLog.info("lifecycle", "Shutdown requested")
        try {
            operation?.cancelAndJoin()
            stopStreams()
            withContext(NonCancellable) {
                try {
                    connection.stopFridaOwned()
                } catch (e: Exception) {
                    applicationLog.error("frida", "Failed to stop the owned server", e)
                }
                settings.flush()
            }
        } catch (failure: Exception) {
            if (failure !is CancellationException) applicationLog.error("shutdown", "Shutdown failed", failure)
            throw failure
        } finally {
            withContext(NonCancellable) {
                applicationLog.info("lifecycle", "Shutdown finished")
                try {
                    applicationLog.close()
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}
