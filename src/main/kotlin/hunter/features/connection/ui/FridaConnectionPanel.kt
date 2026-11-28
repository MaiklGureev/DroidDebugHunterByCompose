@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter.features.connection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hunter.core.tr
import hunter.features.connection.ConnectionUiState
import hunter.features.connection.ConnectionViewModel
import hunter.features.connection.FridaNext
import hunter.features.traffic.CaptureCheck
import hunter.ui.ToolAction
import hunter.ui.chooseFile

@Composable
fun FridaConnectionPanel(
    connection: ConnectionViewModel,
    state: ConnectionUiState,
    gadget: Boolean,
    enabled: Boolean,
) {
    val diagnosis = state.fridaDiagnosis
    val next = diagnosis?.next
    diagnosis?.checks?.forEach { CheckResult(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (next in setOf(FridaNext.Install, FridaNext.Start, FridaNext.Gadget, FridaNext.LaunchApp) && next != null) {
            val managesServer = next == FridaNext.Install || next == FridaNext.Start
            ToolAction(
                tr(next.label),
                enabled = enabled && (!managesServer || !state.ownsFridaServer),
                primary = true,
            ) {
                when (next) {
                    FridaNext.Install -> connection.installFrida()
                    FridaNext.Start -> connection.startFrida()
                    FridaNext.Gadget -> {
                        connection.setGadget(true)
                        connection.checkFrida()
                    }
                    FridaNext.LaunchApp -> {
                        connection.setSpawn(true)
                        connection.checkFrida()
                    }
                    else -> Unit
                }
            }
        }
        if (state.ownsFridaServer) {
            ToolAction(tr("Stop owned server"), Icons.Outlined.Stop, enabled, onClick = connection::stopFrida)
        }
        if (!gadget) {
            ToolAction(tr("Install server from file…"), enabled = enabled && state.selectedDevice?.state == "device" &&
                !state.ownsFridaServer) {
                chooseFile(title = tr("frida-server (extracted ELF)"))?.let { connection.installFrida(it) }
            }
        }
    }
}

@Composable
internal fun CheckResult(check: CaptureCheck) {
    val scheme = MaterialTheme.colorScheme
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
                    true -> scheme.primary
                    false -> scheme.error
                    null -> scheme.onSurfaceVariant
                },
        )
        Column {
            Text(check.title, style = MaterialTheme.typography.labelLarge)
            Text(check.detail, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
