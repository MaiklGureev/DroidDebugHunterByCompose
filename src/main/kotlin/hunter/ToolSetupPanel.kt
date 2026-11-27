@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.JFileChooser

@Composable
fun ToolSetupPanel(
    model: AppState,
    draft: Settings,
    change: (Settings) -> Unit,
    apply: () -> Unit,
    components: List<String>,
    selected: List<String>,
    enabled: Boolean,
    valid: Boolean,
) {
    val options = draft.setup
    var showVersions by remember { mutableStateOf(false) }
    fun update(value: ToolSetupOptions) = change(draft.copy(setup = value))
    fun chooseDirectory(current: String, onSelected: (String) -> Unit) {
        val dialog = JFileChooser(current).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = tr("Select folder")
        }
        if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
            onSelected(dialog.selectedFile.absolutePath)
    }

    Column(
        Modifier.onPreviewKeyEvent { event ->
            if (enabled && valid && event.type == KeyEventType.KeyDown &&
                event.isPlatformShortcutPressed && event.key == Key.S) {
                apply()
                true
            } else false
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(tr("Local tool settings"), style = MaterialTheme.typography.titleSmall)
        Text(tr("Platform: {0}", HostPlatform.current.label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(tr("The local tool directory is shared across connection methods."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        ToolPathField(tr("Tool bundle directory"), draft.tools, { change(draft.copy(tools = it)) },
            tr("Folder…"), enabled) {
            chooseDirectory(draft.tools) { change(draft.copy(tools = it)) }
        }
        ToolPathField(tr("ADB (empty = bundle / PATH)"), draft.adb, { change(draft.copy(adb = it)) },
            tr("Select…"), enabled) {
            chooseFile(title = HostPlatform.current.executable("adb"))?.let {
                change(draft.copy(adb = it.absolutePath))
            }
        }
        ToolPathField(tr("Python executable (empty = auto)"), draft.python, { change(draft.copy(python = it)) },
            tr("Select…"), enabled) {
            chooseFile(title = HostPlatform.current.executable(HostPlatform.current.pythonName))?.let {
                change(draft.copy(python = it.absolutePath))
            }
        }

        Text(tr("Setup mode"), style = MaterialTheme.typography.titleSmall)
        ToolChoices(SetupMode.entries, options.mode, { tr(it.key) }, enabled) {
            update(options.copy(mode = it, chosen = true))
        }
        if (options.mode != SetupMode.Manual) {
            Text(tr("Python installation method"), style = MaterialTheme.typography.titleSmall)
            ToolChoices(SetupMethod.entries, options.method, { tr(it.key) }, enabled) {
                update(options.copy(method = it, methodChosen = true))
            }
        }
        ToolCheck(tr("Offline: use local files and cache only"), options.offline, enabled) {
            update(options.copy(offline = it))
        }
        ToolPathField(tr("Download cache (empty = tools/downloads)"), options.cache,
            { update(options.copy(cache = it)) }, tr("Folder…"), enabled) {
            chooseDirectory(options.cache) { update(options.copy(cache = it)) }
        }
        ToolPathField(tr("Local Platform Tools ZIP (optional)"), options.adbArchive,
            { update(options.copy(adbArchive = it)) }, tr("Select…"), enabled) {
            chooseFile(title = tr("Platform Tools ZIP"))?.let {
                update(options.copy(adbArchive = it.absolutePath))
            }
        }
        if (options.mode != SetupMode.Manual && options.method == SetupMethod.Managed) {
            ToolPathField(tr("Existing uv executable (optional)"), options.uv,
                { update(options.copy(uv = it)) }, tr("Select…"), enabled) {
                chooseFile(title = "uv")?.let { update(options.copy(uv = it.absolutePath)) }
            }
        }

        ToolCheck(tr("Choose different versions"), showVersions) { showVersions = it }
        if (showVersions) {
            val ids = components.flatMap(::toolFindingIds)
                .map { if (it == "server" || it == "gadget") "frida" else it }.distinct()
            for (group in ids.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (id in group) {
                        Column(Modifier.weight(1f)) {
                            OutlinedTextField(
                                options.version(id),
                                { update(options.copy(versions = options.versions + (id to it.trim()))) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(ToolPolicy.names.getValue(id)) },
                                enabled = enabled,
                                singleLine = true,
                                isError = !ToolPolicy.validVersion(options.version(id)),
                            )
                            Text(tr("Target: {0}", ToolPolicy.target(id)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            if (!ToolPolicy.validVersion(options.version(id))) {
                                Text(tr("Enter a numeric version"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (group.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            ToolAction(tr("Restore target versions"), enabled = enabled) {
                update(options.copy(versions = options.versions + ids.associateWith(ToolPolicy::target)))
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolAction(tr("Save"), Icons.Outlined.Save, enabled && valid, onClick = apply)
            ToolAction(tr("Use app data folder"), enabled = enabled) {
                change(draft.copy(tools = File(AppFiles.data, "tools/${HostPlatform.current.key}").absolutePath))
            }
            ToolAction(tr("Check / create folders"), Icons.Outlined.CreateNewFolder, enabled && valid) {
                apply()
                model.initializeToolFolders()
            }
            ToolAction(tr("Folder structure"), Icons.Outlined.AccountTree, valid) {
                model.outputs += OutputWindow(title = tr("Folder structure"), text = ToolLayout.structure(draft))
            }
            ToolAction(tr("Plan / manual instructions"), Icons.Outlined.Description, valid && selected.isNotEmpty()) {
                model.outputs += OutputWindow(title = tr("Setup plan"), text = ToolProvisioner.plan(draft, selected))
            }
        }
        if (model.toolDirectoryWarnings.isNotEmpty()) {
            Text(model.toolDirectoryWarnings.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ToolPathField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    action: String,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value, onValueChange,
            modifier = Modifier.weight(1f),
            label = { Text(label) },
            enabled = enabled,
            singleLine = true,
        )
        ToolAction(action, enabled = enabled, onClick = onChoose)
    }
}

@Composable
private fun <T> ToolChoices(
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (entry in entries) {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                Modifier.heightIn(min = 40.dp)
                    .background(
                        if (hovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                        MaterialTheme.shapes.small,
                    )
                    .hoverable(interaction, enabled)
                    .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                    .selectable(entry == selected, enabled, Role.RadioButton) { onSelect(entry) }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(entry == selected, onClick = null, enabled = enabled)
                Text(label(entry), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ToolCheck(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.heightIn(min = 40.dp)
            .background(
                if (hovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .hoverable(interaction, enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .toggleable(checked, enabled, Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
