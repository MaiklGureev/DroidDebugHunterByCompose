@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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

val AndroidConnection.icon
    get() = when (this) {
        AndroidConnection.Usb -> Icons.Outlined.Usb
        AndroidConnection.Wifi -> Icons.Outlined.Wifi
        AndroidConnection.Emulator -> Icons.Outlined.Devices
    }

@Composable
fun AndroidConnectionWizard(model: AppState, enabled: Boolean) {
    var method by remember { mutableStateOf(model.selectedDevice?.connection ?: AndroidConnection.Usb) }
    Panel(tr("Connect Android"), modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (item in AndroidConnection.entries) {
                val selected = item == method
                val connected = model.devices.count { it.connection == item && it.state == "device" }
                Surface(
                    modifier = Modifier.width(190.dp)
                        .selectable(selected, enabled, role = Role.RadioButton) { method = item },
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, if (selected) Accent else Line),
                    color = if (selected) Accent.copy(alpha = 0.08f) else MaterialTheme.colors.surface,
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(item.icon, null, tint = if (selected) Accent else Muted)
                        Column {
                            Text(tr(item.title), fontWeight = FontWeight.SemiBold)
                            Text(tr("{0} connected", connected), fontSize = 12.sp,
                                color = if (connected > 0) Green else Muted)
                        }
                    }
                }
            }
        }
        when (method) {
            AndroidConnection.Usb -> {
                Text(tr("Enable USB debugging, connect Android and confirm the computer's RSA key on the phone."))
                if (model.devices.any { it.connection == method && it.state == "unauthorized" }) {
                    Text(tr("Confirm RSA on the phone"), color = Amber)
                }
            }
            AndroidConnection.Emulator -> {
                Text(tr("Start an Android Virtual Device in Android Studio Device Manager. Wait for Android to finish booting, then refresh the list."))
                Text(tr("Emulators with a network address can also be connected from the Wi-Fi card."), color = Muted)
            }
            AndroidConnection.Wifi -> WirelessConnection(model, enabled)
        }
        if (method != AndroidConnection.Wifi) {
            Action(tr("Refresh"), Icons.Outlined.Refresh, enabled && !model.refreshing) {
                model.task(tr("Refreshing devices")) { model.refreshDevices(true) }
            }
        }
        if (model.devices.any { it.connection == method && it.state == "device" }) {
            Text(tr("Select the connected device from the list below."), color = Muted)
        }
    }
}

@Composable
private fun WirelessConnection(model: AppState, enabled: Boolean) {
    var pairAddress by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var paired by remember { mutableStateOf(false) }
    Text(tr("Connect the phone and computer to the same network. On Android 11+, open Developer options → Wireless debugging."), color = Muted)
    Text(tr("1. Pair a new device"), fontWeight = FontWeight.SemiBold)
    Text(
        tr("Open Pair device with pairing code on Android. If this computer is already paired, go to step 2."),
        color = Muted,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(
            pairAddress,
            {
                pairAddress = it
                paired = false
            },
            tr("Pairing address IP:port"),
            Modifier.width(240.dp),
            enabled,
        )
        Field(code, { code = it }, tr("6-digit code"), Modifier.width(132.dp), enabled, password = true)
        Action(
            tr("Pair"),
            enabled = enabled && Adb.validAddress(pairAddress.trim()) && code.matches(Regex("[0-9]{6}")),
        ) {
            paired = false
            model.connect(pairAddress, code) {
                code = ""
                paired = true
            }
        }
    }
    if (paired) Text(tr("Paired. Use the connection address from the main Wireless debugging screen."), color = Green)
    Text(tr("2. Connect"), fontWeight = FontWeight.SemiBold)
    Text(tr("Use the address on the main Wireless debugging screen. The pairing port is different."), color = Muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(address, { address = it }, tr("Connection address IP:port"), Modifier.width(240.dp), enabled)
        Action(tr("Connect"), enabled = enabled && Adb.validAddress(address.trim()), primary = true) {
            model.connect(address)
        }
        Action(tr("Find Wi-Fi devices"), Icons.Outlined.Search, enabled, onClick = model::discoverWireless)
    }
    if (model.wirelessAddresses.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (endpoint in model.wirelessAddresses) {
                Action(endpoint, enabled = enabled) { address = endpoint }
            }
        }
    }
}
