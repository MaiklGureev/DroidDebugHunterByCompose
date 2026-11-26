package hunter

import androidx.compose.runtime.*
import com.google.gson.JsonObject
import java.io.File
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

enum class Page(val label: String, val caption: String) {
    Traffic("Трафик", "HTTP-запросы и ответы"),
    Devices("Устройства", "USB, Wi-Fi и Frida"),
    Apps("Приложения", "Процессы, разрешения и данные"),
    Logcat("Logcat", "Журнал Android в реальном времени"),
    Tools("ADB-инструменты", "Сеть и диагностика устройства"),
    Settings("Настройки", "Утилиты и поведение"),
    Help("Справка", "Настройка и руководство"),
}

data class OutputWindow(val id: Long = System.nanoTime(), val title: String, val text: String)

data class Confirmation(val title: String, val text: String, val run: () -> Unit)

data class CaptureCheck(val title: String, val ready: Boolean?, val detail: String)

class AppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var settings by mutableStateOf(Settings.load())
        private set

    var initialized by mutableStateOf(false)
        private set

    var page by mutableStateOf(Page.Traffic)
    var palette by mutableStateOf(false)
    var status by mutableStateOf("Подготовка локальных ресурсов…")
    var error by mutableStateOf("")
    var busy by mutableStateOf("")
        private set

    var devices by mutableStateOf(emptyList<Device>())
        private set

    var selectedDevice by mutableStateOf<Device?>(null)
        private set

    var refreshing by mutableStateOf(false)
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
    var modeChecks by mutableStateOf(emptyList<CaptureCheck>())
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
                withContext(Dispatchers.IO) { AppFiles.extract() }
                initialized = true
                status = "Готово. Подключите Android или импортируйте HAR."
                refreshDevices(true)
            } catch (e: Exception) {
                error = "Подготовка ресурсов: ${e.message}"
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
        scope.launch {
            try {
                withContext(Dispatchers.IO) { value.save() }
            } catch (e: Exception) {
                error = "Настройки не сохранены: ${e.message}"
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
                    if (status == title) status = "Готово"
                } catch (e: CancellationException) {
                    status = "Операция отменена"
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

    suspend fun refreshDevices(showError: Boolean = false) {
        if (refreshing) return
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
                stopCapture()
                stopLogcat()
                apps = emptyList()
                selectedApp = null
                permissions = emptyList()
                if (selectedDevice != null) {
                    status = "Подключение изменилось; потоки остановлены"
                    report(status)
                }
            }
            selectedDevice = chosen
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (showError) {
                error = "ADB: ${e.message}. Проверьте путь в настройках."
                report(error)
            }
        } finally {
            refreshing = false
        }
    }

    fun selectDevice(device: Device) =
        task("Смена устройства") {
            stopCapture()
            stopLogcat()
            selectedDevice = device
            apps = emptyList()
            selectedApp = null
            permissions = emptyList()
            updateSettings(settings.copy(lastSerial = device.serial))
            status = "Выбрано: ${device.label}"
        }

    fun serial(): String =
        selectedDevice?.takeIf { it.state == "device" }?.serial
            ?: error("Выберите авторизованное Android-устройство")

    fun loadApps() =
        task("Загрузка приложений") {
            val serial = serial()
            val loaded = Adb(settings).applications(serial, ::report)
            if (selectedDevice?.serial == serial) {
                apps = loaded
                selectedApp =
                    loaded.firstOrNull {
                        it.packageName == (selectedApp?.packageName ?: settings.lastPackage)
                    }
                permissions = emptyList()
                status = "Загружено ${loaded.size} приложений"
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
                    error = "Выберите устройство"
                    return
                }
        val app = selectedApp
        val run = {
            task(action.title) {
                check(device.state == "device") { "Устройство недоступно" }
                val output = Adb(settings).execute(action, device.serial, app, permission)
                outputs +=
                    OutputWindow(
                        title = action.title,
                        text =
                            "Устройство: ${device.label}\nПакет: ${app?.packageName ?: "—"} · профиль ${app?.user ?: "—"}\n\n$output",
                    )
                status = "${action.title}: выполнено"
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
            error = "Выберите приложение из списка"
            return
        }
        if (action.destructive)
            confirmation =
                Confirmation(
                    action.title,
                    "${device.label}\n${app!!.label}\n${app.packageName} · профиль ${app.user}\n\n${if(action==AdbAction.Uninstall) "Приложение будет удалено для этого профиля." else "Данные приложения будут безвозвратно удалены."}",
                    run,
                )
        else run()
    }

    fun loadPermissions() =
        task("Загрузка разрешений") {
            permissions =
                Adb(settings).permissions(serial(), selectedApp ?: error("Выберите приложение"))
            status = "Разрешения обновлены"
        }

    fun connect(address: String, code: String) =
        task(if (code.isBlank()) "Подключение ADB" else "Сопряжение ADB") {
            status = Adb(settings).connect(address, code)
            refreshDevices(true)
        }

    fun disconnect() =
        task("Отключение ADB") {
            val target = serial()
            stopCapture()
            stopLogcat()
            status = Adb(settings).run("disconnect", target).requireSuccess()
            refreshDevices(true)
        }

    fun restartAdb() {
        confirmation =
            Confirmation(
                "Перезапустить ADB?",
                "Это остановит захват и общий ADB-сервер, включая соединения других программ.",
            ) {
                task("Перезапуск ADB") {
                    stopCapture()
                    stopLogcat()
                    val adb = Adb(settings)
                    adb.run("kill-server").requireSuccess()
                    adb.run("start-server").requireSuccess()
                    refreshDevices(true)
                    status = "ADB перезапущен"
                }
            }
    }

    fun rootAdb() {
        confirmation =
            Confirmation(
                "Запросить adb root?",
                "На поддерживаемой прошивке adbd будет перезапущен с root. Потоки будут остановлены.",
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
        task(if (capturing) "Остановка захвата" else "Запуск захвата") {
            if (capturing) {
                stopCapture()
                status = "Захват остановлен"
                return@task
            }
            val target = serial()
            val config = settings
            val selectedMode = mode
            lateinit var owned: CaptureSession
            owned =
                CaptureSession(
                    config,
                    target,
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
                                error = "Движок захвата завершился. Подробности в журнале."
                            }
                        }
                    },
                )
            capture = owned
            try {
                owned.start()
                capturing = true
                status = "${selectedMode.title}: захват активен"
            } catch (e: Exception) {
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
        task(if (logging) "Остановка Logcat" else "Запуск Logcat") {
            if (logging) {
                stopLogcat()
                status = "Logcat остановлен"
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
                                status = "Logcat завершён. См. журнал."
                            }
                        }
                    },
                )
            logcat = owned
            try {
                owned.start()
                logging = true
                status = "Logcat: запись активна"
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
        task("Импорт HAR") {
            val content =
                withContext(Dispatchers.IO) {
                    require(file.length() <= 64 * 1024 * 1024) { "HAR больше 64 МБ" }
                    file.readText(Charsets.UTF_8)
                }
            val imported = withContext(Dispatchers.Default) { Har.read(content) }
            exchanges.addAll(imported)
            trafficBytes += imported.sumOf { it.memoryCost() }
            trimTraffic()
            rebuildIndex()
            status = "Импортировано ${imported.size} запросов · ${file.name}"
        }

    fun saveHar(file: File, snapshot: List<Exchange>) =
        task("Экспорт HAR") {
            val content = withContext(Dispatchers.Default) { Har.write(snapshot) }
            withContext(Dispatchers.IO) { AppFiles.atomicWrite(file, content.toByteArray()) }
            status = "Сохранено ${snapshot.count { !it.raw }} HTTP-запросов"
        }

    fun saveText(file: File, text: String) =
        task("Сохранение файла") {
            withContext(Dispatchers.IO) {
                AppFiles.atomicWrite(file, text.toByteArray(Charsets.UTF_8))
            }
            status = "Сохранено: ${file.absolutePath}"
        }

    fun saveBody(file: File, body: Body) =
        task("Сохранение тела") {
            withContext(Dispatchers.IO) { AppFiles.atomicWrite(file, body.bytes()) }
            status = "Тело сохранено: ${file.name}"
        }

    fun prepareCapture(vpn: Boolean) =
        task(if (vpn) "Подготовка CA и VPN" else "Подготовка CA") {
            check(!capturing)
            val app = if (vpn) selectedApp ?: error("Выберите приложение") else null
            CaptureSession.prepareAssets(settings, app)
            status = "CA${if(vpn) " и VPN-профиль" else ""} подготовлены"
        }

    fun sendCa() =
        task("Передача CA") {
            check(AppFiles.ca.isFile) { "Сначала подготовьте CA" }
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
                "CA в Download/DroidDebugHunter-CA.cer. Android: Безопасность → Установить сертификат → Сертификат CA."
            outputs +=
                OutputWindow(
                    title = "Установка CA",
                    text =
                        status +
                            "\n\nПодтвердите установку на телефоне. Приложение должно доверять пользовательским CA; pinning может блокировать HTTPS.",
                )
        }

    fun sendVpn() =
        task("Передача VPN-профиля") {
            val app = selectedApp ?: error("Выберите приложение")
            val target = serial()
            val adb = Adb(settings)
            adb.validateApp(target, app)
            val profile = File(CaptureSession.profile(app, settings), "ddh.conf")
            check(profile.isFile) { "Сначала подготовьте VPN-профиль" }
            if (
                !adb.shell(target, "pm path --user ${app.user} com.wireguard.android")
                    .contains("package:")
            ) {
                val apk =
                    File(settings.tools, "android-tools").listFiles()?.firstOrNull {
                        it.extension == "apk" && it.name.startsWith("com.wireguard.android-")
                    } ?: error("Нет локального APK WireGuard")
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
                "WireGuard: + → Импорт из файла → Download/ddh.conf. Запустите сервер, включите туннель, подтвердите VPN."
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

    fun installFrida(file: File? = null) =
        task("Установка frida-server") {
            val target = serial()
            val adb = Adb(settings)
            val arch = adb.architecture(target)
            val server =
                file
                    ?: fridaFiles().firstOrNull {
                        it.name == "frida-server-${Adb.FRIDA_VERSION}-android-$arch"
                    }
                    ?: error("Нет frida-server ${Adb.FRIDA_VERSION} для $arch")
            withContext(Dispatchers.IO) { Adb.validateElf(server, arch) }
            val remote = "/data/local/tmp/ddhc-frida-server"
            check(fridaPid == null) { "Сначала остановите запущенный нами frida-server" }
            adb.device(target, "push", server.absolutePath, remote, timeout = 120000)
                .requireSuccess()
            adb.shell(target, "chmod 755 $remote")
            val version = adb.shell(target, "$remote --version")
            check(version == Adb.FRIDA_VERSION) {
                "Версия сервера $version, нужен ${Adb.FRIDA_VERSION}"
            }
            status = "frida-server $version ($arch) установлен"
        }

    fun startFrida() =
        task("Запуск frida-server") {
            check(fridaPid == null) { "Сервер уже запущен этой сессией" }
            val target = serial()
            val adb = Adb(settings)
            check(
                adb.shell(target, "/data/local/tmp/ddhc-frida-server --version") ==
                    Adb.FRIDA_VERSION
            )
            val root = adb.shell(target, "id").contains("uid=0")
            val command =
                "/data/local/tmp/ddhc-frida-server >/data/local/tmp/ddhc-frida.log 2>&1 & echo \$!"
            val pid =
                adb.shell(
                        target,
                        if (root) "sh -c ${Adb.quote(command)}" else "su -c ${Adb.quote(command)}",
                    )
                    .lineSequence()
                    .lastOrNull { it.matches(Regex("[0-9]+")) }
                    ?: error("Не удалось получить PID frida-server")
            fridaPid = target to pid
            fridaSettings = settings
            delay(500)
            check(adb.shell(target, "cat /proc/$pid/cmdline").contains("ddhc-frida-server")) {
                "frida-server завершился. Проверьте root и порт 27042."
            }
            status = "frida-server активен · PID $pid"
        }

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
    }

    fun stopFrida() =
        task("Остановка frida-server") {
            stopCapture()
            stopFridaOwned()
            status = "Собственный frida-server остановлен"
        }

    fun checkTools() =
        task("Проверка утилит") {
            val config = settings
            val adb =
                runCatching { Adb(config).run("version").requireSuccess() }
                    .getOrElse { "ADB: ${it.message}" }
            val python =
                runCatching {
                        Processes.run(
                                listOf(config.pythonCommand, "--version"),
                                config.environment(),
                                10000,
                            )
                            .text
                    }
                    .getOrElse { "Python: ${it.message}" }
            val modules =
                runCatching {
                        Processes.run(
                                listOf(
                                    config.pythonCommand,
                                    "-c",
                                    "import frida, mitmproxy, mitmproxy_rs; print('Frida ' + frida.__version__); print('mitmproxy: OK')",
                                ),
                                config.environment(),
                                15000,
                            )
                            .text
                    }
                    .getOrElse { "Модули: ${it.message}" }
            outputs +=
                OutputWindow(
                    title = "Проверка утилит",
                    text =
                        "Каталог: ${config.tools}\n\n$adb\n\n$python\n\n$modules\n\nfrida-server: ${fridaFiles().joinToString { it.name }}",
                )
            status = "Проверка завершена"
        }

    fun checkCapture() =
        task("Проверка готовности захвата") {
            val config = settings
            val selectedMode = mode
            val app = selectedApp
            val target = selectedDevice
            val checks = mutableListOf<CaptureCheck>()
            modeChecks = emptyList()
            suspend fun check(title: String, block: suspend () -> String) {
                try {
                    checks += CaptureCheck(title, true, block())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    checks += CaptureCheck(title, false, e.message ?: "Недоступно")
                }
                modeChecks = checks.toList()
            }
            val adb = Adb(config)
            check("ADB") { adb.run("version").requireSuccess().lineSequence().first() }
            check("Устройство") {
                require(target?.state == "device") { "Выберите авторизованное устройство" }
                check(adb.device(target.serial, "get-state").requireSuccess() == "device")
                target.label
            }
            if (selectedMode != CaptureMode.Proxy)
                check("Приложение") {
                    requireNotNull(app) { "Выберите приложение" }
                    requireNotNull(target)
                    adb.validateApp(target.serial, app)
                    "${app.packageName} · UID ${app.uid} · профиль ${app.user}"
                }
            check("Движок Python") {
                val code =
                    if (selectedMode == CaptureMode.Proxy || selectedMode == CaptureMode.Vpn)
                        "import mitmproxy, mitmproxy_rs; print('mitmproxy: OK')"
                    else
                        "import frida, frida_tools.repl; assert frida.__version__ == '${Adb.FRIDA_VERSION}', frida.__version__; print('Frida ' + frida.__version__)"
                Processes.run(listOf(config.pythonCommand, "-c", code), config.environment(), 15000)
                    .requireSuccess()
            }
            if (selectedMode == CaptureMode.OkHttp || selectedMode == CaptureMode.Native) {
                if (config.gadget)
                    check("Gadget / run-as") {
                        requireNotNull(app)
                        requireNotNull(target)
                        check(config.spawn) { "Включите запуск приложения" }
                        adb.shell(target.serial, "run-as ${Adb.quote(app.packageName)} id")
                        val arch = adb.architecture(target.serial, app)
                        val file =
                            File(
                                config.tools,
                                "frida-gadget/frida-gadget-${Adb.FRIDA_VERSION}-android-$arch.so",
                            )
                        withContext(Dispatchers.IO) { Adb.validateElf(file, arch) }
                        "Gadget $arch найден, run-as доступен"
                    }
                else
                    check("frida-server") {
                        requireNotNull(target)
                        val processes = adb.shell(target.serial, "ps -A")
                        check(processes.contains("frida", true)) {
                            "frida-server не найден. Установите и запустите его на устройстве."
                        }
                        "Процесс Frida найден; совместимость проверяется при подключении"
                    }
            } else {
                checks +=
                    CaptureCheck(
                        "HTTPS / CA",
                        null,
                        if (AppFiles.ca.isFile)
                            "CA подготовлен. Установку и доверие CA подтвердите на Android."
                        else "Подготовьте CA и установите его на Android для HTTPS.",
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
                        val apk =
                            File(config.tools, "android-tools").listFiles()?.any {
                                it.extension == "apk"
                            } == true
                        check(installed || apk) {
                            "WireGuard не установлен; локальный APK отсутствует"
                        }
                        if (installed) "WireGuard установлен"
                        else "Локальный APK найден; установите при передаче профиля"
                    }
                    checks +=
                        CaptureCheck(
                            "Разрешение VPN",
                            null,
                            "Импортируйте профиль, подтвердите VPN и включите туннель на телефоне.",
                        )
                }
            }
            modeChecks = checks.toList()
            status =
                if (checks.any { it.ready == false }) "Есть препятствия запуску: см. чек-лист"
                else "Автоматические проверки пройдены; выполните ручные шаги Android"
        }

    fun setupTools(download: Boolean, component: String) =
        task(if (download) "Подготовка утилит" else "Проверка комплекта") {
            val config = settings
            val result =
                Processes.run(
                    listOf(
                        "powershell.exe",
                        "-NoProfile",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        File(AppFiles.runtime, "tool-setup/setup_tools.ps1").absolutePath,
                        "-Action",
                        if (download) "download" else "check",
                        "-Component",
                        component,
                        "-ToolsRoot",
                        config.tools,
                        "-Python",
                        config.pythonCommand,
                        "-NoOpen",
                    ),
                    config.environment(),
                    if (download) 1200000 else 30000,
                )
            outputs +=
                OutputWindow(title = "Настройка утилит · код ${result.code}", text = result.text)
            result.requireSuccess()
            status = "Утилиты: операция завершена"
        }

    fun resetCapture() {
        confirmation =
            Confirmation(
                "Сбросить CA и VPN?",
                "Остановите захват перед сбросом. Текущие файлы будут перенесены в резервную папку. Новый CA и VPN-профили потребуется установить на Android заново.",
            ) {
                task("Сброс CA и VPN") {
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
                    status = "CA и VPN перенесены в резервную папку"
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
                report("Остановка Frida: ${e.message}")
            }
            withContext(Dispatchers.IO) { settings.save() }
        }
        scope.cancel()
    }
}
