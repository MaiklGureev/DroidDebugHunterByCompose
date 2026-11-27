@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.desktop.ui.tooling.preview.Preview

@Composable
fun ConnectionToolsPanel(
    model: AppState,
    captureComponents: List<String>,
    expanded: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = model.settings
    val required = listOf("adb") + captureComponents
    val optional = when {
        "server" in required -> listOf("gadget")
        "gadget" in required -> listOf("server")
        else -> emptyList()
    }
    val components = required + optional
    var selection by remember(required) { mutableStateOf(emptySet<String>()) }
    val selected = components.filter { it in selection }
    val findings = model.toolFindings.associateBy { it.id }
    val unfinished = components.filter {
        model.toolSteps[it]?.state?.let { state -> state != ToolStepState.Prepared } == true
    }
    var sources by remember(required) { mutableStateOf(emptyList<String>()) }
    var draft by remember(required, config.toolConfiguration(), config.setup) { mutableStateOf(config) }
    val valid = draft.tools.isNotBlank() && ToolPolicy.ids.all { ToolPolicy.validVersion(draft.setup.version(it)) }
    val editable = model.canPrepareTools
    val checkingAllowed = model.initialized && model.busy.isEmpty()
    val needsPython = selected.any { it in listOf("python", "capture", "proxy", "server", "gadget") }

    fun apply() {
        model.updateSettings(model.settings.copy(
            adb = draft.adb.trim(),
            python = draft.python.trim(),
            tools = draft.tools.trim(),
            setup = draft.setup,
        ))
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(tr("Tool setup"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            ToolAction(
                tr(if (expanded) "Hide tool settings" else "Tool settings"),
                Icons.Outlined.Settings,
                onClick = onExpand,
            )
        }
        Text(
            listOfNotNull(
                tr(draft.setup.mode.key),
                tr(draft.setup.method.key).takeIf { needsPython && draft.setup.mode != SetupMode.Manual },
                tr("Offline").takeIf { draft.setup.offline },
            ).joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )

        fun toggle(id: String, checked: Boolean) {
            selection = if (checked) selection + id else selection - id
        }
        Text(tr("ADB software"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        for (id in components) {
            if (id == captureComponents.firstOrNull()) {
                Text(tr("Capture software"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            val detected = toolFindingIds(id).map { findings[it] ?: ToolFinding(it, null) }
            val step = model.toolSteps[id]?.takeIf { it.state != ToolStepState.Prepared }
            ToolListRow(
                id, detected, step, findings.isNotEmpty(), draft.setup, id in selection, id in optional,
                editable, valid, { toggle(id, it) }, { sources = listOf(id) },
            ) {
                val details = detected.joinToString("\n\n") { it.summary }
                val text = if (step == null) details else "$details\n\n${step.detail}"
                model.outputs += OutputWindow(title = componentTitle(id), text = text)
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (draft.setup.mode == SetupMode.Manual) {
                ToolAction(tr("Download links / folders"), Icons.Outlined.Link,
                    valid && selected.isNotEmpty(), primary = true) {
                    sources = selected
                }
            } else {
                ToolAction(tr("Prepare selected ({0})", selected.size), Icons.Outlined.Download,
                    editable && valid && selected.isNotEmpty(), primary = true) {
                    apply()
                    model.setupTools(selected)
                }
            }
            ToolAction(tr("Check tools"), Icons.Outlined.Refresh, checkingAllowed && (!editable || valid)) {
                if (editable) apply()
                model.checkTools(refreshAdb = true)
            }
            if (selection.containsAll(required)) {
                ToolAction(tr("Clear selection"), enabled = editable) { selection = emptySet() }
            } else {
                ToolAction(tr("Select required"), enabled = editable) { selection = required.toSet() }
            }
            if (unfinished.isNotEmpty()) {
                ToolAction(tr("Select unfinished"), enabled = editable) { selection = unfinished.toSet() }
            }
        }
        if (needsPython && "python" !in selected && draft.setup.mode != SetupMode.Manual) {
            Text(tr("Python is prepared when a selected component needs it."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        model.setupProgress?.let { progress ->
            Text("${progress.phase} · ${progress.detail}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            val fraction = progress.fraction
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
                Text("${(fraction * 100).toInt()}% · ${progress.bytes / 1024} KiB", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            ToolAction(tr("Cancel"), Icons.Outlined.Close, onClick = model::cancelOperation)
        }
        if (model.setupLiveLog.isNotBlank()) {
            ToolAction(tr("Setup log"), Icons.Outlined.Description) {
                model.outputs += OutputWindow(title = tr("Setup log"), text = model.setupLiveLog)
            }
        }
        if (expanded) {
            HorizontalDivider()
            ToolSetupPanel(model, draft, { draft = it }, ::apply, components, selected, editable, valid)
        }
    }
    if (sources.isNotEmpty()) ToolSourcesDialog(model, draft, sources) { sources = emptyList() }
}

@Composable
private fun ToolListRow(
    id: String,
    detected: List<ToolFinding>,
    step: ToolStep?,
    inspected: Boolean,
    options: ToolSetupOptions,
    checked: Boolean,
    optional: Boolean,
    enabled: Boolean,
    showSources: Boolean,
    onChecked: (Boolean) -> Unit,
    onSources: () -> Unit,
    onDetails: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (status, container, content) = when {
        !inspected -> Triple(tr("Not checked"), scheme.surfaceVariant, scheme.onSurfaceVariant)
        detected.any { it.failed } -> Triple(tr("Check required"), scheme.errorContainer, scheme.onErrorContainer)
        detected.any { it.actual == null } -> Triple(tr("Not found"), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        detected.any { it.warning } -> Triple(tr("Different version"), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        else -> Triple(tr("Ready"), scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .background(if (hovered) scheme.surfaceVariant else Color.Transparent, MaterialTheme.shapes.small)
                .hoverable(interaction, enabled)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChecked),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked, onCheckedChange = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                val title = componentTitle(id)
                Text(if (optional) tr("{0} (optional)", title) else title, style = MaterialTheme.typography.bodyMedium)
                Text(detected.joinToString(" · ") { finding ->
                    val version = "${finding.actual ?: "—"} / ${options.version(finding.versionId)}"
                    if (detected.size > 1) "${finding.title} $version" else tr("Detected / selected: {0}", version)
                }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (step != null) {
                    val reason = step.detail.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
                    val label = tr(if (step.state == ToolStepState.Skipped) "Skipped" else "Failed")
                    Text("$label · $reason", color = scheme.error, style = MaterialTheme.typography.labelSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Badge(containerColor = container, contentColor = content) {
            Text(status, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
        ToolAction(tr("Details"), Icons.Outlined.Info, onClick = onDetails)
        ToolAction(tr("Files"), Icons.Outlined.FolderOpen, showSources, onClick = onSources)
    }
    HorizontalDivider()
}

@Preview
@Composable
private fun LightToolListPreview() = ToolListPreview(dark = false)

@Preview
@Composable
private fun DarkToolListPreview() = ToolListPreview(dark = true)

@Composable
private fun ToolListPreview(dark: Boolean) {
    HunterTheme(dark) {
        Surface {
            Column(Modifier.width(600.dp).padding(16.dp)) {
                val options = ToolSetupOptions()
                ToolListRow(
                    "adb", listOf(ToolFinding("adb", options.version("adb"))), null,
                    true, options, true, false, true, true, {}, {}, {},
                )
                ToolListRow(
                    "python", listOf(ToolFinding("python", null)),
                    ToolStep("python", ToolStepState.Skipped, tr("Prepare Python first, or use the download links for manual setup.")),
                    true, options, false, false, true, true, {}, {}, {},
                )
            }
        }
    }
}
