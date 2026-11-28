package hunter.features.connection

import hunter.core.AppFiles
import hunter.core.ApplicationLog
import hunter.core.logHandledFailure
import hunter.core.tr
import hunter.features.android.Adb
import hunter.features.android.AdbAction
import hunter.features.android.AndroidApp
import hunter.features.android.AndroidConnection
import hunter.features.android.Device
import hunter.features.android.Permission
import hunter.features.settings.Settings
import hunter.features.traffic.CaptureCheck
import hunter.features.traffic.CaptureMode
import hunter.features.traffic.WIREGUARD_PROFILE
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CaptureAssetsState(
    val caAvailable: Boolean = false,
    val caSent: Boolean = false,
    val profileAvailable: Boolean = false,
    val profileSent: Boolean = false,
    val wireGuardInstalled: Boolean? = null,
)

data class ConnectionUiState(
    val devices: List<Device> = emptyList(),
    val selectedDevice: Device? = null,
    val refreshing: Boolean = false,
    val wirelessAddresses: List<String> = emptyList(),
    val apps: List<AndroidApp> = emptyList(),
    val selectedApp: AndroidApp? = null,
    val permissions: List<Permission> = emptyList(),
    val mode: CaptureMode = CaptureMode.Proxy,
    val modeChecks: List<CaptureCheck> = emptyList(),
    val fridaDiagnosis: FridaDiagnosis? = null,
    val ownsFridaServer: Boolean = false,
    val assets: CaptureAssetsState = CaptureAssetsState(),
)

internal data class CaptureTarget(
    val config: Settings,
    val device: Device?,
    val app: AndroidApp?,
    val mode: CaptureMode,
)

