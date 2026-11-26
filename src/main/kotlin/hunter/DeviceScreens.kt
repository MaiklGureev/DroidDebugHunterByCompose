package hunter

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DevicesScreen(model: AppState) {
    var connection by remember { mutableStateOf(false) }
    val enabled = model.busy.isEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("Подключить по сети", Icons.Outlined.Add, enabled, primary = true) {
                connection = true
            }
            Action("Обновить", Icons.Outlined.Refresh, enabled && !model.refreshing) {
                model.task("Обновление устройств") { model.refreshDevices(true) }
            }
            Action("Перезапустить ADB", enabled = enabled, onClick = model::restartAdb)
        }
        if (model.devices.isEmpty())
            Panel(
                "Нет устройств",
                "Включите USB-отладку, подключите Android и подтвердите RSA-ключ компьютера на телефоне.",
            ) {
                Text(
                    "Для Android 11+ доступно беспроводное сопряжение. Адрес и порт сопряжения отличаются от адреса подключения.",
                    color = Muted,
                )
            }
        model.devices.forEach { device ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                border =
                    BorderStroke(
                        1.dp,
                        if (model.selectedDevice?.serial == device.serial) Accent else Line,
                    ),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) { model.selectDevice(device) }
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        if (device.serial.contains(":")) Icons.Outlined.Wifi
                        else Icons.Outlined.PhoneAndroid,
                        null,
                        Modifier.size(30.dp),
                        tint = if (device.state == "device") Green else Amber,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.model.ifBlank { device.serial },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(device.serial, color = Muted, fontSize = 12.sp)
                    }
                    Pill(
                        when (device.state) {
                            "device" -> "Подключено"
                            "unauthorized" -> "Подтвердите RSA на телефоне"
                            else -> device.state
                        },
                        if (device.state == "device") Green else Amber,
                    )
                    RadioButton(
                        model.selectedDevice?.serial == device.serial,
                        { model.selectDevice(device) },
                        enabled = enabled,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(
                "Выбранное устройство",
                model.selectedDevice?.label ?: "Выберите устройство",
                Modifier.weight(1f),
            ) {
                Action(
                    "Android, ABI, батарея",
                    Icons.Outlined.Info,
                    enabled && model.selectedDevice?.state == "device",
                ) {
                    model.perform(AdbAction.DeviceInfo)
                }
                Action(
                    "Проверить root",
                    Icons.Outlined.AdminPanelSettings,
                    enabled && model.selectedDevice?.state == "device",
                ) {
                    model.task("Проверка root") {
                        val target = model.serial()
                        val adb = Adb(model.settings)
                        val current = adb.shell(target, "id")
                        val root = adb.device(target, "shell", "su -c id", timeout = 8000)
                        model.outputs +=
                            OutputWindow(
                                title = "Доступ root",
                                text = "adbd: $current\nsu: ${root.text}",
                            )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("adb root", enabled = enabled, onClick = model::rootAdb)
                    Action(
                        "Отключить Wi-Fi ADB",
                        enabled = enabled && model.selectedDevice?.serial?.contains(":") == true,
                        onClick = model::disconnect,
                    )
                }
                Text(
                    "unauthorized: разблокируйте экран и разрешите отладку. offline: переподключите USB или сетевое соединение.",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            Panel(
                "Frida",
                "Локальный комплект ${Adb.FRIDA_VERSION} · выбор сервера по ABI",
                Modifier.weight(1f),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Установить", Icons.Outlined.InstallMobile, enabled) {
                        model.installFrida()
                    }
                    Action("Выбрать файл…", enabled = enabled) {
                        chooseFile(title = "frida-server (распакованный ELF)")?.let {
                            model.installFrida(it)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(
                        "Запустить",
                        Icons.Outlined.PlayArrow,
                        enabled,
                        onClick = model::startFrida,
                    )
                    Action(
                        "Остановить свой",
                        Icons.Outlined.Stop,
                        enabled,
                        onClick = model::stopFrida,
                    )
                }
                Action("Проверить состояние", enabled = enabled) {
                    model.task("Состояние Frida") {
                        model.outputs +=
                            OutputWindow(
                                title = "Состояние Frida",
                                text =
                                    Adb(model.settings).shell(model.serial(), "ps -A | grep frida"),
                            )
                    }
                }
                Text(
                    "Запуск сервера требует root. Без root используйте debuggable + Gadget в настройке захвата. При выходе завершается только сервер, запущенный этой сессией.",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }
    }
    if (connection) {
        var address by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var pairing by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { connection = false },
            title = { Text("Подключение Android") },
            text = {
                Column(Modifier.width(430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Check("Сопряжение Android 11+", pairing) { pairing = it }
                    Field(
                        address,
                        { address = it },
                        if (pairing) "Адрес сопряжения IP:порт" else "Адрес подключения IP:порт",
                        Modifier.fillMaxWidth(),
                    )
                    if (pairing)
                        Field(
                            code,
                            { code = it },
                            "Код из 6 цифр",
                            Modifier.fillMaxWidth(),
                            password = true,
                        )
                    Text(
                        "Android → Для разработчиков → Беспроводная отладка. После сопряжения выполните подключение по основному адресу устройства.",
                        color = Muted,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        connection = false
                        model.connect(address, if (pairing) code else "")
                    },
                    enabled = address.isNotBlank() && (!pairing || code.length == 6),
                ) {
                    Text(if (pairing) "Сопрячь" else "Подключить")
                }
            },
            dismissButton = { TextButton({ connection = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun AppsScreen(model: AppState) {
    val enabled = model.busy.isEmpty()
    val app = model.selectedApp
    var tab by remember { mutableStateOf(0) }
    var permission by remember(app?.packageName) { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppPicker(model, Modifier.widthIn(max = 700.dp).fillMaxWidth(), enabled)
        if (app == null)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Outlined.Apps,
                    "Выберите приложение",
                    "Обновите список и найдите приложение по названию, пакету, UID или App ID.",
                ) {
                    Action(
                        "Загрузить приложения",
                        Icons.Outlined.Refresh,
                        enabled && model.selectedDevice?.state == "device",
                        onClick = model::loadApps,
                    )
                }
            }
        else {
            Panel(app.label, app.packageName) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        if (app.system) "Системное" else "Стороннее",
                        if (app.system) Amber else Green,
                    )
                    Pill("UID ${app.uid ?: "?"}", Accent)
                    Pill("App ID ${app.appId}", Accent)
                    Pill("Профиль ${app.user}", Accent)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(AdbAction.Start, AdbAction.Stop, AdbAction.Restart).forEach { action ->
                        Action(
                            action.title,
                            enabled = enabled,
                            primary = action == AdbAction.Start,
                        ) {
                            model.perform(action)
                        }
                    }
                    Action("Трафик", Icons.Outlined.SwapVert) { model.page = Page.Traffic }
                    Action("Logcat", Icons.Outlined.Terminal) {
                        model.logOptions = model.logOptions.copy(appOnly = true)
                        model.page = Page.Logcat
                    }
                }
            }
            TabRow(tab, backgroundColor = MaterialTheme.colors.background) {
                listOf("Диагностика", "Разрешения", "Отладка", "Данные").forEachIndexed { i, label
                    ->
                    Tab(tab == i, { tab = i }, text = { Text(label) })
                }
            }
            when (tab) {
                0 ->
                    Panel("Диагностика приложения", modifier = Modifier.fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                    AdbAction.AppInfo,
                                    AdbAction.AppProcesses,
                                    AdbAction.AppLogs,
                                    AdbAction.AppSettings,
                                )
                                .forEach { action ->
                                    Action(action.title, enabled = enabled) {
                                        model.perform(action)
                                    }
                                }
                        }
                        Text(
                            "Результат открывается в отдельном окне. Его можно скопировать или сохранить в UTF-8.",
                            color = Muted,
                        )
                    }
                1 ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action(
                                "Обновить разрешения",
                                Icons.Outlined.Refresh,
                                enabled,
                                onClick = model::loadPermissions,
                            )
                            Action("Выдать", enabled = enabled && permission.isNotBlank()) {
                                model.perform(AdbAction.Grant, permission)
                            }
                            Action("Отозвать", enabled = enabled && permission.isNotBlank()) {
                                model.perform(AdbAction.Revoke, permission)
                            }
                        }
                        Text(
                            "Только объявленные опасные runtime-разрешения выбранного Android-профиля.",
                            color = Muted,
                            fontSize = 11.sp,
                        )
                        LazyColumn {
                            items(model.permissions, key = { it.name }) { item ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { permission = item.name }
                                        .padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(permission == item.name, { permission = item.name })
                                    Text(item.name, Modifier.weight(1f))
                                    Pill(
                                        when (item.granted) {
                                            true -> "Выдано"
                                            false -> "Отозвано"
                                            else -> "Неизвестно"
                                        },
                                        if (item.granted == true) Green else Amber,
                                    )
                                }
                                Divider(color = Line)
                            }
                        }
                    }
                2 ->
                    Panel(
                        "Java / JDWP",
                        "Требуется debuggable APK. IDE подключается к процессу отдельно.",
                        Modifier.fillMaxWidth(),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(AdbAction.Debug, AdbAction.RestartDebug).forEach { action ->
                                Action(action.title, enabled = enabled) { model.perform(action) }
                            }
                        }
                    }
                3 ->
                    Panel(
                        "Данные и удаление",
                        "Действия применяются только к выбранному Android-профилю и требуют подтверждения.",
                        Modifier.fillMaxWidth(),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(AdbAction.Clear, AdbAction.ClearStart, AdbAction.Uninstall)
                                .forEach { action ->
                                    Action(action.title, enabled = enabled, danger = true) {
                                        model.perform(action)
                                    }
                                }
                        }
                    }
            }
        }
    }
}

