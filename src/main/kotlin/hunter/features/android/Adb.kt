package hunter.features.android

import hunter.core.AppFiles
import hunter.core.ApplicationLog
import hunter.core.CommandResult
import hunter.core.Processes
import hunter.core.array
import hunter.core.logHandledFailure
import hunter.core.parseObject
import hunter.core.text
import hunter.core.tr
import hunter.features.settings.Settings
import hunter.features.tools.ToolPolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

enum class AdbAction(
    private val titleKey: String,
    val app: Boolean = true,
    val destructive: Boolean = false,
) {
    Start("Launch"),
    Stop("Stop"),
    Restart("Restart"),
    Debug("Launch with JDWP"),
    RestartDebug("Restart with JDWP"),
    Clear("Clear data", destructive = true),
    ClearStart("Clear and launch", destructive = true),
    Uninstall("Uninstall for profile", destructive = true),
    AppSettings("App settings"),
    AppInfo("Information / APK"),
    AppProcesses("App processes"),
    AppLogs("Recent logs"),
    Grant("Grant permission"),
    Revoke("Revoke permission"),
    WifiOn("Enable Wi-Fi", false),
    WifiOff("Disable Wi-Fi", false),
    DataOn("Enable mobile data", false),
    DataOff("Disable mobile data", false),
    Network("IP / routes / proxy", false),
    WifiSettings("Wi-Fi settings", false),
    DeviceInfo("Device information", false),
    Processes("All processes", false);

    val title
        get() = tr(titleKey)
}

class Adb(private val settings: Settings) {
    suspend fun run(vararg args: String, timeout: Long = 30000): CommandResult {
        val log = currentCoroutineContext()[ApplicationLog]
        val action = args.getOrNull(if (args.firstOrNull() == "-s") 2 else 0).orEmpty()
        log?.debug("adb", "Command · $action")
        // ADB can start the shared daemon as a descendant of this client.
        return Processes.run(
            listOf(settings.adbCommand) + args,
            timeoutMs = timeout,
            terminateDescendants = false,
        ).also { result ->
            if (result.code != 0) log?.warning("adb", "Command $action exited with code ${result.code}")
        }
    }

    suspend fun device(serial: String, vararg args: String, timeout: Long = 30000) =
        run("-s", serial, *args, timeout = timeout)

    suspend fun shell(serial: String, command: String): String {
        val result = device(serial, "shell", command)
        val output = result.requireSuccess()
        check(
            !Regex(
                    "(?im)^\\s*(Error:|Failure\\b|Security exception:|Exception occurred|Permission Denial:|java\\.[\\w.]*Exception\\b)"
                )
                .containsMatchIn(result.text) && !result.text.contains("Permission denied", true)
        ) {
            result.text
        }
        return output
    }

    suspend fun devices() = parseDevices(run("devices", "-l").requireSuccess())

    suspend fun connect(address: String, code: String = ""): String {
        require(validAddress(address)) { tr("Enter IP:port") }
        if (code.isNotBlank())
            require(code.matches(Regex("[0-9]{6}"))) {
                tr("The pairing code must contain 6 digits")
            }
        val result = if (code.isBlank()) run("connect", address) else run("pair", address, code)
        val output = if (code.isBlank()) result.requireSuccess() else result.output.trim()
        val success =
            if (code.isBlank()) Regex("(?im)^(?:already )?connected to ")
            else Regex("(?im)^successfully paired to ")
        check(result.code == 0 && success.containsMatchIn(output)) {
            if (code.isBlank()) result.text.ifBlank { tr("Could not connect over Wi-Fi") }
            else tr("Pairing failed. Open a new pairing code on Android and try again.")
        }
        return output
    }

    suspend fun discoverWireless() =
        parseWirelessServices(run("mdns", "services", timeout = 5000).requireSuccess())

    suspend fun applications(serial: String, onWarning: (String) -> Unit = {}): List<AndroidApp> =
        coroutineScope {
            val user =
                shell(serial, "am get-current-user").toIntOrNull()
                    ?: error(tr("Cannot determine the Android profile"))
            val installed = async { shell(serial, "pm list packages -U --user $user") }
            val system = async { shell(serial, "pm list packages -s --user $user") }
            val names = async {
                try {
                    appLabels(serial, user)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logHandledFailure("adb", "Application label lookup failed", e)
                    onWarning(tr("App labels unavailable: {0}", e.message))
                    emptyMap()
                }
            }
            val systemPackages =
                Regex("(?m)^package:(\\S+)")
                    .findAll(system.await())
                    .map { it.groupValues[1] }
                    .toSet()
            val labels = names.await()
            val apps = parseApplications(installed.await(), user, systemPackages, labels)
            check(shell(serial, "am get-current-user") == user.toString()) {
                tr("The Android profile changed while loading. Refresh apps.")
            }
            apps
        }

