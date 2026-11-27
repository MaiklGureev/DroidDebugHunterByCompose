package hunter

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val FRIDA_REMOTE_PORT = "tcp:27042"
const val MANAGED_FRIDA_SERVER = "/data/local/tmp/ddhc-frida-server"

class FridaForward private constructor(
    private val adb: Adb,
    private val serial: String,
    private val port: Int,
) {
    val address get() = "127.0.0.1:$port"

    suspend fun close() = withContext(NonCancellable) {
        val local = "tcp:$port"
        val entries = adb.run("forward", "--list", timeout = 5000).requireSuccess()
        if (entries.lineSequence().any {
                it.trim().split(Regex("\\s+")) == listOf(serial, local, FRIDA_REMOTE_PORT)
            }) {
            adb.device(serial, "forward", "--remove", local, timeout = 5000).requireSuccess()
        }
    }

    companion object {
        suspend fun open(adb: Adb, serial: String): FridaForward = withContext(NonCancellable) {
            val port = adb.device(serial, "forward", "tcp:0", FRIDA_REMOTE_PORT, timeout = 5000)
                .requireSuccess().toIntOrNull()
            check(port != null && port in 1..65535) { tr("ADB forward did not return a port") }
            FridaForward(adb, serial, port)
        }
    }
}

enum class FridaNext(val label: String) {
    Device("Select a device"),
    Tools("Open tool settings"),
    App("Select an app"),
    Install("Install server"),
    Start("Start server"),
    Gadget("Use Gadget"),
    LaunchApp("Enable launch at startup"),
    Retry("Check again"),
    Ready("Start capture"),
}

data class FridaDiagnosis(val checks: List<CaptureCheck>, val next: FridaNext)

