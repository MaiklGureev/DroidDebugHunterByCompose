@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter.features.connection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hunter.app.AppState
import hunter.app.Page
import hunter.core.tr
import hunter.features.connection.CaptureAssetsState
import hunter.features.connection.ConnectionUiState
import hunter.features.settings.Settings
import hunter.features.tools.ToolComponentStatus
import hunter.features.tools.componentTitle
import hunter.features.tools.ui.ToolStatusBadge
import hunter.features.traffic.CaptureCheck
import hunter.features.traffic.CaptureMode
import hunter.features.traffic.CaptureSession
import hunter.features.traffic.WIREGUARD_PROFILE
import hunter.features.traffic.WIREGUARD_TUNNEL
import hunter.ui.ToolAction
import hunter.ui.ToolCheck
import hunter.ui.copyText

@Composable
internal fun CaptureSetupPanel(
    model: AppState,
    state: ConnectionUiState,
    settings: Settings,
    components: List<String>,
    status: (String) -> ToolComponentStatus,
    editable: Boolean,
    capturing: Boolean,
    selectDevice: () -> Unit,
    configureTools: () -> Unit,
    chooseApp: () -> Unit,
) {
    val connection = model.connection
    val connected = state.selectedDevice?.state == "device"
    val vpn = state.mode == CaptureMode.Vpn
    val appRequired = state.mode.capturesSingleApp
    val appSelected = state.selectedApp != null
    val endpointValid = !vpn || runCatching { CaptureSession.endpoint(settings) }.isSuccess
    val toolsAvailable = components.none {
        it != "wireguard" && status(it) in listOf(ToolComponentStatus.Missing, ToolComponentStatus.Failed)
    }
    ConnectionSection(tr("Requirements and next steps"), actions = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolAction(tr("User guide"), Icons.AutoMirrored.Outlined.HelpOutline) { model.page = Page.Help }
            if (capturing) {
                ToolAction(tr("Open Traffic"), Icons.Outlined.SwapVert, primary = true) {
                    model.page = Page.Traffic
                }
            } else {
                ToolAction(
                    tr("Start capture"),
                    Icons.Outlined.PlayArrow,
                    enabled = editable && connected && (!appRequired || appSelected) && endpointValid &&
                        toolsAvailable && (!vpn || state.assets.wireGuardInstalled != false),
                    primary = true,
                ) {
                    model.page = Page.Traffic
                    model.toggleCapture()
                }
            }
        }
    }) {
        Text(tr("Computer tools"), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (id in components) {
                val toolStatus = status(id)
                ToolStatusBadge(toolStatus, tr("{0} · {1}", componentTitle(id), tr(toolStatus.label)))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolAction(tr("Open tool settings"), Icons.Outlined.Build, onClick = configureTools)
            ToolAction(tr("Check readiness"), Icons.Outlined.Refresh, editable, onClick = model::checkCaptureSetup)
        }
        HorizontalDivider()
        Text(tr("Android"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CheckResult(CaptureCheck(
                    tr("Device"),
                    connected,
                    state.selectedDevice?.label ?: tr("Select an authorized Android device"),
                ))
                ToolAction(tr("Select a device"), Icons.Outlined.Devices, onClick = selectDevice)
            }
            if (appRequired) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CheckResult(CaptureCheck(tr("App"), appSelected, state.selectedApp?.label ?: tr("Select an app")))
                    ToolAction(
                        tr(if (appSelected) "Change app" else "Select an app"),
                        Icons.Outlined.Android,
                        connected && editable,
                        onClick = chooseApp,
                    )
                }
            }
        }
        state.modeChecks.filter { it.ready == false }.forEach { CheckResult(it) }
        HorizontalDivider()
        if (state.mode.isFrida) {
            Text("Frida", style = MaterialTheme.typography.titleSmall)
            ToolCheck(tr("Rootless: debuggable + run-as + Gadget"), settings.gadget, editable, connection::setGadget)
            ToolCheck(tr("Launch app at startup"), settings.spawn, editable, connection::setSpawn)
            FridaConnectionPanel(connection, state, settings.gadget, editable)
        } else {
            if (vpn) {
                WireGuardSteps(
                    model, state.assets, settings, connected && appSelected,
                    status("wireguard").ready, status("proxy").ready, endpointValid, editable,
                )
                HorizontalDivider()
            }
            CertificateSteps(state.assets, vpn, connected, status("proxy").ready, editable,
                prepare = { connection.prepareCapture(false) }, send = connection::sendCa,
            )
        }
    }
}

