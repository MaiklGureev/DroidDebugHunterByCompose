package hunter

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

enum class AdbAction(val title: String, val app: Boolean = true, val destructive: Boolean = false) {
    Start("Запустить"),
    Stop("Остановить"),
    Restart("Перезапустить"),
    Debug("Запустить с JDWP"),
    RestartDebug("Перезапустить с JDWP"),
    Clear("Очистить данные", destructive = true),
    ClearStart("Очистить и запустить", destructive = true),
    Uninstall("Удалить для профиля", destructive = true),
    AppSettings("Настройки приложения"),
    AppInfo("Информация / APK"),
    AppProcesses("Процессы приложения"),
    AppLogs("Последние логи"),
    Grant("Выдать разрешение"),
    Revoke("Отозвать разрешение"),
    WifiOn("Включить Wi-Fi", false),
    WifiOff("Выключить Wi-Fi", false),
    DataOn("Включить мобильные данные", false),
    DataOff("Выключить мобильные данные", false),
    Network("IP / маршруты / прокси", false),
    WifiSettings("Настройки Wi-Fi", false),
    DeviceInfo("Сведения об устройстве", false),
    Processes("Все процессы", false),
}

class Adb(private val settings: Settings) {
    suspend fun run(vararg args: String, timeout: Long = 30000) =
        Processes.run(listOf(settings.adbCommand) + args, timeoutMs = timeout)

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
        require(
            address.matches(Regex("(?:[A-Za-z0-9][A-Za-z0-9.:-]*|\\[[a-fA-F0-9:]+]):[0-9]{1,5}"))
        ) {
            "Укажите IP:порт"
        }
        if (code.isNotBlank())
            require(code.matches(Regex("[0-9]{6}"))) { "Код сопряжения должен содержать 6 цифр" }
        val result = if (code.isBlank()) run("connect", address) else run("pair", address, code)
        val output = result.requireSuccess()
        check(!Regex("(?i)failed|cannot|unable|error").containsMatchIn(output)) { output }
        return output
    }

    suspend fun applications(serial: String, onWarning: (String) -> Unit = {}): List<AndroidApp> =
        coroutineScope {
            val user =
                shell(serial, "am get-current-user").toIntOrNull()
                    ?: error("Не удалось определить профиль Android")
            val installed = async { shell(serial, "pm list packages -U --user $user") }
            val system = async { shell(serial, "pm list packages -s --user $user") }
            val names = async {
                try {
                    appLabels(serial, user)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onWarning("Названия приложений недоступны: ${e.message}")
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
                "Профиль Android изменился во время загрузки. Обновите приложения."
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
                runCatching { device(serial, "shell", "rm -f $remote", timeout = 4000) }
            }
        }
    }

    suspend fun validateApp(serial: String, app: AndroidApp) {
        validateName(app.packageName)
        check(shell(serial, "am get-current-user") == app.user.toString()) {
            "Профиль Android изменился. Обновите приложения."
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
            "Пакет удалён или UID изменился. Обновите приложения."
        }
    }

    suspend fun permissions(serial: String, app: AndroidApp): List<Permission> {
        validateApp(serial, app)
        val dump = shell(serial, "dumpsys package ${quote(app.packageName)}")
        val dangerous = shell(serial, "pm list permissions -d")
        return parsePermissions(dump, dangerous, app.user)
    }

    private suspend fun pids(serial: String, app: AndroidApp): List<String> {
        require(app.uid != null) { "Android не предоставил UID" }
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

    suspend fun execute(
        action: AdbAction,
        serial: String,
        app: AndroidApp?,
        permission: String = "",
    ): String {
        if (action.app) {
            requireNotNull(app) { "Выберите приложение из списка" }
            validateApp(serial, app)
        }
        val pkg = quote(app?.packageName ?: "")
        val user = app?.user ?: 0
        if (action == AdbAction.Grant || action == AdbAction.Revoke) {
            validateName(permission)
            require(permissions(serial, app!!).any { it.name == permission }) {
                "Выберите объявленное опасное runtime-разрешение"
            }
            shell(
                serial,
                "pm ${if (action == AdbAction.Grant) "grant" else "revoke"} --user $user $pkg ${quote(permission)}",
            )
            val actual = permissions(serial, app).single { it.name == permission }
            check(actual.granted == null || actual.granted == (action == AdbAction.Grant)) {
                "Android не подтвердил изменение"
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
            launcher =
                shell(
                        serial,
                        "cmd package resolve-activity --brief --user $user -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg",
                    )
                    .lineSequence()
                    .lastOrNull {
                        it.matches(Regex("[A-Za-z][\\w.]*/[A-Za-z_.$][\\w.$]*")) &&
                            it.startsWith(app!!.packageName + "/")
                    } ?: error("У приложения нет launcher-активности")
            if (debugging) {
                check(
                    Regex("(?s)\\b(?:flags|pkgFlags)=\\[[^]]*\\bDEBUGGABLE\\b")
                        .containsMatchIn(shell(serial, "dumpsys package $pkg"))
                ) {
                    "JDWP требует debuggable-приложение"
                }
                if (action == AdbAction.Debug)
                    check(pids(serial, app!!).isEmpty()) {
                        "Приложение работает. Выберите «Перезапустить с JDWP»."
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
                "Android не подтвердил действие: $result"
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
                return "PID: ${ids.joinToString().ifBlank { "процессы не запущены" }}"
            check(ids.isNotEmpty()) { "Приложение не запущено" }
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
                else -> error("Неизвестное действие")
            }
        return commands
            .map { "$ $it\n${shell(serial,it)}" }
            .joinToString("\n\n")
            .ifBlank { "Команда выполнена" }
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

    companion object {
        const val FRIDA_VERSION = "17.26.2"

        fun quote(value: String) = "'${value.replace("'", "'\\''")}'"

        fun validateName(value: String) {
            require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*"))) {
                "Некорректный пакет / разрешение"
            }
        }

        fun arch(abi: String) =
            when (abi.trim()) {
                "arm64-v8a" -> "arm64"
                "armeabi-v7a",
                "armeabi" -> "arm"
                "x86_64" -> "x86_64"
                "x86" -> "x86"
                else -> error("Неизвестная ABI: $abi")
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
            require(file.isFile) { "Файл не найден: $file" }
            val header = file.inputStream().use { it.readNBytes(20) }
            require(
                header.size == 20 &&
                    header.take(4) == listOf(0x7f.toByte(), 69.toByte(), 76.toByte(), 70.toByte())
            ) {
                "Файл не является ELF"
            }
            val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
            val expected =
                mapOf(
                    "arm64" to (2 to 183),
                    "arm" to (1 to 40),
                    "x86_64" to (2 to 62),
                    "x86" to (1 to 3),
                )[arch] ?: error("Неизвестная архитектура")
            require(
                header[5] == 1.toByte() &&
                    header[4].toInt() == expected.first &&
                    machine == expected.second
            ) {
                "ELF не соответствует $arch"
            }
        }
    }
}