/** Probes the selected transport without starting a server, launching an app or injecting an agent. */
suspend fun diagnoseFrida(
    config: Settings,
    device: Device?,
    app: AndroidApp?,
    mode: CaptureMode?,
    report: (String) -> Unit,
): FridaDiagnosis {
    val checks = mutableListOf<CaptureCheck>()
    fun finish(next: FridaNext) = FridaDiagnosis(checks.toList(), next)
    suspend fun <T> attempt(title: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            checks += CaptureCheck(title, false, tr("The check timed out"))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checks += CaptureCheck(title, false, e.message ?: tr("Unavailable"))
            null
        }

    val serial = device?.takeIf { it.state == "device" }?.serial
    if (serial == null) {
        checks += CaptureCheck(tr("Device"), false, tr("Select an authorized Android device"))
        return finish(FridaNext.Device)
    }
    val adb = Adb(config)
    attempt(tr("Device")) {
        check(adb.device(serial, "get-state", timeout = 5000).requireSuccess() == "device") {
            tr("Device unauthorized / unavailable")
        }
    } ?: return finish(FridaNext.Device)
    val version = attempt(tr("Frida client")) { ToolInspector.fridaVersion(config) }
        ?: return finish(FridaNext.Tools)
    checks += CaptureCheck(tr("Frida client"), true, version)
    if (version != ToolPolicy.target("frida")) {
        checks += CaptureCheck(tr("Target version"), null, ToolPolicy.notice("frida", version))
    }
    if (mode == CaptureMode.Native && attempt(tr("Python engine")) {
            Processes.run(listOf(config.pythonCommand, "-c", "import h11, hpack"),
                config.environment("capture"), 5000).requireSuccess()
        } == null) return finish(FridaNext.Tools)

    if (mode != null && app != null) {
        attempt(tr("App")) { adb.validateApp(serial, app) } ?: return finish(FridaNext.App)
    }
    if (mode != null && config.gadget) {
        if (app == null) {
            checks += CaptureCheck(tr("App"), false, tr("Select an app"))
            return finish(FridaNext.App)
        }
        attempt("Gadget / run-as") { adb.requireGadgetAccess(serial, app) }
            ?: return finish(FridaNext.App)
        val arch = attempt(tr("App ABI")) { adb.architecture(serial, app) }
            ?: return finish(FridaNext.App)
        checks += CaptureCheck(tr("App ABI"), true, arch)
        val file = File(config.tools, "frida-gadget/frida-gadget-$version-android-$arch.so")
        attempt("Gadget") {
            withContext(Dispatchers.IO) { Adb.validateElf(file, arch) }
        } ?: return finish(FridaNext.Tools)
        checks += CaptureCheck("Gadget", true, tr("ELF matches {0}; selected for client {1}", arch, version))
        checks += CaptureCheck(tr("Frida connection"), null,
            tr("Gadget connects when the app launches. The readiness check does not launch the app."))
        return finish(if (config.spawn) FridaNext.Ready else FridaNext.LaunchApp)
    }

    val arch = attempt(tr("Device ABI")) { adb.architecture(serial) }
        ?: return finish(FridaNext.Retry)
    checks += CaptureCheck(tr("Device ABI"), true, arch)
    val installedVersion = attempt(tr("Installed server")) {
        val exists = adb.device(serial, "shell", "test -x $MANAGED_FRIDA_SERVER", timeout = 5000)
        when (exists.code) {
            0 -> adb.device(serial, "shell", "$MANAGED_FRIDA_SERVER --version", timeout = 5000)
                .requireSuccess().also {
                    check(ToolPolicy.validVersion(it)) { tr("Cannot read the installed server version") }
                }
            1 -> if (exists.text.isBlank()) "" else exists.requireSuccess()
            else -> exists.requireSuccess()
        }
    }
    val compatible = installedVersion == version
    if (installedVersion == "") {
        checks += CaptureCheck(tr("Installed server"), null, tr("Managed server is not installed"))
    } else if (installedVersion != null) {
        checks += CaptureCheck(tr("Installed server"), if (compatible) true else null,
            if (compatible) installedVersion else tr("Server version is {0}; {1} is required", installedVersion, version))
    }
    val forward = attempt(tr("Frida connection")) { FridaForward.open(adb, serial) }
        ?: return finish(FridaNext.Retry)
    val connection = try {
        attempt(tr("Frida connection")) {
            currentCoroutineContext().ensureActive()
            val args = listOf(config.pythonCommand, CaptureSession.agent("frida_probe.py"), forward.address)
            val result = Processes.run(
                if (mode != null && !config.spawn && app != null) args + app.packageName else args,
                config.environment("capture"),
                8000,
            ).requireSuccess()
            parseObject(result)
        }
    } finally {
        try {
            forward.close()
        } catch (e: Exception) {
            report("ADB forward: ${e.message}")
        }
    }
    val probeError = connection?.text("error").orEmpty()
    if (connection != null && probeError.isNotEmpty()) {
        checks += CaptureCheck(tr("Frida connection"), false,
            connection.text("detail").ifBlank { probeError })
        if (probeError != "ServerNotRunningError") return finish(FridaNext.Retry)
    } else if (connection != null) {
        val remoteArch = connection.text("arch")
        val matches = remoteArch.isEmpty() || remoteArch == arch
        checks += CaptureCheck(tr("Frida connection"), matches,
            if (matches) tr("Frida responded on the selected device")
            else tr("Frida reports ABI {0}; Android reports {1}", remoteArch, arch))
        checks += CaptureCheck(tr("Running server version"), null,
            tr("The connection works, but Frida does not report the running server's exact version."))
        val launchNeeded = mode != null && app != null && !config.spawn &&
            !connection.flag("appRunning")
        if (launchNeeded) {
            checks += CaptureCheck(tr("App"), false,
                tr("Open the app on Android and check again, or enable launch at startup."))
        }
        return finish(when {
            !matches -> FridaNext.Retry
            mode != null && app == null -> FridaNext.App
            launchNeeded -> FridaNext.LaunchApp
            else -> FridaNext.Ready
        })
    }

    val rootAvailable = attempt(tr("Root access")) {
        val result = adb.device(serial, "shell", "id -u; command -v su", timeout = 5000).text
        result.lineSequence().any { it.trim() == "0" || it.trim().endsWith("/su") }
    } ?: false
    if (!rootAvailable) {
        checks += CaptureCheck(tr("Root access"), false,
            tr("Use a root-enabled test device or choose Gadget for a debuggable app."))
        return finish(if (mode != null) FridaNext.Gadget else FridaNext.Retry)
    }
    if (compatible) return finish(FridaNext.Start)
    val local = File(config.tools, "frida-server/frida-server-$version-android-$arch")
    val hasLocal = attempt(tr("Local server file")) {
        withContext(Dispatchers.IO) { Adb.validateElf(local, arch) }
    } != null
    if (hasLocal) checks += CaptureCheck(tr("Local server file"), true, "$version · $arch")
    return finish(if (hasLocal) FridaNext.Install else FridaNext.Tools)
}
