@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter.features.connection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hunter.app.AppState
import hunter.app.Page
import hunter.core.tr
import hunter.features.android.Device
import hunter.features.connection.FridaNext
import hunter.features.traffic.CaptureCheck
import hunter.ui.Action
import hunter.ui.Amber
import hunter.ui.Check
import hunter.ui.Green
import hunter.ui.Muted
import hunter.ui.Red
import hunter.ui.chooseFile

@Composable
fun FridaConnectionPanel(
    model: AppState,
    enabled: Boolean,
    selectDevice: () -> Unit,
    configureTools: () -> Unit,
) {
    val diagnosis = model.fridaDiagnosis()
    val next = diagnosis?.next
    diagnosis?.checks?.forEach { CheckResult(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (next != null && next != FridaNext.Retry && next != FridaNext.Ready) {
            val managesServer = next == FridaNext.Install || next == FridaNext.Start
            Action(
                tr(next.label),
                enabled = enabled && (!managesServer || !model.ownsFridaServer()),
                primary = true,
            ) {
                when (next) {
                    FridaNext.Device -> selectDevice()
                    FridaNext.Tools -> configureTools()
                    FridaNext.App -> {
                        model.page = Page.Traffic
                        if (model.apps.isEmpty()) model.loadApps()
                    }
                    FridaNext.Install -> model.installFrida()
                    FridaNext.Start -> model.startFrida()
                    FridaNext.Gadget -> {
                        model.updateSettings(model.settings.copy(gadget = true))
                        model.checkFrida()
                    }
                    FridaNext.LaunchApp -> {
                        model.updateSettings(model.settings.copy(spawn = true))
                        model.checkFrida()
                    }
                    FridaNext.Ready,
                    FridaNext.Retry -> Unit
                }
            }
        }
        Action(
            tr(if (next == null) "Check Frida connection" else "Check again"),
            Icons.Outlined.Refresh,
            enabled,
            primary = next == null || next == FridaNext.Retry,
        ) {
            model.checkFrida()
        }
        if (model.ownsFridaServer()) {
            Action(tr("Stop owned server"), Icons.Outlined.Stop, enabled, onClick = model::stopFrida)
        }
        if (!model.settings.gadget) {
            Action(tr("Select file…"), enabled = enabled && model.selectedDevice?.state == "device" &&
                !model.ownsFridaServer()) {
                chooseFile(title = tr("frida-server (extracted ELF)"))?.let { model.installFrida(it) }
            }
        }
    }
}

@Composable
fun CheckResult(check: CaptureCheck) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            when (check.ready) {
                true -> Icons.Outlined.CheckCircleOutline
                false -> Icons.Outlined.ErrorOutline
                null -> Icons.Outlined.Info
            },
            null,
            Modifier.size(17.dp),
            tint =
                when (check.ready) {
                    true -> Green
                    false -> Red
                    null -> Amber
                },
        )
        Column {
            Text(check.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(check.detail, color = Muted, fontSize = 11.sp)
        }
    }
}
