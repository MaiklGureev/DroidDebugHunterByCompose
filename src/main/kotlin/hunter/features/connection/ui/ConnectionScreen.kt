@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package hunter.features.connection.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hunter.app.AppState
import hunter.app.Page
import hunter.core.tr
import hunter.features.connection.FridaNext
import hunter.features.tools.ToolComponentStatus
import hunter.features.tools.toolComponents
import hunter.features.tools.toolConfiguration
import hunter.features.tools.ui.ConnectionToolsPanel
import hunter.features.traffic.CaptureMode
import hunter.ui.HunterTheme
import kotlinx.coroutines.launch

@Composable
fun ConnectionScreen(model: AppState) {
    val connection = model.connection
    val state by connection.uiState.collectAsState()
    val settings by model.settings.uiState.collectAsState()
    val tools by model.toolSetup.uiState.collectAsState()
    val idle = model.initialized && model.busy.isEmpty()
    val capturing by model.traffic.running.collectAsState(model.traffic.uiState.value.running)
    val editable = idle && !capturing
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val toolSettings = remember { BringIntoViewRequester() }
    val components = state.mode.toolComponents(settings.gadget)
    val diagnosed = state.fridaDiagnosis
    val availableOnAndroid = buildSet {
        if (state.mode == CaptureMode.Vpn && state.assets.wireGuardInstalled == true) add("wireguard")
        if (state.mode.requiresRoot(settings.gadget) &&
            diagnosed?.next in setOf(FridaNext.Start, FridaNext.LaunchApp, FridaNext.Ready)
        ) add("server")
    }
    val componentStatus: (String) -> ToolComponentStatus = { id ->
        when {
            id == "capture" && diagnosed?.next == FridaNext.Tools && diagnosed.requiredAbi == null ->
                ToolComponentStatus.Failed
            id == "server" && !settings.gadget || id == "gadget" && settings.gadget ->
                tools.componentStatus(id, diagnosed?.requiredAbi)
            id == "capture" -> tools.componentStatus(id, nativeCapture = state.mode == CaptureMode.Native)
            else -> tools.componentStatus(id)
        }
    }

    LaunchedEffect(idle, settings.toolConfiguration()) {
        if (idle) model.toolSetup.refreshIfNeeded()
    }
    LaunchedEffect(idle, state.mode, state.selectedDevice, state.selectedApp, settings) {
        if (idle) connection.refreshCaptureAssets()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AndroidConnectionWizard(connection, state, idle)
            ConnectionSection(tr("Capture method")) {
                CaptureMethodPicker(state.mode, settings.gadget, editable, connection::setMode)
            }
            CaptureSetupPanel(
                model = model,
                state = state,
                settings = settings,
                components = listOf("adb") + components.filterNot { it in availableOnAndroid },
                status = componentStatus,
                editable = editable,
                capturing = capturing,
                selectDevice = { scope.launch { scroll.animateScrollTo(0) } },
                configureTools = { scope.launch { toolSettings.bringIntoView(Rect(0f, 0f, 1f, 32f)) } },
                chooseApp = {
                    model.page = Page.Traffic
                    if (state.apps.isEmpty()) connection.loadApps()
                },
            )
            ConnectionToolsPanel(
                model = model,
                captureComponents = components,
                modifier = Modifier.bringIntoViewRequester(toolSettings),
                componentStatus = componentStatus,
                availableOnAndroid = availableOnAndroid,
            )
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
internal fun ConnectionSection(
    title: String,
    actions: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                actions?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun CaptureMethodPicker(
    current: CaptureMode,
    gadget: Boolean,
    editable: Boolean,
    select: (CaptureMode) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in CaptureMode.entries) {
            val selected = mode == current
            val icon = when (mode) {
                CaptureMode.Proxy -> Icons.Outlined.Language
                CaptureMode.OkHttp, CaptureMode.Native -> Icons.Outlined.BugReport
                CaptureMode.Vpn -> Icons.Outlined.VpnKey
            }
            Surface(
                modifier = Modifier.width(184.dp)
                    .pointerHoverIcon(if (editable) PointerIcon.Hand else PointerIcon.Default)
                    .selectable(selected, editable, role = Role.RadioButton) { select(mode) },
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant),
                color = if (selected) scheme.secondaryContainer else scheme.surface,
                contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
            ) {
                Row(
                    Modifier.heightIn(min = 64.dp).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, contentDescription = null, Modifier.size(20.dp))
                    Column {
                        Text(mode.title, style = MaterialTheme.typography.labelLarge)
                        Text(
                            tr(when {
                                mode.requiresRoot(gadget) -> "Root required"
                                mode.isFrida -> "Gadget · No root required"
                                else -> "No root required"
                            }),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    Text(current.hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
}

@Preview
@Composable
private fun LightCaptureMethodsPreview() = CaptureMethodsPreview(dark = false)

@Preview
@Composable
private fun DarkCaptureMethodsPreview() = CaptureMethodsPreview(dark = true)

@Composable
private fun CaptureMethodsPreview(dark: Boolean) {
    HunterTheme(dark) {
        ConnectionSection(tr("Capture method")) {
            CaptureMethodPicker(CaptureMode.Native, gadget = dark, editable = true, select = {})
        }
    }
}
