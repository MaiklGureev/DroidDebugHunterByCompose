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
            Action(tr("Connect over network"), Icons.Outlined.Add, enabled, primary = true) {
                connection = true
            }
            Action(tr("Refresh"), Icons.Outlined.Refresh, enabled && !model.refreshing) {
                model.task(tr("Refreshing devices")) { model.refreshDevices(true) }
            }
            Action(tr("Restart ADB"), enabled = enabled, onClick = model::restartAdb)
        }
        if (model.devices.isEmpty())
            Panel(
                tr("No devices"),
                tr(
                    "Enable USB debugging, connect Android and confirm the computer's RSA key on the phone."
                ),
            ) {
                Text(
                    tr(
                        "Android 11+ supports wireless pairing. The pairing address/port differs from the connection address."
                    ),
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
                            "device" -> tr("Connected")
                            "unauthorized" -> tr("Confirm RSA on the phone")
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
                tr("Selected device"),
                model.selectedDevice?.label ?: tr("Select a device"),
                Modifier.weight(1f),
            ) {
                Action(
                    tr("Android, ABI, battery"),
                    Icons.Outlined.Info,
                    enabled && model.selectedDevice?.state == "device",
                ) {
                    model.perform(AdbAction.DeviceInfo)
                }
                Action(
                    tr("Check root"),
                    Icons.Outlined.AdminPanelSettings,
                    enabled && model.selectedDevice?.state == "device",
                ) {
                    model.task(tr("Checking root")) {
                        val target = model.serial()
                        val adb = Adb(model.settings)
                        val current = adb.shell(target, "id")
                        val root = adb.device(target, "shell", "su -c id", timeout = 8000)
                        model.outputs +=
                            OutputWindow(
                                title = tr("Root access"),
                                text = "adbd: $current\nsu: ${root.text}",
                            )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("adb root", enabled = enabled, onClick = model::rootAdb)
                    Action(
                        tr("Disconnect Wi-Fi ADB"),
                        enabled = enabled && model.selectedDevice?.serial?.contains(":") == true,
                        onClick = model::disconnect,
                    )
                }
                Text(
                    tr(
                        "unauthorized: unlock the screen and allow debugging. offline: reconnect USB or the network connection."
                    ),
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            Panel(
                "Frida",
                tr("Local bundle {0} · server selected by ABI", Adb.FRIDA_VERSION),
                Modifier.weight(1f),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(tr("Install"), Icons.Outlined.InstallMobile, enabled) {
                        model.installFrida()
                    }
                    Action(tr("Select file…"), enabled = enabled) {
                        chooseFile(title = tr("frida-server (extracted ELF)"))?.let {
                            model.installFrida(it)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(
                        tr("Launch"),
                        Icons.Outlined.PlayArrow,
                        enabled,
                        onClick = model::startFrida,
                    )
                    Action(
                        tr("Stop owned server"),
                        Icons.Outlined.Stop,
                        enabled,
                        onClick = model::stopFrida,
                    )
                }
                Action(tr("Check status"), enabled = enabled) {
                    model.task(tr("Frida status")) {
                        model.outputs +=
                            OutputWindow(
                                title = tr("Frida status"),
                                text =
                                    Adb(model.settings).shell(model.serial(), "ps -A | grep frida"),
                            )
                    }
                }
                Text(
                    tr(
                        "The server requires root. Without root, use debuggable + Gadget in capture settings. Only the server started by this session is stopped on exit."
                    ),
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
            title = { Text(tr("Connect Android")) },
            text = {
                Column(Modifier.width(430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Check(tr("Android 11+ pairing"), pairing) { pairing = it }
                    Field(
                        address,
                        { address = it },
                        if (pairing) tr("Pairing address IP:port")
                        else tr("Connection address IP:port"),
                        Modifier.fillMaxWidth(),
                    )
                    if (pairing)
                        Field(
                            code,
                            { code = it },
                            tr("6-digit code"),
                            Modifier.fillMaxWidth(),
                            password = true,
                        )
                    Text(
                        tr(
                            "Android → Developer options → Wireless debugging. After pairing, connect using the main device address."
                        ),
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
                    Text(if (pairing) tr("Pair") else tr("Connect"))
                }
            },
            dismissButton = { TextButton({ connection = false }) { Text(tr("Cancel")) } },
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
                    tr("Select an app"),
                    tr("Refresh the list and find an app by name, package, UID or App ID."),
                ) {
                    Action(
                        tr("Load apps"),
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
                        if (app.system) tr("System app") else tr("User app"),
                        if (app.system) Amber else Green,
                    )
                    Pill("UID ${app.uid ?: "?"}", Accent)
                    Pill("App ID ${app.appId}", Accent)
                    Pill(tr("Profile {0}", app.user), Accent)
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
                    Action(tr("Traffic"), Icons.Outlined.SwapVert) { model.page = Page.Traffic }
                    Action("Logcat", Icons.Outlined.Terminal) {
                        model.logOptions = model.logOptions.copy(appOnly = true)
                        model.page = Page.Logcat
                    }
                }
            }
            TabRow(tab, backgroundColor = MaterialTheme.colors.background) {
                listOf(tr("Diagnostics"), tr("Permissions"), tr("Debugging"), tr("Data"))
                    .forEachIndexed { i, label ->
                        Tab(tab == i, { tab = i }, text = { Text(label) })
                    }
            }
            when (tab) {
                0 ->
                    Panel(tr("App diagnostics"), modifier = Modifier.fillMaxWidth()) {
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
                            tr("Results open in a separate window. Copy or save them as UTF-8."),
                            color = Muted,
                        )
                    }
                1 ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action(
                                tr("Refresh permissions"),
                                Icons.Outlined.Refresh,
                                enabled,
                                onClick = model::loadPermissions,
                            )
                            Action(tr("Grant"), enabled = enabled && permission.isNotBlank()) {
                                model.perform(AdbAction.Grant, permission)
                            }
                            Action(tr("Revoke"), enabled = enabled && permission.isNotBlank()) {
                                model.perform(AdbAction.Revoke, permission)
                            }
                        }
                        Text(
                            tr(
                                "Only declared dangerous runtime permissions for the selected Android profile."
                            ),
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
                                            true -> tr("Granted")
                                            false -> tr("Revoked")
                                            else -> tr("Unknown")
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
                        tr("Requires a debuggable APK. Attach the IDE to the process separately."),
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
                        tr("Data and uninstallation"),
                        tr(
                            "Operations apply only to the selected Android profile and require confirmation."
                        ),
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
            tr("Network"),
            tr("Commands run on the selected Android device."),
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
            tr("Diagnostics"),
            tr("Full command output opens in a separate window with copy/export."),
            Modifier.fillMaxWidth(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AdbAction.DeviceInfo, AdbAction.Processes).forEach { action ->
                    Action(action.title, enabled = enabled) { model.perform(action) }
                }
            }
        }
        Panel(
            tr("Files and APKs"),
            tr("Choose a local file and its destination on Android."),
            Modifier.fillMaxWidth(),
        ) {
            var remote by remember { mutableStateOf("/sdcard/Download/") }
            Field(remote, { remote = it }, tr("Remote path"), Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(tr("Send file"), Icons.Outlined.UploadFile, enabled) {
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
                Action(tr("Download file"), Icons.Outlined.FileDownload, enabled) {
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
                Action(tr("Install APK"), Icons.Outlined.InstallMobile, enabled) {
                    chooseFile(title = tr("Select APK"))?.let { file ->
                        model.confirmation =
                            Confirmation(
                                tr("Install APK?"),
                                tr(
                                    "{0}\nDevice: {1}",
                                    file.absolutePath,
                                    model.selectedDevice?.label,
                                ),
                            ) {
                                model.task(tr("Installing APK")) {
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
    var draft by
        remember(model.settings.copy(language = UiLanguage.English, dark = false)) {
            mutableStateOf(model.settings)
        }
    var component by remember { mutableStateOf("all") }
    val enabled = model.busy.isEmpty() && !model.capturing && !model.logging
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(tr("Interface language"), modifier = Modifier.fillMaxWidth()) {
            Choice(
                model.settings.language,
                UiLanguage.entries,
                { it.nativeName },
                {
                    model.changeLanguage(it)
                    draft = draft.copy(language = it)
                },
                Modifier.width(230.dp),
            )
            Text(tr("Language changes immediately and is saved automatically."), color = Muted)
        }
        Panel(
            tr("Tools"),
            tr(
                "External dependencies use a local directory. Downloads require a separate command."
            ),
            Modifier.fillMaxWidth(),
        ) {
            Text(tr("Platform: {0}", HostPlatform.current.label), color = Accent)
            Text(
                tr(
                    "ADB and Python are selected for this OS/CPU. Enter an executable path or PATH command; a venv is supported."
                ),
                color = Muted,
                fontSize = 11.sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(
                    draft.tools,
                    { draft = draft.copy(tools = it) },
                    tr("Tool bundle directory"),
                    Modifier.weight(1f),
                    enabled,
                )
                Action(tr("Folder…"), enabled = enabled) {
                    val dialog =
                        JFileChooser(draft.tools).apply {
                            locale = java.util.Locale.forLanguageTag(model.settings.language.code)
                            dialogTitle = tr("Tool bundle directory")
                            approveButtonText = tr("Select")
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        }
                    if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
                        draft = draft.copy(tools = dialog.selectedFile.absolutePath)
                }
                MiniIcon(Icons.Outlined.FolderOpen, tr("Open directory")) {
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
                    tr("ADB (empty = bundle / PATH)"),
                    Modifier.weight(1f),
                    enabled,
                )
                Action(tr("Select…"), enabled = enabled) {
                    chooseFile(title = HostPlatform.current.executable("adb"))?.let {
                        draft = draft.copy(adb = it.absolutePath)
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(
                    draft.python,
                    { draft = draft.copy(python = it) },
                    tr("Python 3.14 (empty = auto)"),
                    Modifier.weight(1f),
                    enabled,
                )
                Action(tr("Select…"), enabled = enabled) {
                    chooseFile(
                            title = HostPlatform.current.executable(HostPlatform.current.pythonName)
                        )
                        ?.let { draft = draft.copy(python = it.absolutePath) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(tr("Save"), Icons.Outlined.Save, enabled, primary = true) {
                    model.updateSettings(draft)
                    model.status = tr("Settings saved")
                }
                Action(tr("Check"), Icons.Outlined.CheckCircleOutline, enabled) {
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
                            "all" -> tr("All tools")
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
                Action(tr("Check bundle"), enabled = enabled) {
                    model.updateSettings(draft)
                    model.setupTools(false, component)
                }
                Action(tr("Download / prepare"), Icons.Outlined.Download, enabled) {
                    model.updateSettings(draft)
                    model.setupTools(true, component)
                }
            }
            Text(
                tr(
                    "Preparation uses the internet and Python 3.14 for {0}. Packages are installed in the selected directory without replacing global packages. Choose a writable download directory.",
                    HostPlatform.current.label,
                ),
                color = Muted,
                fontSize = 11.sp,
            )
        }
        Panel(tr("Behavior and memory"), modifier = Modifier.fillMaxWidth()) {
            Check(tr("Automatically refresh devices every 5 seconds"), draft.autoRefresh, enabled) {
                draft = draft.copy(autoRefresh = it)
            }
            Check(tr("Dark theme"), model.settings.dark) {
                model.updateSettings(model.settings.copy(dark = it))
                draft = draft.copy(dark = it)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(tr("Logcat rows"), color = Muted)
                Choice(
                    draft.maxLogs,
                    listOf(20000, 50000, 100000),
                    { it.toString() },
                    { draft = draft.copy(maxLogs = it) },
                    Modifier.width(130.dp),
                    enabled,
                )
                Text(tr("HTTP requests"), color = Muted)
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
                tr(
                    "Shutdown reaps owned processes, restores the proxy and removes owned ADB forwards/reverses. The shared ADB server keeps running."
                ),
                color = Muted,
                fontSize = 11.sp,
            )
            Action(tr("Apply"), Icons.Outlined.Check, enabled) {
                model.updateSettings(draft)
                model.status = tr("Settings applied")
            }
        }
        Panel(
            tr("CA and VPN"),
            tr("CA and keys persist between launches. Reset creates a backup."),
            Modifier.fillMaxWidth(),
        ) {
            Text(AppFiles.capture.absolutePath, color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(tr("Open folder")) { openFile(model, AppFiles.capture) }
                Action(
                    tr("Reset CA and VPN…"),
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
            tr("Quick start"),
            "Windows / macOS / Linux",
            tr("Android: complete setup"),
            tr("ADB tools"),
            "Logcat",
            tr("Body viewer"),
            tr("Tools and downloads"),
            tr("Third-party components"),
        )
    val files =
        listOf(
            "",
            "PLATFORMS.md",
            "ANDROID_SETUP_GUIDE.md",
            "ADB_TOOLS.md",
            "LOGCAT.md",
            "BODY_VIEWER.md",
            "DOWNLOADS.md",
            "THIRD_PARTY_NOTICES.md",
        )
    var topic by remember { mutableStateOf(0) }
    val language = model.settings.language
    val text by
        produceState("", topic, language) {
            value =
                withContext(Dispatchers.IO) {
                    if (topic == 0) ""
                    else
                        runCatching {
                                File(AppFiles.runtime, "help/${language.helpFile(files[topic])}")
                                    .readText()
                            }
                            .getOrDefault(tr("Guide unavailable"))
                }
        }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(
                topic,
                topics.indices.toList(),
                { topics[it] },
                { topic = it },
                Modifier.width(290.dp),
            )
            Action(tr("Open help folder"), Icons.Outlined.FolderOpen) {
                openFile(model, File(AppFiles.runtime, "help"))
            }
        }
        if (topic == 0)
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Panel(
                    tr("Start by connecting"),
                    tr("USB or Android 11+ wireless debugging"),
                    Modifier.fillMaxWidth(),
                ) {
                    Text(
                        tr(
                            "1. Enable Developer options and USB debugging.\n2. Connect the phone and confirm the RSA key.\n3. Select the device in the top bar.\n4. Refresh apps and select the target."
                        )
                    )
                    Action(tr("Open devices")) { model.page = Page.Devices }
                }
                Panel(tr("Choose a capture mode"), modifier = Modifier.fillMaxWidth()) {
                    CaptureMode.entries.forEach {
                        Text(it.title, fontWeight = FontWeight.SemiBold)
                        Text(it.hint, color = Muted)
                    }
                    Text(
                        tr(
                            "On Traffic, open settings beside Start to prepare CA/VPN and configure Frida."
                        ),
                        color = Muted,
                    )
                }
                Panel(tr("Work without a phone"), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        tr(
                            "HAR import and request analysis work without ADB, Python or Frida. Double-click for headers, bodies and cURL in a separate window. HAR export preserves visible HTTP requests, including partial-body markers."
                        )
                    )
                    Action(tr("Open HAR"), Icons.Outlined.FileOpen) {
                        chooseFile(title = tr("Import HAR"))?.let(model::importHar)
                    }
                }
                Panel(tr("Keyboard and limitations"), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        tr(
                            "Ctrl+O — open HAR\nCtrl+K — commands\nF1 — help\nEnter / double-click — row details\nCtrl+C in Logcat — copy selected rows\n\nThe system proxy cannot identify package/UID. VPN attributes packages from its allowlist. Pinning can block HTTPS; Native TLS does not guarantee HTTP/3 support. Only inspect devices/apps you are authorized to test."
                        ),
                        color = Muted,
                    )
                }
            }
        else MonoText(text, Modifier.fillMaxSize())
    }
}
