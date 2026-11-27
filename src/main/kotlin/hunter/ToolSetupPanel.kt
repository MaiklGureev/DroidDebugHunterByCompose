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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    enabled: Boolean,
    valid: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Tool settings"), style = MaterialTheme.typography.titleMedium)
        ToolPathField(tr("Tool bundle directory"), draft.tools, { change(draft.copy(tools = it)) },
            tr("Folder…"), enabled) {
            chooseToolDirectory(draft.tools) { change(draft.copy(tools = it)) }
        }
        ToolPathField(tr("Download cache (empty = tools/downloads)"), draft.setup.cache,
            { change(draft.copy(setup = draft.setup.copy(cache = it))) }, tr("Folder…"), enabled) {
            chooseToolDirectory(draft.setup.cache) { change(draft.copy(setup = draft.setup.copy(cache = it))) }
        }
        ToolCheck(tr("Offline: use local files and cache only"), draft.setup.offline, enabled) {
            change(draft.copy(setup = draft.setup.copy(offline = it)))
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
        }
        if (model.toolDirectoryWarnings.isNotEmpty()) {
            Text(
                model.toolDirectoryWarnings.joinToString("\n"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun ToolComponentOptions(component: String, draft: Settings, change: (Settings) -> Unit, enabled: Boolean) {
    val options = draft.setup
    fun update(value: ToolSetupOptions) = change(draft.copy(setup = value))
    val ids = toolFindingIds(component).map { if (it in listOf("server", "gadget")) "frida" else it }
    if (component == "adb") {
        AdbDownloadVersion(options.version("adb"), enabled) {
            update(options.copy(versions = options.versions + ("adb" to it)))
        }
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (id in ids) {
            OutlinedTextField(
                options.version(id),
                { update(options.copy(versions = options.versions + (id to it.trim()))) },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 210.dp),
                label = { Text(tr("{0} version", ToolPolicy.names.getValue(id))) },
                supportingText = if (options.version(id) != ToolPolicy.target(id)) {
                    {
                        Text(
                            if (ToolPolicy.validVersion(options.version(id))) tr("Target: {0}", ToolPolicy.target(id))
                            else tr("Enter a numeric version"),
                        )
                    }
                } else null,
                isError = !ToolPolicy.validVersion(options.version(id)),
                enabled = enabled,
                singleLine = true,
            )
        }
    }
    when (component) {
        "adb" -> {
            ToolPathField(tr("ADB (empty = bundle / PATH)"), draft.adb, { change(draft.copy(adb = it)) },
                tr("Select…"), enabled) {
                chooseFile(title = HostPlatform.current.executable("adb"))?.let {
                    change(draft.copy(adb = it.absolutePath))
                }
            }
            ToolPathField(tr("Local Platform Tools ZIP (optional)"), options.adbArchive,
                { update(options.copy(adbArchive = it)) }, tr("Select…"), enabled) {
                chooseFile(title = tr("Platform Tools ZIP"))?.let {
                    update(options.copy(adbArchive = it.absolutePath))
                }
            }
        }
        "python" -> {
            ToolPathField(tr("Python executable (empty = auto)"), draft.python, { change(draft.copy(python = it)) },
                tr("Select…"), enabled) {
                chooseFile(title = HostPlatform.current.executable(HostPlatform.current.pythonName))?.let {
                    change(draft.copy(python = it.absolutePath))
                }
            }
            Text(tr("Python installation method"), style = MaterialTheme.typography.labelLarge)
            ToolChoices(SetupMethod.entries, options.method, { tr(it.key) }, enabled) {
                update(options.copy(method = it))
            }
            if (options.method == SetupMethod.Managed) {
                ToolPathField(tr("Existing uv executable (optional)"), options.uv,
                    { update(options.copy(uv = it)) }, tr("Select…"), enabled) {
                    chooseFile(title = "uv")?.let { update(options.copy(uv = it.absolutePath)) }
                }
            }
        }
    }
}

@Composable
private fun AdbDownloadVersion(version: String, enabled: Boolean, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    TextButton(onClick = { editing = true }, enabled = enabled) {
        Text(tr("Change download version"))
    }
    if (editing) {
        var input by remember(version) { mutableStateOf(version) }
        val focus = remember { FocusRequester() }
        val valid = ToolPolicy.validVersion(input)
        val save = {
            onChange(input)
            editing = false
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(tr("Change download version")) },
            text = {
                OutlinedTextField(
                    input,
                    { input = it.trim() },
                    Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            if (valid) save()
                            true
                        } else false
                    },
                    label = { Text(tr("{0} version", ToolPolicy.names.getValue("adb"))) },
                    supportingText = {
                        Text(if (valid) tr("Target: {0}", ToolPolicy.target("adb")) else tr("Enter a numeric version"))
                    },
                    isError = !valid,
                    singleLine = true,
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            },
            confirmButton = { TextButton(onClick = save, enabled = valid) { Text(tr("Apply")) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(tr("Cancel")) } },
        )
    }
}

private fun chooseToolDirectory(current: String, onSelected: (String) -> Unit) {
    val dialog = JFileChooser(current).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = tr("Select folder")
    }
    if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
        onSelected(dialog.selectedFile.absolutePath)
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
