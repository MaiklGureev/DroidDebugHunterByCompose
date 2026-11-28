@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter.features.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hunter.core.AppFiles
import hunter.core.HostPlatform
import hunter.core.tr
import hunter.features.settings.Settings
import hunter.features.tools.SetupMethod
import hunter.features.tools.ToolLayout
import hunter.features.tools.ToolPolicy
import hunter.features.tools.ToolSetupOptions
import hunter.ui.Check
import hunter.ui.ToolAction
import hunter.ui.ToolCheck
import hunter.ui.chooseFile
import java.io.File
import javax.swing.JFileChooser

@Composable
fun ToolSetupPanel(
    draft: Settings,
    change: (Settings) -> Unit,
    apply: () -> Unit,
    enabled: Boolean,
    valid: Boolean,
    directoryWarnings: List<String>,
    onCheckFolders: () -> Unit,
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
            ToolAction(tr("Check / create folders"), Icons.Outlined.CreateNewFolder,
                enabled && valid, onClick = onCheckFolders)
        }
        if (directoryWarnings.isNotEmpty()) {
            Text(
                directoryWarnings.joinToString("\n"),
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
fun ToolDownloadVersions(
    versionIds: List<String>,
    options: ToolSetupOptions,
    enabled: Boolean,
    prepareLatest: (() -> Unit)?,
    onChange: (Map<String, String>) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val title = tr(if (versionIds.size == 1) "Change download version" else "Change download versions")
    TextButton(onClick = { editing = true }, enabled = enabled) {
        Text(title)
    }
    if (editing) {
        val editingAdb = versionIds.singleOrNull() == "adb"
        val adbLatestLabel = if (ToolLayout.hasGoogleAdbArchive()) "Latest from Google" else "Version from ZIP"
        var useLatest by remember(options.versions) { mutableStateOf(editingAdb && options.adbLatest) }
        var versions by remember(versionIds, options.versions) {
            mutableStateOf(versionIds.associateWith {
                if (it == "adb" && options.adbLatest) ToolPolicy.target(it) else options.version(it)
            })
        }
        val focus = remember { FocusRequester() }
        val valid = useLatest || versions.values.all(ToolPolicy::validVersion)
        val save = {
            onChange(if (useLatest) versions + ("adb" to ToolPolicy.ADB_LATEST) else versions)
            editing = false
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                Column(
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            if (valid) save()
                            true
                        } else false
                    },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (editingAdb) {
                        ToolChoices(
                            listOf(true, false), useLatest,
                            { tr(if (it) adbLatestLabel else "Specific version") },
                            enabled,
                        ) { useLatest = it }
                    }
                    if (useLatest) {
                        Text(
                            tr(if (options.adbArchive.isNotBlank()) "The selected local ZIP takes priority."
                                else if (options.offline) "Offline mode uses the cached Platform Tools ZIP."
                                else "Downloads the current Platform Tools ZIP for this computer."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (prepareLatest != null) {
                            ToolAction(tr("Download and prepare"), enabled = enabled) {
                                editing = false
                                prepareLatest()
                            }
                        }
                    }
                    for ((index, id) in versionIds.withIndex()) if (!useLatest) {
                        val input = versions.getValue(id)
                        val validVersion = ToolPolicy.validVersion(input)
                        OutlinedTextField(
                            value = input,
                            onValueChange = { versions = versions + (id to it.trim()) },
                            modifier = if (index == 0) Modifier.fillMaxWidth().focusRequester(focus)
                                else Modifier.fillMaxWidth(),
                            label = { Text(tr("{0} version", ToolPolicy.names.getValue(id))) },
                            supportingText = {
                                Text(
                                    if (validVersion) tr("Target: {0}", ToolPolicy.target(id))
                                    else tr("Enter a numeric version"),
                                )
                            },
                            isError = !validVersion,
                            singleLine = true,
                        )
                    }
                    LaunchedEffect(useLatest) { if (!useLatest) focus.requestFocus() }
                }
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
