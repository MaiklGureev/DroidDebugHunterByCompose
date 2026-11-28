package hunter.features.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hunter.app.AppState
import hunter.app.Confirmation
import hunter.app.OutputWindow
import hunter.app.Page
import hunter.core.Processes
import hunter.core.tr
import hunter.features.android.Adb
import hunter.features.android.AdbAction
import hunter.features.android.AndroidConnection
import hunter.features.android.Device
import hunter.ui.Accent
import hunter.ui.Action
import hunter.ui.Amber
import hunter.ui.Check
import hunter.ui.EmptyState
import hunter.ui.Field
import hunter.ui.Green
import hunter.ui.Line
import hunter.ui.Muted
import hunter.ui.Panel
import hunter.ui.Pill
import hunter.ui.chooseFile

val AndroidConnection.icon
    get() = when (this) {
        AndroidConnection.Usb -> Icons.Outlined.Usb
        AndroidConnection.Wifi -> Icons.Outlined.Wifi
        AndroidConnection.Emulator -> Icons.Outlined.Devices
    }

@Composable
fun DevicesScreen(model: AppState) {
    val connection = model.connection
    val state by connection.uiState.collectAsState()
    val enabled = model.busy.isEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("ADB devices"), fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(tr("Set up connection"), Icons.Outlined.Cable) { model.page = Page.Connection }
                Action(tr("Restart ADB"), enabled = enabled, onClick = connection::restartAdb)
            }
        }
        if (state.devices.isEmpty()) Text(tr("No devices"), color = Muted)
        AdbDeviceRows(state.devices, state.selectedDevice?.serial, enabled, connection::selectDevice)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(
                tr("Selected device"),
                state.selectedDevice?.label ?: tr("Select a device"),
                Modifier.weight(1f),
            ) {
                Action(
                    tr("Android, ABI, battery"),
                    Icons.Outlined.Info,
                    enabled && state.selectedDevice?.state == "device",
                ) {
                    connection.perform(AdbAction.DeviceInfo)
                }
                Action(
                    tr("Check root"),
                    Icons.Outlined.AdminPanelSettings,
                    enabled && state.selectedDevice?.state == "device",
                ) {
                    model.task(tr("Checking root")) {
                        val target = connection.serial()
                        val adb = Adb(model.settings.uiState.value)
                        val current = adb.shell(target, "id")
                        val root = adb.device(target, "shell", "su -c id", timeout = 8000)
                        model.outputs +=
                            OutputWindow(
                                title = tr("Root access"),
                                text = "adbd: $current\nsu: ${root.text}",
                            )
                    }
                }
                Action("adb root", enabled = enabled && state.selectedDevice?.state == "device",
                    onClick = connection::rootAdb)
                Text(
                    tr(
                        "unauthorized: unlock the screen and allow debugging. offline: reconnect USB or the network connection."
                    ),
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
fun AdbDeviceRows(
    devices: List<Device>,
    selectedSerial: String?,
    enabled: Boolean,
    onSelect: (Device) -> Unit,
) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        devices.forEach { device ->
            val selected = selectedSerial == device.serial
            Surface(
                shape = RoundedCornerShape(10.dp),
                border =
                    BorderStroke(
                        1.dp,
                        if (selected) Accent else Line,
                    ),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected, enabled, role = Role.RadioButton) { onSelect(device) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        device.connection.icon,
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
                    RadioButton(selected, onClick = null, enabled = enabled)
                }
            }
        }
    }
}

@Composable
fun AppsScreen(model: AppState) {
    val connection = model.connection
    val state by connection.uiState.collectAsState()
    val enabled = model.busy.isEmpty()
    val app = state.selectedApp
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
                        enabled && state.selectedDevice?.state == "device",
                        onClick = connection::loadApps,
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
                            connection.perform(action)
                        }
                    }
                    Action(tr("Traffic"), Icons.Outlined.SwapVert) { model.page = Page.Traffic }
                    Action("Logcat", Icons.Outlined.Terminal) {
                        model.logcat.setAppOnly(true)
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
                                        connection.perform(action)
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
                                onClick = connection::loadPermissions,
                            )
                            Action(tr("Grant"), enabled = enabled && permission.isNotBlank()) {
                                connection.perform(AdbAction.Grant, permission)
                            }
                            Action(tr("Revoke"), enabled = enabled && permission.isNotBlank()) {
                                connection.perform(AdbAction.Revoke, permission)
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
                            items(state.permissions, key = { it.name }) { item ->
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
                                Action(action.title, enabled = enabled) { connection.perform(action) }
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
                                        connection.perform(action)
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
    val connection = model.connection
    val state by connection.uiState.collectAsState()
    val enabled = model.busy.isEmpty() && state.selectedDevice?.state == "device"
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
                    Action(action.title, enabled = enabled) { connection.perform(action) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AdbAction.DataOn, AdbAction.DataOff).forEach { action ->
                    Action(action.title, enabled = enabled) { connection.perform(action) }
                }
            }
            Action(AdbAction.Network.title, Icons.Outlined.Lan, enabled) {
                connection.perform(AdbAction.Network)
            }
        }
        Panel(
            tr("Diagnostics"),
            tr("Full command output opens in a separate window with copy/export."),
            Modifier.fillMaxWidth(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AdbAction.DeviceInfo, AdbAction.Processes).forEach { action ->
                    Action(action.title, enabled = enabled) { connection.perform(action) }
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
                                        Adb(model.settings.uiState.value)
                                            .device(
                                                connection.serial(),
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
                                        Adb(model.settings.uiState.value)
                                            .device(
                                                connection.serial(),
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
                                    state.selectedDevice?.label,
                                ),
                            ) {
                                model.task(tr("Installing APK")) {
                                    val result =
                                        Adb(model.settings.uiState.value)
                                            .device(
                                                connection.serial(),
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