    private suspend fun appLabels(serial: String, user: Int): Map<String, String> {
        val remote =
            "/data/local/tmp/ddhc-labels-${UUID.randomUUID().toString().replace("-","")}.dex"
        try {
            device(
                    serial,
                    "push",
                    File(AppFiles.runtime, "AndroidHelper/app-labels.dex").absolutePath,
                    remote,
                )
                .requireSuccess()
            val output =
                shell(
                    serial,
                    "chmod 444 $remote && CLASSPATH=$remote app_process /system/bin droiddebughunter.AppLabels $user",
                )
            val payload =
                output
                    .lineSequence()
                    .firstOrNull { it.startsWith("DDH_APP_LABELS_V1:") }
                    ?.substringAfter(':') ?: error(output)
            val root = parseObject(payload)
            check(root.text("userId").toIntOrNull() == user)
            return root
                .array("applications")
                .take(10000)
                .map { it.asJsonObject }
                .associate {
                    it.text("packageName") to
                        it.text("name").filterNot(Char::isISOControl).take(256)
                }
        } finally {
            withContext(NonCancellable) {
                try {
                    device(serial, "shell", "rm -f $remote", timeout = 4000).requireSuccess()
                } catch (e: Exception) {
                    logHandledFailure("adb", "Android label helper cleanup failed", e)
                }
            }
        }
    }

    suspend fun validateApp(serial: String, app: AndroidApp) {
        validateName(app.packageName)
        check(shell(serial, "am get-current-user") == app.user.toString()) {
            tr("The Android profile changed. Refresh apps.")
        }
        val current =
            parseApplications(
                    shell(
                        serial,
                        "pm list packages -U --user ${app.user} ${quote(app.packageName)}",
                    ),
                    app.user,
                )
                .singleOrNull { it.packageName == app.packageName }
        check(current != null && (app.uid == null || current.uid == app.uid)) {
            tr("The package was removed or its UID changed. Refresh apps.")
        }
    }

    suspend fun permissions(serial: String, app: AndroidApp): List<Permission> {
        validateApp(serial, app)
        val dump = shell(serial, "dumpsys package ${quote(app.packageName)}")
        val dangerous = shell(serial, "pm list permissions -d")
        return parsePermissions(dump, dangerous, app.user)
    }

    private suspend fun pids(serial: String, app: AndroidApp): List<String> {
        require(app.uid != null) { tr("Android did not provide a UID") }
        return Regex("(?m)^\\s*(\\d+)\\s+(\\d+)\\s+(\\S+)\\s*$")
            .findAll(shell(serial, "ps -A -o UID,PID,NAME"))
            .filter {
                it.groupValues[1] == app.uid.toString() &&
                    (it.groupValues[3] == app.packageName ||
                        it.groupValues[3].startsWith(app.packageName + ":"))
            }
            .map { it.groupValues[2] }
            .toList()
    }

    internal suspend fun launcherActivity(serial: String, user: Int, packageName: String): String {
        validateName(packageName)
        return shell(
            serial,
            "cmd package resolve-activity --brief --user $user -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${quote(packageName)}",
        ).lineSequence().lastOrNull {
            it.startsWith("$packageName/") && it.matches(Regex("[A-Za-z][\\w.]*/[A-Za-z_.$][\\w.$]*"))
        } ?: error(tr("The app has no launcher activity"))
    }

