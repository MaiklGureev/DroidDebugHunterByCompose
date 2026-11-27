@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.desktop.ui.tooling.preview.Preview
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConnectionToolsPanel(
    model: AppState,
    captureComponents: List<String>,
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
    val scope = rememberCoroutineScope()

    fun apply() {
        model.updateSettings(model.settings.copy(
            adb = draft.adb.trim(),
            python = draft.python.trim(),
            tools = draft.tools.trim(),
            setup = draft.setup,
        ))
    }

    Column(
        modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (editable && valid && event.type == KeyEventType.KeyDown &&
                event.isPlatformShortcutPressed && event.key == Key.S) {
                apply()
                true
            } else false
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToolSetupPanel(model, draft, { draft = it }, ::apply, editable, valid)
        HorizontalDivider()

        fun prepare(ids: List<String>) {
            apply()
            model.setupTools(ids)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolAction(tr("Prepare selected ({0})", selected.size), Icons.Outlined.Download,
                editable && valid && selected.isNotEmpty()) {
                prepare(selected)
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

        for (id in components) key(id) {
            if (id == "adb" || id == captureComponents.firstOrNull()) {
                Text(
                    tr(if (id == "adb") "ADB software" else "Capture software"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (id != "adb") {
                    Text(
                        tr("Python is prepared when a selected component needs it."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val detected = toolFindingIds(id).map { findings[it] ?: ToolFinding(it, null) }
            val step = model.toolSteps[id]?.takeIf { it.state != ToolStepState.Prepared }
            val destination = File(draft.tools, ToolLayout.folder(id)).absoluteFile
            val manualHint = if (valid) ToolLayout.sources(draft, id).firstOrNull()?.formatKey else null
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolListRow(
                    id, detected, step, findings.isNotEmpty(), draft.setup,
                    id in selection, id in optional, editable,
                    onChecked = { selection = if (it) selection + id else selection - id },
                ) {
                    val details = detected.joinToString("\n\n") { it.summary }
                    val text = if (step == null) details else "$details\n\n${step.detail}"
                    model.outputs += OutputWindow(title = componentTitle(id), text = text)
                }
                SelectionContainer {
                    Text(
                        tr("Bundle folder: {0}", destination.path.takeIf { draft.tools.isNotBlank() } ?: "—"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ToolComponentOptions(id, draft, { draft = it }, editable)
                manualHint?.let {
                    Text(tr(it), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolAction(tr("Open destination folder"), Icons.Outlined.FolderOpen,
                        enabled = draft.tools.isNotBlank()) {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    check(destination.isDirectory || destination.mkdirs()) {
                                        tr("Could not create directory: {0}", destination.path)
                                    }
                                }
                                openFile(model, destination)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                model.error = e.message.orEmpty()
                            }
                        }
                    }
                    ToolAction(tr("Manual download"), Icons.Outlined.Link, valid) { sources = listOf(id) }
                    ToolAction(tr("Download and prepare"), Icons.Outlined.Download,
                        editable && valid, primary = true) {
                        prepare(listOf(id))
                    }
                }
            }
            HorizontalDivider()
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
    onChecked: (Boolean) -> Unit,
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
    }
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
                    true, options, true, false, true, {}, {},
                )
                ToolListRow(
                    "python", listOf(ToolFinding("python", null)),
                    ToolStep("python", ToolStepState.Skipped, tr("Prepare Python first, or use the download links for manual setup.")),
                    true, options, false, false, true, {}, {},
                )
            }
        }
    }
}
