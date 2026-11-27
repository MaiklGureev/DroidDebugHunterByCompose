package hunter

import androidx.compose.runtime.*
import com.google.gson.JsonObject
import java.io.File
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

data class CaptureCheck(val title: String, val ready: Boolean?, val detail: String)

private data class CaptureTarget(
    val config: Settings,
    val device: Device?,
    val app: AndroidApp?,
    val mode: CaptureMode,
)

class AppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var settings by mutableStateOf(Settings.load().also { I18n.use(it.language) })
        private set

    var initialized by mutableStateOf(false)
        private set

    var page by mutableStateOf(Page.Connection)
    var palette by mutableStateOf(false)
    var status by mutableStateOf(tr("Preparing local resources…"))
    var error by mutableStateOf("")
    var busy by mutableStateOf("")
        private set

    var devices by mutableStateOf(emptyList<Device>())
        private set

    var selectedDevice by mutableStateOf<Device?>(null)
        private set

    var refreshing by mutableStateOf(false)
        private set

    var wirelessAddresses by mutableStateOf(emptyList<String>())
        private set

    var apps by mutableStateOf(emptyList<AndroidApp>())
        private set

    var selectedApp by mutableStateOf<AndroidApp?>(null)
        private set

    var permissions by mutableStateOf(emptyList<Permission>())
        private set

    var mode by mutableStateOf(CaptureMode.Proxy)
    var capturing by mutableStateOf(false)
        private set

    var logging by mutableStateOf(false)
        private set

    var logOptions by mutableStateOf(LogOptions())
    private var captureChecks by mutableStateOf<Pair<CaptureTarget, List<CaptureCheck>>?>(null)
    val modeChecks
        get() = captureChecks?.takeIf { it.first == captureTarget() }?.second.orEmpty()

    private var fridaCheck by mutableStateOf<Pair<CaptureTarget, FridaDiagnosis>?>(null)

    var toolDirectoryWarnings by mutableStateOf(emptyList<String>())
        private set

    private var toolInspection by mutableStateOf<Pair<ToolConfiguration, List<ToolFinding>>?>(null)
    val toolFindings
        get() = toolInspection?.takeIf { it.first == settings.toolConfiguration() }?.second.orEmpty()

    private var toolPreparation by mutableStateOf<Pair<ToolConfiguration, Map<String, ToolStep>>?>(null)
    val toolSteps
        get() = toolPreparation?.takeIf { it.first == settings.toolConfiguration() }?.second.orEmpty()

    var setupProgress by mutableStateOf<SetupProgress?>(null)
        private set

    var setupLiveLog by mutableStateOf("")
        private set

    var lastDownloadedToolFile by mutableStateOf<File?>(null)
        private set

    var confirmation by mutableStateOf<Confirmation?>(null)
    val exchanges = mutableStateListOf<Exchange>()
    val logs = mutableStateListOf<LogEntry>()
    val journal = mutableStateListOf<String>()
    val outputs = mutableStateListOf<OutputWindow>()
    val detailIds = mutableStateListOf<String>()
    var evictedLogs by mutableStateOf(0L)
        private set

    var evictedRequests by mutableStateOf(0L)
        private set

    var receivedLogs by mutableStateOf(0L)
        private set

    private val dropped = AtomicLong()
    var droppedLogs by mutableStateOf(0L)
        private set

    private val events = Channel<JsonObject>(512)
    private val incomingLogs = Channel<LogEntry>(4096)
    private val messages = Channel<String>(1024)
    private var operation: Job? = null
    private val deviceRefreshMutex = Mutex()
    private val settingsSaveMutex = Mutex()
    private var capture: CaptureSession? = null
    private var logcat: LogcatSession? = null
    private var fridaPid: Pair<String, String>? = null
    private var fridaSettings: Settings? = null
    private var byId = HashMap<String, Int>()
    private var trafficBytes = 0L
    private var logBytes = 0L

    private fun Exchange.memoryCost() =
        512L +
            2L *
                (url.length +
                    request.data.length +
                    response.data.length +
                    diagnostic.length +
                    (diagnosticBody?.data?.length ?: 0) +
                    requestHeaders.sumOf { it.name.length + it.value.length } +
                    responseHeaders.sumOf { it.name.length + it.value.length })

    private fun LogEntry.memoryCost() = 256L + 2L * (raw.length + message.length + tag.length)

    init {
        scope.launch {
            try {
                toolDirectoryWarnings =
                    withContext(Dispatchers.IO) {
                        AppFiles.extract()
                        ToolLayout.ensure(File(settings.tools))
                    }
                toolDirectoryWarnings.forEach(::report)
                initialized = true
                status = tr("Ready. Connect Android or import a HAR file.")
                refreshDevices(true)
            } catch (e: Exception) {
                error = tr("Resource preparation: {0}", e.message)
            }
        }
        scope.launch {
            while (isActive) {
                delay(100)
                val batch = ArrayList<LogEntry>()
                repeat(1000) { incomingLogs.tryReceive().getOrNull()?.let { batch += it } }
                if (batch.isNotEmpty()) {
                    receivedLogs += batch.size
                    logs.addAll(batch)
                    logBytes += batch.sumOf { it.memoryCost() }
                    trimLogs()
                }
                droppedLogs = dropped.get()
                val captureBatch = ArrayList<JsonObject>()
                repeat(512) { events.tryReceive().getOrNull()?.let { captureBatch += it } }
                for (event in captureBatch) accept(event)
                if (captureBatch.isNotEmpty()) trimTraffic()
                var changed = false
                repeat(200) {
                    messages.tryReceive().getOrNull()?.let {
                        journal += it
                        changed = true
                    }
                }
                if (changed && journal.size > 2000) journal.subList(0, journal.size - 2000).clear()
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                if (initialized && settings.autoRefresh && busy.isEmpty()) refreshDevices()
            }
        }
    }

    fun report(message: String) {
        messages.trySend("${LocalTime.now().toString().take(12)}  $message")
    }

    fun updateSettings(value: Settings) {
        settings = value
        I18n.use(value.language)
        scope.launch {
            try {
                settingsSaveMutex.withLock { withContext(Dispatchers.IO) { settings.save() } }
            } catch (e: Exception) {
                error = tr("Settings were not saved: {0}", e.message)
            }
        }
    }

    fun task(title: String, block: suspend () -> Unit) {
        if (busy.isNotEmpty()) return
        busy = title
        error = ""
        operation =
            scope.launch {
                try {
                    block()
                    if (status == title) status = tr("Done")
                } catch (e: CancellationException) {
                    status = tr("Operation cancelled")
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                    report("$title: $error")
                } finally {
                    busy = ""
                    operation = null
                }
            }
    }

    fun cancelOperation() {
        operation?.cancel()
    }

    fun changeLanguage(language: UiLanguage) {
        updateSettings(settings.copy(language = language))
        status = tr("Language changed")
    }

    suspend fun refreshDevices(showError: Boolean = false) = deviceRefreshMutex.withLock {
        refreshing = true
        try {
            val new = Adb(settings).devices()
            devices = new
            val serial = selectedDevice?.serial ?: settings.lastSerial
            val chosen =
                new.firstOrNull { it.serial == serial }
                    ?: if (selectedDevice == null) new.firstOrNull { it.state == "device" }
                    else null
            if (
                selectedDevice?.serial != chosen?.serial ||
                    (selectedDevice?.state == "device" && chosen?.state != "device")
            ) {
                fridaCheck = null
                stopCapture()
                stopLogcat()
                apps = emptyList()
                selectedApp = null
                permissions = emptyList()
                if (selectedDevice != null) {
                    status = tr("Connection changed; streams stopped")
                    report(status)
                }
            }
            selectedDevice = chosen
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (showError) {
                error = tr("ADB: {0}. Check the ADB path on Connection.", e.message)
                report(error)
            }
        } finally {
            refreshing = false
        }
    }

    fun selectDevice(device: Device) =
        task(tr("Switching device")) {
            chooseDevice(device)
        }

    private suspend fun chooseDevice(device: Device) {
        if (selectedDevice?.serial != device.serial) {
            fridaCheck = null
            stopCapture()
            stopLogcat()
            apps = emptyList()
            selectedApp = null
            permissions = emptyList()
        }
        selectedDevice = device
        updateSettings(settings.copy(lastSerial = device.serial))
        status = tr("Selected: {0}", device.label)
    }

    fun serial(): String =
        selectedDevice?.takeIf { it.state == "device" }?.serial
            ?: error(tr("Select an authorized Android device"))

    fun loadApps() =
        task(tr("Loading apps")) {
            val serial = serial()
            val loaded = Adb(settings).applications(serial, ::report)
            if (selectedDevice?.serial == serial) {
                apps = loaded
                selectedApp =
                    loaded.firstOrNull {
                        it.packageName == (selectedApp?.packageName ?: settings.lastPackage)
                    }
                permissions = emptyList()
                status = tr("Loaded {0} apps", loaded.size)
            }
        }

    fun selectApp(app: AndroidApp) {
        selectedApp = app
        permissions = emptyList()
        updateSettings(settings.copy(lastPackage = app.packageName))
    }

    fun perform(action: AdbAction, permission: String = "") {
        val device =
            selectedDevice
                ?: run {
                    error = tr("Select a device")
                    return
                }
        val app = selectedApp
        val run = {
            task(action.title) {
                check(device.state == "device") { tr("Device unavailable") }
                val output = Adb(settings).execute(action, device.serial, app, permission)
                outputs +=
                    OutputWindow(
                        title = action.title,
                        text =
                            tr(
                                "Device: {0}\nPackage: {1} · profile {2}\n\n{3}",
                                device.label,
                                app?.packageName ?: "—",
                                app?.user ?: "—",
                                output,
                            ),
                    )
                status = tr("{0}: completed", action.title)
                if (action == AdbAction.Grant || action == AdbAction.Revoke)
                    permissions = Adb(settings).permissions(device.serial, app!!)
                if (action == AdbAction.Uninstall) {
                    apps = apps.filterNot { it.packageName == app?.packageName }
                    selectedApp = null
                    permissions = emptyList()
                }
            }
        }
        if (action.app && app == null) {
            error = tr("Select an app from the list")
            return
        }
        if (action.destructive)
            confirmation =
                Confirmation(
                    action.title,
                    tr(
                        "{0}\n{1}\n{2} · profile {3}\n\n{4}",
                        device.label,
                        app!!.label,
                        app.packageName,
                        app.user,
                        if (action == AdbAction.Uninstall)
                            tr("The app will be uninstalled for this profile.")
                        else tr("App data will be permanently deleted."),
                    ),
                    run,
                )
        else run()
    }

    fun loadPermissions() =
        task(tr("Loading permissions")) {
            permissions =
                Adb(settings).permissions(serial(), selectedApp ?: error(tr("Select an app")))
            status = tr("Permissions updated")
        }

    fun connect(address: String, code: String = "", onSuccess: () -> Unit = {}) =
        task(if (code.isBlank()) tr("Connecting ADB") else tr("Pairing ADB")) {
            val endpoint = address.trim()
            status = Adb(settings).connect(endpoint, code)
            refreshDevices(true)
            if (code.isBlank()) {
                devices.firstOrNull { it.serial == endpoint && it.state == "device" }
                    ?.let { chooseDevice(it) }
            }
            onSuccess()
        }

    fun discoverWireless() =
        task(tr("Finding Wi-Fi devices")) {
            wirelessAddresses = emptyList()
            wirelessAddresses = Adb(settings).discoverWireless()
            status = if (wirelessAddresses.isEmpty()) tr("No Wi-Fi addresses found. Enter the address from Android.")
                else tr("Found {0} Wi-Fi addresses", wirelessAddresses.size)
            refreshDevices(true)
        }

    fun disconnect() =
        task(tr("Disconnecting ADB")) {
            val target = serial()
            stopCapture()
            stopLogcat()
            status = Adb(settings).run("disconnect", target).requireSuccess()
            refreshDevices(true)
        }

    fun restartAdb() {
        confirmation =
            Confirmation(
                tr("Restart ADB?"),
                tr(
                    "This stops capture and the shared ADB server, including connections from other programs."
                ),
            ) {
                task(tr("Restarting ADB")) {
                    stopCapture()
                    stopLogcat()
                    val adb = Adb(settings)
                    adb.run("kill-server").requireSuccess()
                    adb.run("start-server").requireSuccess()
                    refreshDevices(true)
                    status = tr("ADB restarted")
                }
            }
    }

    fun rootAdb() {
        confirmation =
            Confirmation(
                tr("Request adb root?"),
                tr("On supported firmware, adbd restarts with root. Streams will stop."),
            ) {
                task("adb root") {
                    val target = serial()
                    stopCapture()
                    stopLogcat()
                    status = Adb(settings).device(target, "root").requireSuccess()
                    refreshDevices(true)
                }
            }
    }

    fun toggleCapture() =
        task(if (capturing) tr("Stopping capture") else tr("Starting capture")) {
            if (capturing) {
                stopCapture()
                status = tr("Capture stopped")
                return@task
            }
            val device = selectedDevice?.takeIf { it.state == "device" }
            if (device == null) {
                page = Page.Connection
                status = tr("Select an authorized Android device")
                return@task
            }
            if (mode != CaptureMode.Proxy && selectedApp == null) {
                page = Page.Traffic
                status = tr("Select an app")
                return@task
            }
            if (mode.isFrida && fridaDiagnosis()?.next != FridaNext.Ready &&
                refreshFridaDiagnosis()?.next != FridaNext.Ready) {
                page = Page.Connection
                return@task
            }
            val config = settings
            val selectedMode = mode
            lateinit var owned: CaptureSession
            owned =
                CaptureSession(
                    config,
                    device.serial,
                    selectedMode,
                    selectedApp,
                    scope,
                    { events.send(it) },
                    ::report,
                    {
                        scope.launch {
                            operation?.join()
                            if (capture === owned) {
                                stopCapture()
                                error =
                                    tr("The capture engine exited. See the journal for details.")
                            }
                        }
                    },
                )
            capture = owned
            try {
                owned.start()
                capturing = true
                status = tr("{0}: capture active", selectedMode.title)
            } catch (e: Exception) {
                if (selectedMode.isFrida) fridaCheck = null
                stopCapture()
                throw e
            }
        }

    suspend fun stopCapture() {
        val owned = capture
        capture = null
        owned?.stop()
        capturing = false
    }

    fun toggleLogcat() =
        task(if (logging) tr("Stopping Logcat") else tr("Starting Logcat")) {
            if (logging) {
                stopLogcat()
                status = tr("Logcat stopped")
                return@task
            }
            lateinit var owned: LogcatSession
            owned =
                LogcatSession(
                    settings,
                    serial(),
                    if (logOptions.appOnly) selectedApp else null,
                    logOptions,
                    scope,
                    { if (incomingLogs.trySend(it).isFailure) dropped.incrementAndGet() },
                    ::report,
                    {
                        scope.launch {
                            operation?.join()
                            if (logcat === owned) {
                                stopLogcat()
                                status = tr("Logcat finished. See the journal.")
                            }
                        }
                    },
                )
            logcat = owned
            try {
                owned.start()
                logging = true
                status = tr("Logcat: recording active")
            } catch (e: Exception) {
                stopLogcat()
                throw e
            }
        }

    suspend fun stopLogcat() {
        val owned = logcat
        logcat = null
        owned?.stop()
        logging = false
    }

    private fun trimLogs() {
        var count = 0
        while (
            count < logs.size &&
                (logs.size - count > settings.maxLogs || logBytes > 64L * 1024 * 1024)
        ) {
            logBytes -= logs[count].memoryCost()
            count++
        }
        if (count > 0) {
            logs.subList(0, count).clear()
            evictedLogs += count
        }
    }

    private fun trimTraffic() {
        var count = 0
        while (
            count < exchanges.size &&
                (exchanges.size - count > settings.maxRequests || trafficBytes > 96L * 1024 * 1024)
        ) {
            trafficBytes -= exchanges[count].memoryCost()
            count++
        }
        if (count > 0) {
            exchanges.subList(0, count).clear()
            evictedRequests += count
            rebuildIndex()
        }
    }

    fun clearLogs() {
        logs.clear()
        logBytes = 0
        evictedLogs = 0
        receivedLogs = 0
        dropped.set(0)
        droppedLogs = 0
        while (incomingLogs.tryReceive().isSuccess) {}
    }

    fun clearTraffic() {
        exchanges.clear()
        byId.clear()
        trafficBytes = 0
        evictedRequests = 0
    }

    private fun accept(event: JsonObject) {
        val kind = event.text("kind")
        if (kind in listOf("ready", "unsupported", "status", "identity")) {
            if (event.text("message").isNotBlank()) report(event.text("message"))
            return
        }
        val id = event.text("id")
        val index = byId[id]
        val old = index?.let { exchanges.getOrNull(it) }
        val e =
            applyCaptureEvent(
                old,
                event,
                apps.firstOrNull { it.packageName == event.text("packageName") }?.name ?: "",
            ) ?: return
        if (index != null && old != null) {
            exchanges[index] = e
            trafficBytes += e.memoryCost() - old.memoryCost()
        } else {
            byId[e.id] = exchanges.size
            exchanges += e
            trafficBytes += e.memoryCost()
        }
    }

    private fun rebuildIndex() {
        byId = HashMap()
        exchanges.forEachIndexed { i, e -> byId[e.id] = i }
    }

    fun importHar(file: File) =
        task(tr("Import HAR")) {
            val content =
                withContext(Dispatchers.IO) {
                    require(file.length() <= 64 * 1024 * 1024) { tr("HAR exceeds 64 MB") }
                    file.readText(Charsets.UTF_8)
                }
            val imported = withContext(Dispatchers.Default) { Har.read(content) }
            exchanges.addAll(imported)
            trafficBytes += imported.sumOf { it.memoryCost() }
            trimTraffic()
            rebuildIndex()
            page = Page.Traffic
            status = tr("Imported {0} requests · {1}", imported.size, file.name)
        }

    fun saveHar(file: File, snapshot: List<Exchange>) =
        task(tr("Export HAR")) {
            val content = withContext(Dispatchers.Default) { Har.write(snapshot) }
            withContext(Dispatchers.IO) { AppFiles.atomicWrite(file, content.toByteArray()) }
            status = tr("Saved {0} HTTP requests", snapshot.count { !it.raw })
        }

    fun saveText(file: File, text: String) =
        task(tr("Saving file")) {
            withContext(Dispatchers.IO) {
                AppFiles.atomicWrite(file, text.toByteArray(Charsets.UTF_8))
            }
            status = tr("Saved: {0}", file.absolutePath)
        }

    fun saveBody(file: File, body: Body) =
        task(tr("Saving body")) {
            withContext(Dispatchers.IO) { AppFiles.atomicWrite(file, body.bytes()) }
            status = tr("Body saved: {0}", file.name)
        }

    fun prepareCapture(vpn: Boolean) =
        task(if (vpn) tr("Preparing CA and VPN") else tr("Preparing CA")) {
            check(!capturing)
            val app = if (vpn) selectedApp ?: error(tr("Select an app")) else null
            CaptureSession.prepareAssets(settings, app)
            status = tr("CA{0} prepared", if (vpn) tr(" and VPN profile") else "")
        }

    fun sendCa() =
        task(tr("Sending CA")) {
            check(AppFiles.ca.isFile) { tr("Prepare the CA first") }
            val adb = Adb(settings)
            val target = serial()
            adb.device(
                    target,
                    "push",
                    AppFiles.ca.absolutePath,
                    "/sdcard/Download/DroidDebugHunter-CA.cer",
                )
                .requireSuccess()
            val activities =
                listOf(
                    "android.settings.ADVANCED_SECURITY_SETTINGS",
                    "android.settings.SECURITY_SETTINGS",
                    "android.settings.SETTINGS",
                )
            for (action in activities) {
                val r = adb.device(target, "shell", "am start -a $action")
                if (r.code == 0 && !r.text.contains("Error:", true)) break
            }
            status =
                tr(
                    "CA is in Download/DroidDebugHunter-CA.cer. Android: Security → Install certificate → CA certificate."
                )
            outputs +=
                OutputWindow(
                    title = tr("CA installation"),
                    text =
                        status +
                            tr(
                                "\n\nConfirm installation on the phone. The app must trust user CAs; pinning may block HTTPS."
                            ),
                )
        }

    fun sendVpn() =
        task(tr("Sending VPN profile")) {
            val app = selectedApp ?: error(tr("Select an app"))
            val target = serial()
            val adb = Adb(settings)
            adb.validateApp(target, app)
            val profile = File(CaptureSession.profile(app, settings), "ddh.conf")
            check(profile.isFile) { tr("Prepare the VPN profile first") }
            if (
                !adb.shell(target, "pm path --user ${app.user} com.wireguard.android")
                    .contains("package:")
            ) {
                val apk = withContext(Dispatchers.IO) { ToolLayout.wireGuardApk(settings) }
                    ?: error(tr("No local WireGuard APK"))
                adb.device(
                        target,
                        "install",
                        "--user",
                        "${app.user}",
                        apk.absolutePath,
                        timeout = 120000,
                    )
                    .requireSuccess()
            }
            adb.device(target, "push", profile.absolutePath, "/sdcard/Download/ddh.conf")
                .requireSuccess()
            adb.shell(
                target,
                "am start --user ${app.user} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.wireguard.android",
            )
            status =
                tr(
                    "WireGuard: + → Import from file → Download/ddh.conf. Start the server, enable the tunnel and confirm VPN consent."
                )
            outputs += OutputWindow(title = "WireGuard", text = status)
        }

    fun fridaFiles() =
        File(settings.tools, "frida-server")
            .listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith("frida-server-") &&
                    it.extension !in listOf("xz", "zip", "txt")
            }
            ?.sortedBy { it.name } ?: emptyList()

    private fun captureTarget() = CaptureTarget(settings, selectedDevice, selectedApp, mode)

    fun fridaDiagnosis(): FridaDiagnosis? =
        fridaCheck?.takeIf { it.first == captureTarget() }?.second

    private suspend fun refreshFridaDiagnosis(): FridaDiagnosis? {
        fridaCheck = null
        val target = captureTarget()
        val result = diagnoseFrida(target.config, target.device, target.app, target.mode, ::report)
        if (captureTarget() != target) return null
        fridaCheck = target to result
        status = tr("Frida check completed")
        return result
    }

    fun checkFrida() =
        task(tr("Checking Frida connection")) { refreshFridaDiagnosis() }

    fun installFrida(file: File? = null) =
        task(tr("Installing frida-server")) {
            fridaCheck = null
            val target = serial()
            val adb = Adb(settings)
            val arch = adb.architecture(target)
            val clientVersion = ToolInspector.fridaVersion(settings)
            if (clientVersion != ToolPolicy.target("frida"))
                report(ToolPolicy.notice("frida", clientVersion))
            val server =
                file
                    ?: fridaFiles().firstOrNull {
                        it.name == "frida-server-$clientVersion-android-$arch"
                    }
                    ?: error(tr("No frida-server {0} for {1}", clientVersion, arch))
            withContext(Dispatchers.IO) { Adb.validateElf(server, arch) }
            val remote = MANAGED_FRIDA_SERVER
            check(fridaPid == null) { tr("Stop the frida-server started by this session first") }
            adb.device(target, "push", server.absolutePath, remote, timeout = 120000)
                .requireSuccess()
            adb.shell(target, "chmod 755 $remote")
            val version = adb.shell(target, "$remote --version")
            check(version == clientVersion) {
                tr("Server version is {0}; {1} is required", version, clientVersion)
            }
            status = tr("frida-server {0} ({1}) installed", version, arch)
            refreshFridaDiagnosis()
        }

    fun startFrida() =
        task(tr("Starting frida-server")) {
            fridaCheck = null
            check(fridaPid == null) { tr("This session has already started the server") }
            val target = serial()
            val adb = Adb(settings)
            val clientVersion = ToolInspector.fridaVersion(settings)
            val serverVersion = adb.shell(target, "$MANAGED_FRIDA_SERVER --version")
            check(serverVersion == clientVersion) {
                tr("Server version is {0}; {1} is required", serverVersion, clientVersion)
            }
            if (clientVersion != ToolPolicy.target("frida"))
                report(ToolPolicy.notice("frida", clientVersion))
            val root = adb.shell(target, "id").contains("uid=0")
            val command =
                "$MANAGED_FRIDA_SERVER >/data/local/tmp/ddhc-frida.log 2>&1 & echo \$!"
            val pid =
                adb.shell(
                        target,
                        if (root) "sh -c ${Adb.quote(command)}" else "su -c ${Adb.quote(command)}",
                    )
                    .lineSequence()
                    .lastOrNull { it.matches(Regex("[0-9]+")) }
                    ?: error(tr("Cannot obtain the frida-server PID"))
            fridaPid = target to pid
            fridaSettings = settings
            delay(500)
            check(adb.shell(target, "cat /proc/$pid/cmdline").contains("ddhc-frida-server")) {
                tr("frida-server exited. Check root and port 27042.")
            }
            status = tr("frida-server active · PID {0}", pid)
            refreshFridaDiagnosis()
        }

    fun ownsFridaServer() = fridaPid != null

    val canPrepareTools
        get() = initialized && busy.isEmpty() && !capturing && !logging && !ownsFridaServer()

    suspend fun stopFridaOwned() {
        val (target, pid) = fridaPid ?: return
        val adb = Adb(fridaSettings ?: settings)
        val cmdline = adb.device(target, "shell", "cat /proc/$pid/cmdline", timeout = 5000)
        if (cmdline.code == 0 && cmdline.output.contains("ddhc-frida-server")) {
            val root = adb.shell(target, "id").contains("uid=0")
            adb.shell(target, if (root) "kill $pid" else "su -c ${Adb.quote("kill $pid")}")
        }
        fridaPid = null
        fridaSettings = null
        fridaCheck = null
    }

    fun stopFrida() =
        task(tr("Stopping frida-server")) {
            stopCapture()
            stopFridaOwned()
            status = tr("Owned frida-server stopped")
        }

    fun initializeToolFolders() =
        task(tr("Checking tool folders")) {
            toolDirectoryWarnings =
                withContext(Dispatchers.IO) { ToolLayout.ensure(File(settings.tools)) }
            toolDirectoryWarnings.forEach(::report)
            status =
                if (toolDirectoryWarnings.isEmpty()) tr("Tool folders are ready")
                else tr("Some folders could not be created; choose a writable location")
        }

    fun downloadToolSource(config: Settings, source: ToolSource) =
        task(tr("Downloading")) {
            setupProgress = SetupProgress(tr("Downloading"), source.label)
            try {
                lastDownloadedToolFile = ToolLayout.download(config, source) { setupProgress = it }
                status = tr("Downloaded: {0}", lastDownloadedToolFile!!.absolutePath)
                report(status)
            } finally {
                setupProgress = null
            }
        }

    suspend fun refreshToolStatus() {
        val config = settings
        val key = config.toolConfiguration()
        val findings = ToolInspector.inspect(config)
        if (settings.toolConfiguration() == key) toolInspection = key to findings
    }

    fun checkTools(refreshAdb: Boolean = false) =
        task(tr("Checking tools")) {
            refreshToolStatus()
            val detected = toolFindings.associateBy { it.id }
            val unresolved = toolSteps.filterNot { (component, step) ->
                step.state != ToolStepState.Prepared && toolFindingIds(component).all { id ->
                    val finding = detected[id]
                    finding != null && !finding.failed && finding.actual == settings.setup.version(finding.versionId)
                }
            }
            toolPreparation = settings.toolConfiguration() to unresolved
            if (refreshAdb) refreshDevices(true)
            status = tr("Check completed")
        }

    fun checkCapture() {
        if (mode.isFrida) {
            checkFrida()
            return
        }
        task(tr("Checking capture readiness")) {
            val context = captureTarget()
            val (config, target, app, selectedMode) = context
            val checks = mutableListOf<CaptureCheck>()
            captureChecks = null
            suspend fun check(title: String, block: suspend () -> String) {
                try {
                    checks += CaptureCheck(title, true, block())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    checks += CaptureCheck(title, false, e.message ?: tr("Unavailable"))
                }
                captureChecks = context to checks.toList()
            }
            val adb = Adb(config)
            check("ADB") { adb.run("version").requireSuccess().lineSequence().first() }
            check(tr("Device")) {
                require(target?.state == "device") { tr("Select an authorized device") }
                check(adb.device(target.serial, "get-state").requireSuccess() == "device")
                target.label
            }
            if (selectedMode != CaptureMode.Proxy)
                check(tr("App")) {
                    requireNotNull(app) { tr("Select an app") }
                    requireNotNull(target)
                    adb.validateApp(target.serial, app)
                    tr("{0} · UID {1} · profile {2}", app.packageName, app.uid, app.user)
                }
            check(tr("Python engine")) {
                Processes.run(
                    listOf(config.pythonCommand, "-c", "import mitmproxy, mitmproxy_rs; print('mitmproxy: OK')"),
                    config.environment("proxy"),
                    15000,
                ).requireSuccess()
            }
            checks +=
                CaptureCheck(
                    "HTTPS / CA",
                    null,
                    if (AppFiles.ca.isFile)
                        tr("CA prepared. Confirm CA installation and trust on Android.")
                    else tr("Prepare and install the CA on Android for HTTPS."),
                )
            if (selectedMode == CaptureMode.Vpn) {
                check("VPN endpoint") { CaptureSession.endpoint(config) }
                check("WireGuard") {
                    requireNotNull(target)
                    requireNotNull(app)
                    val installed =
                        adb.shell(
                                target.serial,
                                "pm path --user ${app.user} com.wireguard.android",
                            )
                            .contains("package:")
                    val apk = withContext(Dispatchers.IO) { ToolLayout.wireGuardApk(config) != null }
                    check(installed || apk) { tr("WireGuard is not installed; no local APK") }
                    if (installed) tr("WireGuard installed")
                    else tr("Local APK found; install when sending the profile")
                }
                checks +=
                    CaptureCheck(
                        tr("VPN permission"),
                        null,
                        tr(
                            "Import the profile, confirm VPN consent and enable the tunnel on the phone."
                        ),
                    )
            }
            captureChecks = context to checks.toList()
            status =
                if (checks.any { it.ready == false })
                    tr("Startup prerequisites failed: see the checklist")
                else tr("Automatic checks passed; complete the manual Android steps")
        }
    }

    fun setupTools(components: List<String>) =
        task(tr("Preparing tools")) {
            check(!capturing && !logging && fridaPid == null) {
                tr("Stop capture, Logcat and the owned Frida server before preparing tools.")
            }
            fridaCheck = null
            captureChecks = null
            setupLiveLog = ""
            setupProgress = SetupProgress(tr("Preparing tools"))
            val log = StringBuilder()
            try {
                val previousSteps = toolSteps
                val preparation =
                    ToolProvisioner.prepare(
                        settings,
                        components,
                        { setupProgress = it },
                        { line ->
                            report(line)
                            synchronized(log) { if (log.length < 200_000) log.appendLine(line) }
                            // Only the UI coroutine writes the live log; downloads run on IO.
                            scope.launch(Dispatchers.Main) {
                                setupLiveLog = (setupLiveLog + line + "\n").takeLast(8000)
                            }
                        },
                    )
                val config = preparation.config
                updateSettings(settings.copy(tools = config.tools, adb = config.adb, python = config.python))
                toolPreparation = settings.toolConfiguration() to
                    (previousSteps + preparation.steps.associateBy { it.component })
                refreshToolStatus()
                if (preparation.steps.any { it.component == "adb" && it.state == ToolStepState.Prepared })
                    refreshDevices(true)
                val unfinished = preparation.steps.count { it.state != ToolStepState.Prepared }
                status =
                    if (unfinished == 0) tr("Tools: operation completed")
                    else tr("Setup finished with {0} unfinished components", unfinished)
            } catch (e: CancellationException) {
                outputs +=
                    OutputWindow(
                        title = tr("Operation cancelled"),
                        text = synchronized(log) { log.toString() },
                    )
                throw e
            } catch (e: Exception) {
                outputs +=
                    OutputWindow(
                        title = tr("Tool setup failed"),
                        text = synchronized(log) { log.toString() } + "\n" + e.message,
                    )
                throw e
            } finally {
                setupProgress = null
            }
        }

    fun resetCapture() {
        confirmation =
            Confirmation(
                tr("Reset CA and VPN?"),
                tr(
                    "Stop capture before resetting. Current files will be backed up. Install the new CA and VPN profiles on Android again."
                ),
            ) {
                task(tr("Resetting CA and VPN")) {
                    check(!capturing)
                    withContext(Dispatchers.IO) {
                        CaptureLock().use {
                            val backup =
                                File(AppFiles.data, "capture-backup-${System.currentTimeMillis()}")
                                    .apply { mkdirs() }
                            for (name in listOf("proxy", "vpn")) {
                                val source = File(AppFiles.capture, name)
                                if (source.exists())
                                    java.nio.file.Files.move(
                                        source.toPath(),
                                        File(backup, name).toPath(),
                                    )
                            }
                        }
                    }
                    status = tr("CA and VPN moved to a backup directory")
                }
            }
    }

    suspend fun shutdown() {
        operation?.cancelAndJoin()
        stopCapture()
        stopLogcat()
        withContext(NonCancellable) {
            try {
                stopFridaOwned()
            } catch (e: Exception) {
                report(tr("Stopping Frida: {0}", e.message))
            }
            settingsSaveMutex.withLock { withContext(Dispatchers.IO) { settings.save() } }
        }
        scope.cancel()
    }
}