    suspend fun execute(
        action: AdbAction,
        serial: String,
        app: AndroidApp?,
        permission: String = "",
    ): String {
        if (action.app) {
            requireNotNull(app) { tr("Select an app from the list") }
            validateApp(serial, app)
        }
        val pkg = quote(app?.packageName ?: "")
        val user = app?.user ?: 0
        if (action == AdbAction.Grant || action == AdbAction.Revoke) {
            validateName(permission)
            require(permissions(serial, app!!).any { it.name == permission }) {
                tr("Select a declared dangerous runtime permission")
            }
            shell(
                serial,
                "pm ${if (action == AdbAction.Grant) "grant" else "revoke"} --user $user $pkg ${quote(permission)}",
            )
            val actual = permissions(serial, app).single { it.name == permission }
            check(actual.granted == null || actual.granted == (action == AdbAction.Grant)) {
                tr("Android did not confirm the change")
            }
            return "${actual.name} · granted=${actual.granted}"
        }
        val starting =
            action in
                listOf(
                    AdbAction.Start,
                    AdbAction.Restart,
                    AdbAction.Debug,
                    AdbAction.RestartDebug,
                    AdbAction.ClearStart,
                )
        val debugging = action in listOf(AdbAction.Debug, AdbAction.RestartDebug)
        var launcher = ""
        if (starting) {
            launcher = launcherActivity(serial, user, app!!.packageName)
            if (debugging) {
                check(
                    Regex("(?s)\\b(?:flags|pkgFlags)=\\[[^]]*\\bDEBUGGABLE\\b")
                        .containsMatchIn(shell(serial, "dumpsys package $pkg"))
                ) {
                    tr("JDWP requires a debuggable app")
                }
                if (action == AdbAction.Debug)
                    check(pids(serial, app!!).isEmpty()) {
                        tr("The app is running. Select “Restart with JDWP”.")
                    }
            }
        }
        if (action in listOf(AdbAction.Restart, AdbAction.RestartDebug, AdbAction.ClearStart))
            shell(serial, "am force-stop --user $user $pkg")
        if (action.destructive) {
            val result =
                shell(
                    serial,
                    "pm ${if (action == AdbAction.Uninstall) "uninstall" else "clear"} --user $user $pkg",
                )
            check(result.lineSequence().any { it.trim() == "Success" }) {
                tr("Android did not confirm the operation: {0}", result)
            }
            if (!starting) return result
        }
        if (starting)
            return shell(
                serial,
                "am start ${if (debugging) "-D" else "-W"} --user $user -n ${quote(launcher)}",
            )
        if (action == AdbAction.AppProcesses || action == AdbAction.AppLogs) {
            val ids = pids(serial, app!!)
            if (action == AdbAction.AppProcesses)
                return "PID: ${ids.joinToString().ifBlank { tr("no processes running") }}"
            check(ids.isNotEmpty()) { tr("The app is not running") }
            return ids.take(8)
                .map { "PID $it\n" + shell(serial, "logcat -d -t 200 --pid=$it -v threadtime") }
                .joinToString("\n\n")
        }
        val commands =
            when (action) {
                AdbAction.Stop -> listOf("am force-stop --user $user $pkg")
                AdbAction.AppSettings ->
                    listOf(
                        "am start --user $user -a android.settings.APPLICATION_DETAILS_SETTINGS -d ${quote("package:"+app!!.packageName)}"
                    )
                AdbAction.AppInfo -> listOf("dumpsys package $pkg", "pm path --user $user $pkg")
                AdbAction.WifiOn -> listOf("svc wifi enable")
                AdbAction.WifiOff -> listOf("svc wifi disable")
                AdbAction.DataOn -> listOf("svc data enable")
                AdbAction.DataOff -> listOf("svc data disable")
                AdbAction.WifiSettings -> listOf("am start -a android.settings.WIFI_SETTINGS")
                AdbAction.Network ->
                    listOf("ip addr show", "ip route", "settings get global http_proxy")
                AdbAction.DeviceInfo ->
                    listOf(
                        "getprop ro.product.model",
                        "getprop ro.build.version.release",
                        "getprop ro.build.version.sdk",
                        "getprop ro.product.cpu.abi",
                        "id",
                        "wm size",
                        "dumpsys battery",
                    )
                AdbAction.Processes -> listOf("ps -A")
                else -> error(tr("Unknown operation"))
            }
        return commands
            .map { "$ $it\n${shell(serial,it)}" }
            .joinToString("\n\n")
            .ifBlank { tr("Command completed") }
    }

    suspend fun architecture(serial: String, app: AndroidApp? = null): String {
        val abi =
            if (app != null)
                Regex("primaryCpuAbi=(\\S+)")
                    .find(shell(serial, "dumpsys package ${quote(app.packageName)}"))
                    ?.groupValues
                    ?.get(1)
                    ?.takeUnless { it == "null" }
            else null
        return arch(abi ?: shell(serial, "getprop ro.product.cpu.abi"))
    }

