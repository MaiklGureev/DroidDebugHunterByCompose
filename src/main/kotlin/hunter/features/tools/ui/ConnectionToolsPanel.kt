@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter.features.tools.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hunter.app.AppState
import hunter.app.OutputWindow
import hunter.core.tr
import hunter.features.tools.ToolFinding
import hunter.features.tools.ToolComponentStatus
import hunter.features.tools.ToolLayout
import hunter.features.tools.ToolPolicy
import hunter.features.tools.ToolSetupOptions
import hunter.features.tools.ToolStep
import hunter.features.tools.ToolStepState
import hunter.features.tools.componentTitle
import hunter.features.tools.needsToolPreparation
import hunter.features.tools.toolConfiguration
import hunter.features.tools.toolFindingIds
import hunter.ui.Check
import hunter.ui.HunterTheme
import hunter.ui.ToolAction
import hunter.ui.isPlatformShortcutPressed
import hunter.ui.openFile
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
    componentStatus: (String) -> ToolComponentStatus,
    availableOnAndroid: Set<String>,
) {
    val toolSetup = model.toolSetup
    val connectionState by model.connection.uiState.collectAsState()
    val toolState by toolSetup.uiState.collectAsState()
    val config by model.settings.uiState.collectAsState()
    val optional = when {
        "server" in captureComponents -> listOf("gadget")
        "gadget" in captureComponents -> listOf("server")
        else -> emptyList()
    }
    val batchComponents = captureComponents + optional
    val components = listOf("adb") + batchComponents
    val findings = toolState.findings.associateBy { it.id }
    var sources by remember(captureComponents) { mutableStateOf(emptyList<String>()) }
    var draft by remember(captureComponents, config.toolConfiguration(), config.setup) { mutableStateOf(config) }
    val valid = draft.tools.isNotBlank() && ToolPolicy.ids.all { ToolPolicy.validDownloadVersion(it, draft.setup.version(it)) }
    val capturing by model.traffic.running.collectAsState(model.traffic.uiState.value.running)
    val logging by model.logcat.running.collectAsState(model.logcat.uiState.value.running)
    val editable = model.initialized && model.busy.isEmpty() && !capturing && !logging && !connectionState.ownsFridaServer
    val checkingAllowed = model.initialized && model.busy.isEmpty()
    val scope = rememberCoroutineScope()
    val hasGoogleAdbArchive = ToolLayout.hasGoogleAdbArchive()
    val canPrepareAdb = hasGoogleAdbArchive || draft.setup.adbArchive.isNotBlank()

    fun needsPreparation(id: String): Boolean =
        needsToolPreparation(id, componentStatus(id), id in availableOnAndroid, config, draft)

    val pendingRequired = captureComponents.filter(::needsPreparation)
    val pendingBatch = batchComponents.filter(::needsPreparation)
    val unfinished = toolState.unfinished(batchComponents).filter(::needsPreparation)

    fun apply() = toolSetup.save(draft)

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
        ToolSetupPanel(
            draft, { draft = it }, ::apply, editable, valid, toolState.directoryWarnings,
            onCheckFolders = { toolSetup.checkFolders(draft) },
        )
        HorizontalDivider()

        fun prepare(ids: List<String>) = toolSetup.prepare(ids, draft)
        ToolAction(tr("Check tools"), Icons.Outlined.Refresh, checkingAllowed && (!editable || valid)) {
            toolSetup.checkTools(refreshAdb = true, draft = draft.takeIf { editable })
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
                        if (pendingRequired.isNotEmpty() && pendingRequired != unfinished) {
                            ToolAction(tr("Prepare missing tools"), Icons.Outlined.Download, editable && valid) {
                                prepare(pendingRequired)
                            }
                        }
                        if (optional.isNotEmpty() && pendingBatch != pendingRequired && pendingBatch != unfinished) {
                            ToolAction(componentTitle("frida"), Icons.Outlined.Download, editable && valid) {
                                prepare(pendingBatch)
                            }
                        }
                        if (unfinished.isNotEmpty()) {
                            ToolAction(tr("Retry unfinished components"), Icons.Outlined.Refresh, editable && valid) {
                                toolSetup.retryUnfinished(unfinished, draft)
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
            val step = toolState.steps[id]?.takeIf { it.state != ToolStepState.Prepared }
            val destination = File(draft.tools, ToolLayout.folder(id)).absoluteFile
            val manualHint = if (valid) ToolLayout.sources(draft, id).firstOrNull()?.formatKey else null
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolListRow(id, detected, step, componentStatus(id), draft.setup, id in optional) {
                    val details = detected.joinToString("\n\n") { it.summary }
                    val text = if (step == null) details else "$details\n\n${step.detail}"
                    model.outputs += OutputWindow(title = componentTitle(id), text = text)
                }
                val prepareLatest: (() -> Unit)? = if (id == "adb" && valid && hasGoogleAdbArchive) {
                    {
                        val latest = draft.setup.versions + ("adb" to ToolPolicy.ADB_LATEST)
                        toolSetup.prepare(listOf("adb"), draft.copy(setup = draft.setup.copy(versions = latest)))
                    }
                } else null
                if (id != "adb" || canPrepareAdb) {
                    ToolDownloadVersions(detected.map { it.versionId }, draft.setup, editable, prepareLatest) { versions ->
                        draft = draft.copy(setup = draft.setup.copy(versions = draft.setup.versions + versions))
                    }
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
                    if (needsPreparation(id) && (id != "adb" || canPrepareAdb)) {
                        val label = if (id == "adb" && !hasGoogleAdbArchive) "Prepare local ZIP" else "Download and prepare"
                        ToolAction(tr(label), Icons.Outlined.Download,
                            editable && valid, primary = true) {
                            prepare(listOf(id))
                        }
                    }
                }
            }
            HorizontalDivider()
        }

        toolState.progress?.let { progress ->
            Text("${progress.phase} · ${progress.detail}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            val fraction = progress.fraction
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
                Text("${(fraction * 100).toInt()}% · ${progress.bytes / 1024} KiB", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            ToolAction(tr("Cancel"), Icons.Outlined.Close, onClick = toolSetup::cancel)
        }
        if (toolState.liveLog.isNotBlank()) {
            ToolAction(tr("Setup log"), Icons.Outlined.Description) {
                model.outputs += OutputWindow(title = tr("Setup log"), text = toolState.liveLog)
            }
        }
    }
    if (sources.isNotEmpty()) {
        ToolSourcesDialog(
            model, draft, sources, toolState, allowDownload = sources.any(::needsPreparation),
        ) { sources = emptyList() }
    }
}

@Composable
internal fun ToolStatusBadge(status: ToolComponentStatus, label: String = tr(status.label)) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        ToolComponentStatus.Unchecked -> scheme.surfaceVariant to scheme.onSurfaceVariant
        ToolComponentStatus.Failed -> scheme.errorContainer to scheme.onErrorContainer
        ToolComponentStatus.Missing, ToolComponentStatus.DifferentVersion ->
            scheme.tertiaryContainer to scheme.onTertiaryContainer
        ToolComponentStatus.Ready -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    Badge(containerColor = container, contentColor = content) {
        Text(label, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ToolListRow(
    id: String,
    detected: List<ToolFinding>,
    step: ToolStep?,
    status: ToolComponentStatus,
    options: ToolSetupOptions,
    optional: Boolean,
    onDetails: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            val title = componentTitle(id)
            Text(if (optional) tr("{0} (optional)", title) else title, style = MaterialTheme.typography.bodyMedium)
            for (finding in detected) {
                val versionLabel = if (finding.versionId == "adb" && !ToolLayout.hasGoogleAdbArchive()) {
                    when {
                        options.adbArchive.isBlank() -> "—"
                        options.adbLatest -> tr("Version from ZIP")
                        else -> options.version("adb")
                    }
                } else options.downloadVersionLabel(finding.versionId)
                val versions = tr(
                    "Detected: {0} · Download version: {1}",
                    finding.actual ?: "—",
                    versionLabel,
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
        ToolStatusBadge(status)
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
                    ToolComponentStatus.Ready, options, false, {},
                )
                ToolListRow(
                    "python", listOf(ToolFinding("python", null)),
                    ToolStep("python", ToolStepState.Skipped, tr("Prepare Python first, or use the download links for manual setup.")),
                    ToolComponentStatus.Missing, options, false, {},
                )
                ToolListRow(
                    "proxy",
                    toolFindingIds("proxy").map { ToolFinding(it, options.version(it)) },
                    null, ToolComponentStatus.Ready, options, false, {},
                )
            }
        }
    }
}