class ConnectionViewModel internal constructor(
    private val settings: () -> Settings,
    private val saveSettings: (Settings) -> Unit,
    private val runTask: (String, suspend () -> Unit) -> Unit,
    private val stopStreams: suspend () -> Unit,
    private val stopCapture: suspend () -> Unit,
    private val isCapturing: () -> Boolean,
    private val confirm: (String, String, () -> Unit) -> Unit,
    private val report: (String) -> Unit,
    private val showStatus: (String) -> Unit,
    private val showError: (String) -> Unit,
    private val showOutput: (String, String) -> Unit,
    private val listDevices: suspend (Settings) -> List<Device> = { Adb(it).devices() },
    private val connectWireless: suspend (Settings, String, String) -> String =
        { config, address, code -> Adb(config).connect(address, code) },
    private val discoverAddresses: suspend (Settings) -> List<String> = { Adb(it).discoverWireless() },
    private val probeFrida: suspend (
        Settings, Device?, AndroidApp?, CaptureMode?, (String) -> Unit,
    ) -> FridaDiagnosis = ::diagnoseFrida,
) {
    private val state = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = state.asStateFlow()
    private val deviceMutex = Mutex()
    private val fridaServer = ManagedFridaServer(report)
    private var caSentTo: String? = null
    private var profileSentTo: Pair<String, File>? = null

    fun refresh() = runTask(tr("Refreshing devices")) { refreshDevices(showError = true) }

    internal suspend fun autoRefresh(canRefresh: () -> Boolean) {
        while (currentCoroutineContext().isActive) {
            delay(5000)
            if (canRefresh() && settings().autoRefresh) refreshDevices()
        }
    }

    internal suspend fun refreshDevices(showError: Boolean = false) = deviceMutex.withLock {
        state.update { it.copy(refreshing = true) }
        try {
            val config = settings()
            val devices = listDevices(config)
            val current = settings()
            if (current.adb != config.adb || current.tools != config.tools) return@withLock
            val previous = state.value.selectedDevice
            val serial = previous?.serial ?: current.lastSerial
            val chosen = devices.firstOrNull { it.serial == serial }
                ?: if (previous == null) devices.firstOrNull { it.state == "device" } else null
            if (updateDevice(chosen, devices) && previous != null) {
                val message = tr("Connection changed; streams stopped")
                showStatus(message)
                report(message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logHandledFailure("adb", "Device refresh failed", e)
            if (showError) {
                val message = tr("ADB: {0}. Check the ADB path on Connection.", e.message)
                showError(message)
                report(message)
            }
        } finally {
            state.update { it.copy(refreshing = false) }
        }
    }

    fun selectDevice(device: Device) = runTask(tr("Switching device")) { chooseDevice(device) }

    private suspend fun chooseDevice(device: Device) = deviceMutex.withLock {
        updateDevice(device, state.value.devices)
        saveSettings(settings().copy(lastSerial = device.serial))
        showStatus(tr("Selected: {0}", device.label))
    }

    private suspend fun updateDevice(device: Device?, devices: List<Device>): Boolean {
        val previous = state.value.selectedDevice
        val changedTransport = previous?.serial != device?.serial ||
            (previous?.state == "device" && device?.state != "device")
        if (changedTransport) stopStreams()
        state.update {
            when {
                changedTransport -> it.copy(
                    devices = devices, selectedDevice = device,
                    apps = emptyList(), selectedApp = null, permissions = emptyList(),
                    fridaDiagnosis = null, modeChecks = emptyList(), assets = CaptureAssetsState(),
                )
                previous != device -> it.copy(
                    devices = devices, selectedDevice = device,
                    fridaDiagnosis = null, modeChecks = emptyList(), assets = CaptureAssetsState(),
                )
                else -> it.copy(devices = devices)
            }
        }
        return changedTransport
    }

    fun serial(): String = state.value.selectedDevice?.takeIf { it.state == "device" }?.serial
        ?: error(tr("Select an authorized Android device"))

    fun connect(address: String, code: String = "", onSuccess: () -> Unit = {}) =
        runTask(if (code.isBlank()) tr("Connecting ADB") else tr("Pairing ADB")) {
            val endpoint = address.trim()
            showStatus(connectWireless(settings(), endpoint, code))
            refreshDevices(showError = true)
            if (code.isBlank()) {
                state.value.devices.firstOrNull { it.serial == endpoint && it.state == "device" }
                    ?.let { chooseDevice(it) }
            }
            onSuccess()
        }

    fun discoverWireless() = runTask(tr("Finding Wi-Fi devices")) {
        state.update { it.copy(wirelessAddresses = emptyList()) }
        val addresses = discoverAddresses(settings())
        state.update { it.copy(wirelessAddresses = addresses) }
        showStatus(
            if (addresses.isEmpty()) tr("No Wi-Fi addresses found. Enter the address from Android.")
            else tr("Found {0} Wi-Fi addresses", addresses.size),
        )
        refreshDevices(showError = true)
    }

    fun disconnect() = runTask(tr("Disconnecting ADB")) {
        val target = serial()
        stopStreams()
        showStatus(Adb(settings()).run("disconnect", target).requireSuccess())
        refreshDevices(showError = true)
    }

    fun restartAdb() = confirm(
        tr("Restart ADB?"),
        tr("This stops capture and the shared ADB server, including connections from other programs."),
    ) {
        runTask(tr("Restarting ADB")) {
            stopStreams()
            val adb = Adb(settings())
            adb.run("kill-server").requireSuccess()
            adb.run("start-server").requireSuccess()
            refreshDevices(showError = true)
            showStatus(tr("ADB restarted"))
        }
    }

    fun rootAdb() = confirm(
        tr("Request adb root?"),
        tr("On supported firmware, adbd restarts with root. Streams will stop."),
    ) {
        runTask("adb root") {
            val target = serial()
            stopStreams()
            showStatus(Adb(settings()).device(target, "root").requireSuccess())
            refreshDevices(showError = true)
        }
    }

    fun setMode(mode: CaptureMode) {
        state.update {
            if (it.mode == mode) it else it.copy(
                mode = mode, fridaDiagnosis = null, modeChecks = emptyList(), assets = CaptureAssetsState(),
            )
        }
    }

    fun setGadget(enabled: Boolean) = saveSettings(settings().copy(gadget = enabled))

    fun setSpawn(enabled: Boolean) = saveSettings(settings().copy(spawn = enabled))

    internal fun invalidateDiagnostics() {
        state.update { it.copy(fridaDiagnosis = null, modeChecks = emptyList(), assets = CaptureAssetsState()) }
    }

    internal fun captureTarget(): CaptureTarget = state.value.let {
        CaptureTarget(settings(), it.selectedDevice, it.selectedApp, it.mode)
    }

    internal suspend fun refreshFridaDiagnosis(): FridaDiagnosis? {
        state.update { it.copy(fridaDiagnosis = null) }
        val target = captureTarget()
        val result = probeFrida(target.config, target.device, target.app, target.mode, report)
        if (captureTarget() != target) return null
        state.update { it.copy(fridaDiagnosis = result) }
        currentCoroutineContext()[ApplicationLog]?.info(
            "frida", "Next step · ${result.next.name}${result.requiredAbi?.let { " · ABI $it" }.orEmpty()}",
        )
        showStatus(tr("Frida check completed"))
        return result
    }

    fun checkFrida() = runTask(tr("Checking Frida connection")) { refreshFridaDiagnosis() }

    fun installFrida(file: File? = null) = runTask(tr("Installing frida-server")) {
        invalidateDiagnostics()
        showStatus(fridaServer.install(settings(), serial(), file))
        refreshFridaDiagnosis()
    }

    fun startFrida() = runTask(tr("Starting frida-server")) {
        invalidateDiagnostics()
        try {
            showStatus(fridaServer.start(settings(), serial()))
        } finally {
            state.update { it.copy(ownsFridaServer = fridaServer.isOwned) }
        }
        refreshFridaDiagnosis()
    }

    internal suspend fun stopFridaOwned() {
        if (!fridaServer.isOwned) return
        fridaServer.stopOwned()
        state.update { it.copy(ownsFridaServer = false, fridaDiagnosis = null) }
    }

    fun stopFrida() = runTask(tr("Stopping frida-server")) {
        stopCapture()
        stopFridaOwned()
        showStatus(tr("Owned frida-server stopped"))
    }

    fun loadApps() = runTask(tr("Loading apps")) {
        val serial = serial()
        val loaded = Adb(settings()).applications(serial, report)
        val current = state.value.selectedDevice
        if (current?.serial == serial && current.state == "device") {
            state.update {
                val selected = loaded.firstOrNull { app ->
                    app.packageName == (it.selectedApp?.packageName ?: settings().lastPackage)
                }
                it.copy(
                    apps = loaded, selectedApp = selected, permissions = emptyList(),
                    fridaDiagnosis = it.fridaDiagnosis.takeIf { _ -> selected == it.selectedApp },
                    modeChecks = if (selected == it.selectedApp) it.modeChecks else emptyList(),
                    assets = if (selected == it.selectedApp) it.assets else CaptureAssetsState(),
                )
            }
            showStatus(tr("Loaded {0} apps", loaded.size))
        }
    }

    fun selectApp(app: AndroidApp) {
        state.update {
            it.copy(
                selectedApp = app, permissions = emptyList(),
                fridaDiagnosis = it.fridaDiagnosis.takeIf { _ -> app == it.selectedApp },
                modeChecks = if (app == it.selectedApp) it.modeChecks else emptyList(),
                assets = if (app == it.selectedApp) it.assets else CaptureAssetsState(),
            )
        }
        saveSettings(settings().copy(lastPackage = app.packageName))
    }

    fun loadPermissions() = runTask(tr("Loading permissions")) {
        val target = captureTarget()
        val app = target.app ?: error(tr("Select an app"))
        val permissions = Adb(target.config).permissions(serial(), app)
        if (sameApp(target)) state.update { it.copy(permissions = permissions) }
        showStatus(tr("Permissions updated"))
    }

    fun perform(action: AdbAction, permission: String = "") {
        val target = captureTarget()
        val device = target.device ?: run {
            showError(tr("Select a device"))
            return
        }
        val app = target.app
        if (action.app && app == null) {
            showError(tr("Select an app from the list"))
            return
        }
        val run = {
            runTask(action.title) {
                check(sameApp(target) && device.state == "device") { tr("Device unavailable") }
                val adb = Adb(settings())
                val output = adb.execute(action, device.serial, app, permission)
                showOutput(
                    action.title,
                    tr(
                        "Device: {0}\nPackage: {1} · profile {2}\n\n{3}",
                        device.label, app?.packageName ?: "—", app?.user ?: "—", output,
                    ),
                )
                showStatus(tr("{0}: completed", action.title))
                if (action == AdbAction.Grant || action == AdbAction.Revoke) {
                    val permissions = adb.permissions(device.serial, requireNotNull(app))
                    if (sameApp(target)) state.update { it.copy(permissions = permissions) }
                }
                if (action == AdbAction.Uninstall && sameApp(target)) {
                    state.update {
                        it.copy(
                            apps = it.apps.filterNot { entry -> entry.packageName == app?.packageName },
                            selectedApp = null, permissions = emptyList(),
                            fridaDiagnosis = null, modeChecks = emptyList(), assets = CaptureAssetsState(),
                        )
                    }
                }
            }
        }
        if (action.destructive) {
            val selected = requireNotNull(app)
            confirm(
                action.title,
                tr(
                    "{0}\n{1}\n{2} · profile {3}\n\n{4}",
                    device.label, selected.label, selected.packageName, selected.user,
                    if (action == AdbAction.Uninstall)
                        tr("The app will be uninstalled for this profile.")
                    else tr("App data will be permanently deleted."),
                ),
                run,
            )
        } else run()
    }

    private fun sameApp(target: CaptureTarget): Boolean =
        state.value.let { it.selectedDevice == target.device && it.selectedApp == target.app }

    internal suspend fun checkCapture() {
        val context = captureTarget()
        if (context.mode.isFrida) {
            refreshFridaDiagnosis()
            return
        }
        state.update { it.copy(modeChecks = emptyList()) }
        val checks = checkCaptureReadiness(context) { partial ->
            if (captureTarget() == context) state.update { it.copy(modeChecks = partial) }
        }
        refreshCaptureAssets(context)
        if (captureTarget() == context) {
            state.update { it.copy(modeChecks = checks) }
            showStatus(
                if (checks.any { it.ready == false }) tr("Startup prerequisites failed: see the checklist")
                else tr("Android checks completed; review the remaining setup steps"),
            )
        }
    }

    internal suspend fun refreshCaptureAssets() = refreshCaptureAssets(captureTarget())

    private suspend fun refreshCaptureAssets(target: CaptureTarget) {
        if (target.mode.isFrida) return
        val serial = target.device?.takeIf { it.state == "device" }?.serial
        val app = target.app.takeIf { target.mode == CaptureMode.Vpn }
        val profile = app?.let {
            try {
                ConnectionAssets.profileFile(target.config, it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val (caAvailable, profileAvailable) = withContext(Dispatchers.IO) {
            AppFiles.ca.isFile to (profile?.isFile == true)
        }
        val installed = if (serial != null && app != null) {
            try {
                ConnectionAssets.wireGuardInstalled(target.config, serial, app.user)
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                logHandledFailure("wireguard", "Android package check timed out", e)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logHandledFailure("wireguard", "Could not verify the Android package", e)
                null
            }
        } else null
        if (captureTarget() != target) return
        state.update {
            it.copy(assets = CaptureAssetsState(
                caAvailable = caAvailable,
                caSent = caAvailable && serial != null && caSentTo == serial,
                profileAvailable = profileAvailable,
                profileSent = profileAvailable && profileSentTo == (serial to profile),
                wireGuardInstalled = installed,
            ))
        }
    }

    fun prepareCapture(vpn: Boolean) = runTask(if (vpn) tr("Preparing CA and VPN") else tr("Preparing CA")) {
        check(!isCapturing())
        val target = captureTarget()
        val app = if (vpn) target.app ?: error(tr("Select an app")) else null
        showStatus(ConnectionAssets.prepare(target.config, app, vpn))
        refreshCaptureAssets(target)
    }

    fun sendCa() = runTask(tr("Sending CA")) {
        val target = captureTarget()
        val serial = serial()
        ConnectionAssets.sendCa(target.config, serial)
        caSentTo = serial
        refreshCaptureAssets(target)
        showStatus(tr("Certificate sent to Download/DroidDebugHunter-CA.cer"))
    }

    fun installWireGuard() = runTask(tr("Installing WireGuard on Android")) {
        val target = captureTarget()
        val app = target.app ?: error(tr("Select an app"))
        ConnectionAssets.installWireGuard(target.config, serial(), app)
        refreshCaptureAssets(target)
        showStatus(tr("WireGuard installed"))
    }

    fun sendVpn() = runTask(tr("Sending VPN profile")) {
        val target = captureTarget()
        val app = target.app ?: error(tr("Select an app"))
        val serial = serial()
        ConnectionAssets.sendVpn(target.config, serial, app)
        profileSentTo = serial to ConnectionAssets.profileFile(target.config, app)
        refreshCaptureAssets(target)
        showStatus(tr("Profile sent to Download/{0}", WIREGUARD_PROFILE))
    }

    fun resetCapture() = confirm(
        tr("Reset CA and VPN?"),
        tr("Stop capture before resetting. Current files will be backed up. Install the new CA and VPN profiles on Android again."),
    ) {
        runTask(tr("Resetting CA and VPN")) {
            check(!isCapturing())
            ConnectionAssets.backup()
            caSentTo = null
            profileSentTo = null
            invalidateDiagnostics()
            showStatus(tr("CA and VPN moved to a backup directory"))
        }
    }
}
