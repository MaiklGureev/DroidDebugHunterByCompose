@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ConnectionScreen(model: AppState) {
    val idle = model.initialized && model.busy.isEmpty()
    val editable = idle && !model.capturing
    val connected = model.selectedDevice?.state == "device"
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AndroidConnectionWizard(model, idle)
            Panel(tr("Capture method"), modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (mode in CaptureMode.entries) {
                        val selected = mode == model.mode
                        val icon = when (mode) {
                            CaptureMode.Proxy -> Icons.Outlined.Language
                            CaptureMode.OkHttp, CaptureMode.Native -> Icons.Outlined.BugReport
                            CaptureMode.Vpn -> Icons.Outlined.VpnKey
                        }
                        Surface(
                            modifier = Modifier.width(178.dp)
                                .selectable(selected, editable, role = Role.RadioButton) { model.mode = mode },
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, if (selected) Accent else Line),
                            color = if (selected) Accent.copy(alpha = 0.08f) else MaterialTheme.colors.surface,
                        ) {
                            Row(
                                Modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(icon, null, Modifier.size(19.dp), tint = if (selected) Accent else Muted)
                                Text(mode.title, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Text(model.mode.hint, color = Muted)
            }

            if (model.mode.isFrida) {
                Panel(
                    "Frida",
                    tr("Target Frida {0} · server matches the active client and ABI", Adb.FRIDA_VERSION),
                    Modifier.fillMaxWidth(),
                ) {
                    Check(tr("Rootless: debuggable + run-as + Gadget"), model.settings.gadget, editable) {
                        model.updateSettings(model.settings.copy(gadget = it))
                    }
                    Check(tr("Launch app at startup"), model.settings.spawn, editable) {
                        model.updateSettings(model.settings.copy(spawn = it))
                    }
                    FridaConnectionPanel(model, editable) { scope.launch { scroll.animateScrollTo(0) } }
                }
            } else {
                Panel(tr("HTTPS certificate"), modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Prepare the CA, send it to Android and install it manually as a CA certificate. The app must trust user CAs."))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action(tr("Prepare CA"), enabled = editable) { model.prepareCapture(false) }
                        Action(tr("Send CA"), enabled = idle && connected, onClick = model::sendCa)
                    }
                }
                if (model.mode == CaptureMode.Vpn) {
                    WireGuardSettings(model, editable, connected)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Action(tr("Open Traffic"), Icons.Outlined.SwapVert, primary = true) {
                    model.page = Page.Traffic
                }
                if (!model.mode.isFrida) {
                    Action(tr("Check readiness"), enabled = editable, onClick = model::checkCapture)
                }
                Action(tr("User guide"), Icons.AutoMirrored.Outlined.HelpOutline) {
                    model.page = Page.Help
                }
            }
            if (!model.mode.isFrida) model.modeChecks.forEach { CheckResult(it) }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun WireGuardSettings(model: AppState, editable: Boolean, connected: Boolean) {
    val app = model.selectedApp
    Panel("WireGuard", modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(
                model.settings.vpnHost,
                { model.updateSettings(model.settings.copy(vpnHost = it)) },
                tr("Computer IP / DNS"),
                Modifier.weight(1f),
                editable,
            )
            Field(
                model.settings.vpnPort,
                { model.updateSettings(model.settings.copy(vpnPort = it)) },
                tr("UDP port"),
                Modifier.width(130.dp),
                editable,
            )
        }
        Text(
            if (app == null) tr("Choose the target app on Traffic to prepare its VPN profile.")
            else tr("App: {0}", app.label),
            color = Muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Action(tr("Prepare profile"), enabled = editable && app != null) {
                model.prepareCapture(true)
            }
            Action(tr("Send to WireGuard"), enabled = editable && connected && app != null,
                onClick = model::sendVpn)
        }
        Text(
            tr("Allow the UDP port in your firewall. WireGuard: + → Import from file → Download/ddh.conf. Enable the tunnel after starting the server. Allow Remote control for automatic shutdown."),
            color = Muted,
        )
    }
}
