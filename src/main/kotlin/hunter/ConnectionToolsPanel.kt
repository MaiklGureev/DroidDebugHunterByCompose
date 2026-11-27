@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter

import androidx.compose.foundation.layout.*
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConnectionToolsPanel(
    model: AppState,
    title: String,
    components: List<String>,
    expanded: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = model.settings
    val findings = model.toolFindings.associateBy { it.id }
    val relevant = components.toSet() +
        if ("capture" in components) model.toolSteps.keys.intersect(setOf("server", "gadget"))
        else emptySet()
    val unfinished = relevant.mapNotNull { model.toolSteps[it] }
        .filter { it.state != ToolStepState.Prepared }
    var recoveryLinks by remember(components) { mutableStateOf(false) }
    var draft by remember(config.toolConfiguration(), config.setup) { mutableStateOf(config) }
    fun apply() {
        model.updateSettings(model.settings.copy(
            adb = draft.adb.trim(),
            python = draft.python.trim(),
            tools = draft.tools.trim(),
            setup = draft.setup,
        ))
    }

    Panel(title, modifier = modifier.fillMaxWidth()) {
        for (id in relevant.flatMap(::toolFindingIds).distinct()) {
            val finding = findings[id] ?: ToolFinding(id, null)
            val (label, color) = when {
                id !in findings && findings.isEmpty() -> tr("Not checked") to Muted
                finding.failed -> tr("Check required") to Amber
                finding.actual == null -> tr("Not found") to Amber
                finding.warning -> tr("Different version") to Amber
                else -> tr("Ready") to Green
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(finding.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr("Detected: {0} · Target: {1}", finding.actual ?: "—", ToolPolicy.target(finding.versionId)),
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    if (expanded && finding.detail.isNotBlank()) {
                        Text(finding.detail, color = Muted, fontSize = 11.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Pill(label, color)
            }
        }
        for (step in unfinished) {
            val label = if (step.state == ToolStepState.Skipped) tr("Skipped") else tr("Failed")
            Text("${componentTitle(step.component)} · $label", color = Amber, fontWeight = FontWeight.SemiBold)
            Text(step.detail.lineSequence().lastOrNull { it.isNotBlank() }?.take(400).orEmpty(),
                color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Action(
                tr(if (expanded) "Hide tool settings" else "Configure tools"),
                Icons.Outlined.Build,
            ) { onExpand() }
            Action(tr("Check tools"), Icons.Outlined.Refresh, model.initialized && model.busy.isEmpty()) {
                model.checkTools(refreshAdb = components == listOf("adb"))
            }
            if (unfinished.isNotEmpty()) {
                val validVersions = ToolPolicy.ids.all { ToolPolicy.validVersion(config.setup.version(it)) }
                Action(tr("Retry unfinished components"), Icons.Outlined.Replay,
                    model.canPrepareTools && config.setup.mode != SetupMode.Manual && validVersions) {
                    model.setupTools(unfinished.map { it.component })
                }
                Action(tr("Download links / folders"), Icons.Outlined.Link, validVersions) {
                    recoveryLinks = true
                }
                Action(tr("Setup log"), Icons.Outlined.Description) {
                    val details = unfinished.joinToString("\n\n") { "${componentTitle(it.component)}\n${it.detail}" }
                    model.outputs += OutputWindow(title = tr("Setup log"), text = details)
                }
            }
        }
        if (expanded) {
            Divider(color = Line)
            ToolSetupPanel(model, draft, { draft = it }, ::apply, components, model.canPrepareTools)
        }
    }
    if (recoveryLinks && unfinished.isNotEmpty()) {
        ToolSourcesDialog(model, config, unfinished.map { it.component }) { recoveryLinks = false }
    }
}