@Composable
fun ToolsScreen(model: AppState) {
    val enabled = model.busy.isEmpty() && model.selectedDevice?.state == "device"
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(
            "Сеть",
            "Команды выполняются на выбранном Android-устройстве.",
            Modifier.fillMaxWidth(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AdbAction.WifiOn, AdbAction.WifiOff, AdbAction.WifiSettings).forEach { action
                    ->
                    Action(action.title, enabled = enabled) { model.perform(action) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AdbAction.DataOn, AdbAction.DataOff).forEach { action ->
                    Action(action.title, enabled = enabled) { model.perform(action) }
                }
            }
            Action(AdbAction.Network.title, Icons.Outlined.Lan, enabled) {
                model.perform(AdbAction.Network)
            }
        }
        Panel(
            "Диагностика",
            "Полный вывод команды — в отдельном окне с копированием и экспортом.",
            Modifier.fillMaxWidth(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AdbAction.DeviceInfo, AdbAction.Processes).forEach { action ->
                    Action(action.title, enabled = enabled) { model.perform(action) }
                }
            }
        }
        Panel(
            "Файлы и APK",
            "Выберите локальный файл и путь назначения на Android.",
            Modifier.fillMaxWidth(),
        ) {
            var remote by remember { mutableStateOf("/sdcard/Download/") }
            Field(remote, { remote = it }, "Удалённый путь", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Отправить файл", Icons.Outlined.UploadFile, enabled) {
                    chooseFile()?.let { file ->
                        model.task("ADB push") {
                            model.outputs +=
                                OutputWindow(
                                    title = "ADB push",
                                    text =
                                        Adb(model.settings)
                                            .device(
                                                model.serial(),
                                                "push",
                                                file.absolutePath,
                                                remote,
                                                timeout = 120000,
                                            )
                                            .requireSuccess(),
                                )
                        }
                    }
                }
                Action("Скачать файл", Icons.Outlined.FileDownload, enabled) {
                    chooseFile(true, "android-file.bin")?.let { file ->
                        model.task("ADB pull") {
                            model.outputs +=
                                OutputWindow(
                                    title = "ADB pull",
                                    text =
                                        Adb(model.settings)
                                            .device(
                                                model.serial(),
                                                "pull",
                                                remote,
                                                file.absolutePath,
                                                timeout = 120000,
                                            )
                                            .requireSuccess(),
                                )
                        }
                    }
                }
                Action("Установить APK", Icons.Outlined.InstallMobile, enabled) {
                    chooseFile(title = "Выберите APK")?.let { file ->
                        model.confirmation =
                            Confirmation(
                                "Установить APK?",
                                "${file.absolutePath}\nУстройство: ${model.selectedDevice?.label}",
                            ) {
                                model.task("Установка APK") {
                                    val result =
                                        Adb(model.settings)
                                            .device(
                                                model.serial(),
                                                "install",
                                                "-r",
                                                file.absolutePath,
                                                timeout = 120000,
                                            )
                                            .requireSuccess()
                                    check(result.contains("Success")) { result }
                                    model.status = result
                                }
                            }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(model: AppState) {
    var draft by remember(model.settings) { mutableStateOf(model.settings) }
    var component by remember { mutableStateOf("all") }
    val enabled = model.busy.isEmpty() && !model.capturing && !model.logging
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(
            "Инструменты",
            "Внешние зависимости работают из локального каталога. Загрузка запускается отдельной командой.",
            Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(
                    draft.tools,
                    { draft = draft.copy(tools = it) },
                    "Каталог комплекта утилит",
                    Modifier.weight(1f),
                    enabled,
                )
                Action("Папка…", enabled = enabled) {
                    val dialog =
                        JFileChooser(draft.tools).apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        }
                    if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
                        draft = draft.copy(tools = dialog.selectedFile.absolutePath)
                }
                MiniIcon(Icons.Outlined.FolderOpen, "Открыть каталог") {
                    File(draft.tools).mkdirs()
                    openFile(model, File(draft.tools))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(
                    draft.adb,
                    { draft = draft.copy(adb = it) },
                    "ADB (пусто = из комплекта / PATH)",
                    Modifier.weight(1f),
                    enabled,
                )
                Action("Выбрать…", enabled = enabled) {
                    chooseFile(title = "adb.exe")?.let { draft = draft.copy(adb = it.absolutePath) }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(
                    draft.python,
                    { draft = draft.copy(python = it) },
                    "Python 3.14 x64",
                    Modifier.weight(1f),
                    enabled,
                )
                Action("Выбрать…", enabled = enabled) {
                    chooseFile(title = "python.exe")?.let {
                        draft = draft.copy(python = it.absolutePath)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Сохранить", Icons.Outlined.Save, enabled, primary = true) {
                    model.updateSettings(draft)
                    model.status = "Настройки сохранены"
                }
                Action("Проверить", Icons.Outlined.CheckCircleOutline, enabled) {
                    model.updateSettings(draft)
                    model.checkTools()
                }
            }
            Divider(color = Line)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Choice(
                    component,
                    listOf("all", "adb", "server", "gadget", "capture", "proxy", "wireguard"),
                    {
                        when (it) {
                            "all" -> "Все утилиты"
                            "adb" -> "ADB"
                            "server" -> "frida-server"
                            "gadget" -> "Gadget"
                            "capture" -> "Frida Python"
                            "proxy" -> "mitmproxy Python"
                            else -> "WireGuard APK"
                        }
                    },
                    { component = it },
                    Modifier.width(200.dp),
                    enabled,
                )
                Action("Проверить комплект", enabled = enabled) {
                    model.updateSettings(draft)
                    model.setupTools(false, component)
                }
                Action("Скачать / подготовить", Icons.Outlined.Download, enabled) {
                    model.updateSettings(draft)
                    model.setupTools(true, component)
                }
            }
            Text(
                "Подготовка использует интернет и Python 3.14 x64, устанавливает пакеты в каталог комплекта. Глобальные Python-пакеты не заменяются.",
                color = Muted,
                fontSize = 11.sp,
            )
        }
        Panel("Поведение и память", modifier = Modifier.fillMaxWidth()) {
            Check(
                "Автоматически обновлять устройства каждые 5 секунд",
                draft.autoRefresh,
                enabled,
            ) {
                draft = draft.copy(autoRefresh = it)
            }
            Check("Тёмная тема", model.settings.dark) {
                model.updateSettings(model.settings.copy(dark = it))
                draft = draft.copy(dark = it)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Строк Logcat", color = Muted)
                Choice(
                    draft.maxLogs,
                    listOf(20000, 50000, 100000),
                    { it.toString() },
                    { draft = draft.copy(maxLogs = it) },
                    Modifier.width(130.dp),
                    enabled,
                )
                Text("HTTP-запросов", color = Muted)
                Choice(
                    draft.maxRequests,
                    listOf(1000, 5000, 10000, 20000),
                    { it.toString() },
                    { draft = draft.copy(maxRequests = it) },
                    Modifier.width(130.dp),
                    enabled,
                )
            }
            Text(
                "При закрытии завершаются собственные процессы, восстанавливается прокси и удаляются собственные ADB forward/reverse. Общий ADB-сервер продолжает работать.",
                color = Muted,
                fontSize = 11.sp,
            )
            Action("Применить", Icons.Outlined.Check, enabled) {
                model.updateSettings(draft)
                model.status = "Настройки применены"
            }
        }
        Panel(
            "CA и VPN",
            "CA и ключи хранятся между запусками. При сбросе сохраняется резервная копия.",
            Modifier.fillMaxWidth(),
        ) {
            Text(AppFiles.capture.absolutePath, color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Открыть папку") { openFile(model, AppFiles.capture) }
                Action(
                    "Сбросить CA и VPN…",
                    enabled = enabled,
                    danger = true,
                    onClick = model::resetCapture,
                )
            }
        }
    }
}

@Composable
fun HelpScreen(model: AppState) {
    val topics =
        listOf(
            "Быстрый старт",
            "Android: полная настройка",
            "ADB-инструменты",
            "Logcat",
            "Просмотр тел",
            "Утилиты и загрузки",
            "Сторонние компоненты",
        )
    val files =
        listOf(
            "",
            "ANDROID_SETUP_GUIDE.md",
            "ADB_TOOLS.md",
            "LOGCAT.md",
            "BODY_VIEWER.md",
            "DOWNLOADS.md",
            "THIRD_PARTY_NOTICES.md",
        )
    var topic by remember { mutableStateOf(topics.first()) }
    val text by
        produceState("", topic) {
            value =
                withContext(Dispatchers.IO) {
                    if (topic == topics.first()) ""
                    else
                        runCatching {
                                File(AppFiles.runtime, "help/${files[topics.indexOf(topic)]}")
                                    .readText()
                            }
                            .getOrDefault("Руководство недоступно")
                }
        }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(topic, topics, { it }, { topic = it }, Modifier.width(290.dp))
            Action("Открыть папку справки", Icons.Outlined.FolderOpen) {
                openFile(model, File(AppFiles.runtime, "help"))
            }
        }
        if (topic == topics.first())
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Panel(
                    "Начните с подключения",
                    "USB или беспроводная отладка Android 11+",
                    Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "1. Включите режим разработчика и USB-отладку.\n2. Подключите телефон и подтвердите RSA-ключ.\n3. Выберите устройство в верхней панели.\n4. Обновите приложения и выберите нужное."
                    )
                    Action("Открыть устройства") { model.page = Page.Devices }
                }
                Panel("Выберите способ захвата", modifier = Modifier.fillMaxWidth()) {
                    CaptureMode.entries.forEach {
                        Text(it.title, fontWeight = FontWeight.SemiBold)
                        Text(it.hint, color = Muted)
                    }
                    Text(
                        "На экране «Трафик» нажмите значок настройки рядом с запуском: там доступны подготовка CA, VPN и параметры Frida.",
                        color = Muted,
                    )
                }
                Panel("Работа без телефона", modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "HAR-импорт и анализ запросов работают без ADB, Python и Frida. Двойной щелчок открывает отдельное окно с заголовками, телами и cURL. Экспорт HAR сохраняет видимые HTTP-запросы, включая пометки о частичном теле."
                    )
                    Action("Импортировать HAR", Icons.Outlined.FileOpen) {
                        chooseFile(title = "Импорт HAR")?.let(model::importHar)
                    }
                }
                Panel("Клавиатура и ограничения", modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Ctrl+O — открыть HAR\nCtrl+K — команды\nF1 — справка\nEnter / двойной щелчок — детали строки\nCtrl+C в Logcat — копировать выбранные строки\n\nСистемный прокси не определяет пакет/UID. VPN берёт пакет из allowlist. Pinning может блокировать HTTPS; Native TLS не гарантирует поддержку HTTP/3. Управляйте только устройствами и приложениями, на исследование которых у вас есть разрешение.",
                        color = Muted,
                    )
                }
            }
        else MonoText(text, Modifier.fillMaxSize())
    }
}