@Composable
private fun CertificateSteps(
    assets: CaptureAssetsState,
    vpn: Boolean,
    connected: Boolean,
    canPrepare: Boolean,
    editable: Boolean,
    prepare: () -> Unit,
    send: () -> Unit,
) {
    Text(tr("HTTPS certificate"), style = MaterialTheme.typography.titleSmall)
    CheckResult(CaptureCheck(
        tr("CA certificate"),
        assets.caAvailable,
        tr(when {
            assets.caSent -> "Certificate sent to Download/DroidDebugHunter-CA.cer"
            assets.caAvailable -> "Certificate ready to send"
            vpn -> "The CA certificate is prepared with the VPN profile"
            else -> "Prepare the CA certificate for HTTPS"
        }),
    ))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!vpn && !assets.caAvailable) {
            ToolAction(tr("Prepare CA"), Icons.Outlined.Description, editable && canPrepare, onClick = prepare)
        }
        ToolAction(tr("Send CA"), Icons.AutoMirrored.Outlined.Send, editable && connected && assets.caAvailable, onClick = send)
    }
    Text(
        tr("For HTTPS, install the CA manually in Android security settings. The target app must trust the CA."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WireGuardSteps(
    model: AppState,
    assets: CaptureAssetsState,
    settings: Settings,
    canUseDevice: Boolean,
    hasLocalApk: Boolean,
    canPrepare: Boolean,
    endpointValid: Boolean,
    editable: Boolean,
) {
    val connection = model.connection
    Text("WireGuard", style = MaterialTheme.typography.titleSmall)
    CheckResult(CaptureCheck(
        tr("WireGuard on Android"),
        assets.wireGuardInstalled,
        tr(when (assets.wireGuardInstalled) {
            true -> "WireGuard installed"
            false -> if (hasLocalApk) "Local APK is ready to install" else "Download the WireGuard APK in tool settings"
            null -> if (canUseDevice) "Check readiness to verify the WireGuard installation"
                else "Select a device and app to check its Android profile"
        }),
    ))
    if (assets.wireGuardInstalled != true) {
        ToolAction(
            tr("Install WireGuard on Android"), Icons.Outlined.InstallMobile,
            editable && canUseDevice && hasLocalApk, onClick = connection::installWireGuard,
        )
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            settings.vpnHost,
            { host -> model.settings.update { it.copy(vpnHost = host) } },
            Modifier.width(280.dp),
            enabled = editable,
            label = { Text(tr("Computer IP / DNS")) },
            singleLine = true,
        )
        OutlinedTextField(
            settings.vpnPort,
            { port -> model.settings.update { it.copy(vpnPort = port) } },
            Modifier.width(150.dp),
            enabled = editable,
            label = { Text(tr("UDP port")) },
            singleLine = true,
        )
    }
    if (!endpointValid) {
        Text(
            tr("Enter a reachable computer address and a UDP port from 1024 to 65535."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    CheckResult(CaptureCheck(
        tr("WireGuard profile"),
        assets.profileAvailable,
        when {
            assets.profileSent -> tr("Profile sent to Download/{0}", WIREGUARD_PROFILE)
            assets.profileAvailable -> tr("Profile ready to send")
            else -> tr("Prepare a profile for the selected app and endpoint")
        },
    ))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!assets.profileAvailable || !assets.caAvailable) {
            ToolAction(tr("Prepare profile"), Icons.Outlined.Description, editable && canUseDevice && endpointValid && canPrepare) {
                connection.prepareCapture(true)
            }
        }
        ToolAction(
            tr("Send VPN profile"), Icons.AutoMirrored.Outlined.Send,
            editable && canUseDevice && assets.wireGuardInstalled != false && assets.profileAvailable,
            onClick = connection::sendVpn,
        )
        ToolAction(tr("Copy tunnel name"), Icons.Outlined.ContentCopy) {
            copyText(model, WIREGUARD_TUNNEL)
        }
    }
    SelectionContainer {
        Text(tr("File: {0} · Tunnel name: {1}", WIREGUARD_PROFILE, WIREGUARD_TUNNEL))
    }
    Text(
        tr("In WireGuard, import Download/{0} and keep the name {1}. Start capture, then enable the tunnel and confirm Android VPN consent. Allow the UDP port through the computer firewall.", WIREGUARD_PROFILE, WIREGUARD_TUNNEL),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        tr("Allow Remote control in WireGuard for automatic tunnel shutdown."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
