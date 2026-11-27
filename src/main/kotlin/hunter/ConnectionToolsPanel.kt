@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
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
    val optional = when {
        "server" in captureComponents -> listOf("gadget")
        "gadget" in captureComponents -> listOf("server")
        else -> emptyList()
    }
    val batchComponents = captureComponents + optional
    val components = listOf("adb") + batchComponents
    val findings = model.toolFindings.associateBy { it.id }
    val unfinished = batchComponents.filter {
        model.toolSteps[it]?.state?.let { state -> state != ToolStepState.Prepared } == true
    }
    var sources by remember(captureComponents) { mutableStateOf(emptyList<String>()) }
    var draft by remember(captureComponents, config.toolConfiguration(), config.setup) { mutableStateOf(config) }
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
        ToolAction(tr("Check tools"), Icons.Outlined.Refresh, checkingAllowed && (!editable || valid)) {
            if (editable) apply()
            model.checkTools(refreshAdb = true)
        }

        for (id in components) key(id) {
            if (id == "adb" || id == captureComponents.firstOrNull()) {
                Text(
                    tr(if (id == "adb") "ADB software" else "Capture software"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (id != "adb") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ToolAction(tr("Prepare capture tools"), Icons.Outlined.Download, editable && valid) {
                            prepare(captureComponents)
                        }
                        if (optional.isNotEmpty()) {
                            ToolAction(componentTitle("frida"), Icons.Outlined.Download, editable && valid) {
                                prepare(batchComponents)
                            }
                        }
                        if (unfinished.isNotEmpty()) {
                            ToolAction(tr("Retry unfinished components"), Icons.Outlined.Refresh, editable && valid) {
                                prepare(unfinished)
                            }
                        }
                    }
                    Text(
                        tr("Tools that require Python prepare it as needed."),
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
                ToolListRow(id, detected, step, findings.isNotEmpty(), draft.setup, id in optional) {
                    val details = detected.joinToString("\n\n") { it.summary }
                    val text = if (step == null) details else "$details\n\n${step.detail}"
                    model.outputs += OutputWindow(title = componentTitle(id), text = text)
                }
                ToolDownloadVersions(detected.map { it.versionId }, draft.setup, editable) { versions ->
                    draft = draft.copy(setup = draft.setup.copy(versions = draft.setup.versions + versions))
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
    optional: Boolean,
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
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            val title = componentTitle(id)
            Text(if (optional) tr("{0} (optional)", title) else title, style = MaterialTheme.typography.bodyMedium)
            for (finding in detected) {
                val versions = tr(
                    "Detected: {0} · Download version: {1}",
                    finding.actual ?: "—",
                    options.version(finding.versionId),
                )
                Text(
                    if (detected.size > 1) "${finding.title} · $versions" else versions,
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (step != null) {
                val reason = step.detail.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
                val label = tr(if (step.state == ToolStepState.Skipped) "Skipped" else "Failed")
                Text("$label · $reason", color = scheme.error, style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    true, options, false, {},
                )
                ToolListRow(
                    "python", listOf(ToolFinding("python", null)),
                    ToolStep("python", ToolStepState.Skipped, tr("Prepare Python first, or use the download links for manual setup.")),
                    true, options, false, {},
                )
                ToolListRow(
                    "proxy",
                    toolFindingIds("proxy").map { ToolFinding(it, options.version(it)) },
                    null, true, options, false, {},
                )
            }
        }
    }
}