    suspend fun requireGadgetAccess(serial: String, app: AndroidApp) {
        val user = if (app.user == 0) "" else " --user ${app.user}"
        val result =
            device(serial, "shell", "run-as ${quote(app.packageName)}$user id", timeout = 5000)
        val uid = Regex("(?m)^uid=(\\d+)").find(result.output)?.groupValues?.get(1)?.toIntOrNull()
        check(result.code == 0 && uid != null && (app.uid == null || uid == app.uid)) {
            tr("Gadget requires working run-as / debuggable") + "\n" + result.text
        }
    }

    companion object {
        val FRIDA_VERSION
            get() = ToolPolicy.target("frida")

        fun validAddress(address: String): Boolean =
            address.matches(Regex("(?:[A-Za-z0-9][A-Za-z0-9.-]*|\\[[a-fA-F0-9:]+]):[0-9]{1,5}")) &&
                address.substringAfterLast(':').toIntOrNull()?.let { it in 1..65535 } == true

        fun parseWirelessServices(output: String): List<String> =
            output.lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size == 3 && it[1].trimEnd('.') == "_adb-tls-connect._tcp" }
                .map { it[2] }
                .filter(::validAddress)
                .distinct()
                .sorted()
                .toList()

        fun quote(value: String) = "'${value.replace("'", "'\\''")}'"

        fun validateName(value: String) {
            require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*"))) {
                tr("Invalid package / permission")
            }
        }

        fun arch(abi: String) =
            when (abi.trim()) {
                "arm64-v8a" -> "arm64"
                "armeabi-v7a",
                "armeabi" -> "arm"
                "x86_64" -> "x86_64"
                "x86" -> "x86"
                else -> error(tr("Unknown ABI: {0}", abi))
            }

        fun parseDevices(output: String) =
            output
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith("List ") && !it.startsWith("*") }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 2) null
                    else
                        Device(
                            parts[0],
                            parts[1],
                            parts
                                .firstOrNull { it.startsWith("model:") }
                                ?.substringAfter(':')
                                ?.replace('_', ' ') ?: "",
                        )
                }
                .toList()

        fun parseApplications(
            output: String,
            user: Int,
            system: Set<String> = emptySet(),
            labels: Map<String, String> = emptyMap(),
        ): List<AndroidApp> =
            Regex("(?m)^package:([^\\s]+)(?:\\s+uid:(\\d+))?")
                .findAll(output)
                .map {
                    AndroidApp(
                        it.groupValues[1],
                        it.groupValues[2].toIntOrNull(),
                        user,
                        it.groupValues[1] in system,
                        labels[it.groupValues[1]] ?: "",
                    )
                }
                .distinctBy { it.packageName }
                .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
                .toList()

        fun parsePermissions(dump: String, dangerous: String, user: Int): List<Permission> {
            val section =
                dump
                    .substringAfter("requested permissions:", "")
                    .lineSequence()
                    .dropWhile { it.isBlank() }
                    .takeWhile { it.trim().matches(Regex("[A-Za-z][\\w]*(?:\\.[\\w]+)+")) }
                    .map { it.trim() }
                    .toSet()
            val allowed =
                Regex("(?m)^\\s*permission:([A-Za-z][\\w.]*)\\s*$")
                    .findAll(dangerous)
                    .map { it.groupValues[1] }
                    .toSet()
            val userSection =
                Regex("(?ms)^\\s*User $user:.*?(?=^\\s*User \\d+:|\\z)").find(dump)?.value ?: ""
            val states =
                Regex("(?m)^\\s*([A-Za-z][\\w.]*): granted=(true|false)")
                    .findAll(userSection)
                    .associate { it.groupValues[1] to it.groupValues[2].toBoolean() }
            return section.intersect(allowed).sorted().map { Permission(it, states[it]) }
        }

        fun validateElf(file: File, arch: String) {
            require(file.isFile) { tr("File not found: {0}", file) }
            val header = file.inputStream().use { it.readNBytes(20) }
            require(
                header.size == 20 &&
                    header.take(4) == listOf(0x7f.toByte(), 69.toByte(), 76.toByte(), 70.toByte())
            ) {
                tr("The file is not ELF")
            }
            val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
            val expected =
                mapOf(
                    "arm64" to (2 to 183),
                    "arm" to (1 to 40),
                    "x86_64" to (2 to 62),
                    "x86" to (1 to 3),
                )[arch] ?: error(tr("Unknown architecture"))
            require(
                header[5] == 1.toByte() &&
                    header[4].toInt() == expected.first &&
                    machine == expected.second
            ) {
                tr("ELF does not match {0}", arch)
            }
        }
    }
}
