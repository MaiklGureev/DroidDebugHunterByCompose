@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter

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

@Composable
fun FridaConnectionPanel(
    model: AppState,
    forCapture: Boolean,
    enabled: Boolean,
    openCapture: () -> Unit = {},
    dismiss: () -> Unit = {},
) {
    val diagnosis = model.fridaDiagnosis(forCapture)
    val next = diagnosis?.next
    diagnosis?.checks?.forEach { CheckResult(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (next != null && next != FridaNext.Retry && (!forCapture || next != FridaNext.Ready) &&
            (forCapture || next != FridaNext.Device)) {
            val managesServer = next == FridaNext.Install || next == FridaNext.Start
            Action(
                tr(if (next == FridaNext.Ready) "Open capture setup" else next.label),
                enabled = enabled && (!managesServer || !model.ownsFridaServer()),
                primary = true,
            ) {
                when (next) {
                    FridaNext.Device -> {
                        model.page = Page.Devices
                        dismiss()
                    }
                    FridaNext.Tools -> {
                        model.page = Page.Settings
                        dismiss()
                    }
                    FridaNext.App -> {
                        model.page = Page.Apps
                        if (model.apps.isEmpty()) model.loadApps()
                        dismiss()
                    }
                    FridaNext.Install -> model.installFrida(forCapture = forCapture)
                    FridaNext.Start -> model.startFrida(forCapture)
                    FridaNext.Gadget -> {
                        model.updateSettings(model.settings.copy(gadget = true))
                        model.checkFrida(forCapture)
                    }
                    FridaNext.LaunchApp -> {
                        model.updateSettings(model.settings.copy(spawn = true))
                        model.checkFrida(forCapture)
                    }
                    FridaNext.Ready -> openCapture()
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
            model.checkFrida(forCapture)
        }
        if (model.ownsFridaServer()) {
            Action(tr("Stop owned server"), Icons.Outlined.Stop, enabled, onClick = model::stopFrida)
        }
        if (!forCapture) {
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
