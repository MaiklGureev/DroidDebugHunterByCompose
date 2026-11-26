@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ToolSetupPanel(model: AppState, draft: Settings, change: (Settings) -> Unit, enabled: Boolean) {
    var component by remember { mutableStateOf("") }
    var editVersions by remember { mutableStateOf(false) }
    var sourcesVisible by remember { mutableStateOf(false) }
    val options = draft.setup
    val hasComponent =
        options.chosen && (options.mode == SetupMode.Automatic || component.isNotBlank())
    val needsPython = options.mode == SetupMode.Automatic || component != "adb"
    val selectionReady =
        hasComponent && (options.mode == SetupMode.Manual || !needsPython || options.methodChosen)
    fun update(value: ToolSetupOptions) = change(draft.copy(setup = value))
    val uiScope = rememberCoroutineScope()
    fun selectFolder(current: String, selected: (String) -> Unit) {
        val dialog =
            JFileChooser(current).apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = tr("Select folder")
            }
        if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
            selected(dialog.selectedFile.absolutePath)
    }

    Text(tr("Tool setup"), fontWeight = FontWeight.SemiBold)
    Text(
        tr(
            "Choose a setup approach, a component and a download method. Nothing is downloaded until you press a download or prepare button."
        ),
        color = Muted,
    )
    FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in SetupMode.entries) SetupOptionCard(
            tr(mode.key),
            tr(
                when (mode) {
                    SetupMode.Manual ->
                        "Manual: select installed tools, check versions and use the instructions. No automatic installation."
                    SetupMode.Guided ->
                        "Guided: choose one component and installation method, review the plan, then prepare it."
                    SetupMode.Automatic ->
                        "Automatic: prepare all components locally and try alternative Python methods if needed."
                }
            ),
            options.chosen && options.mode == mode,
            enabled,
        ) {
            update(options.copy(mode = mode, chosen = true))
        }
    }

    Text(tr("Target versions"), fontWeight = FontWeight.SemiBold)
    Text(
        ToolPolicy.ids.joinToString(" · ") { "${ToolPolicy.names[it]} ${ToolPolicy.target(it)}" },
        fontSize = 12.sp,
    )
    Text(
        tr(
            "Other versions are allowed with warnings. Frida client, server and Gadget must use the same version. Package requirements and native wheel availability still apply."
        ),
        color = Muted,
        fontSize = 12.sp,
    )
    Check(tr("Choose different versions"), editVersions, enabled) { editVersions = it }
    if (editVersions) {
        for (ids in ToolPolicy.ids.chunked(2)) Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            for (id in ids) Column(Modifier.weight(1f)) {
                Field(
                    options.version(id),
                    { update(options.copy(versions = options.versions + (id to it.trim()))) },
                    ToolPolicy.names.getValue(id),
                    Modifier.fillMaxWidth(),
                    enabled,
                )
                Text(tr("Target: {0}", ToolPolicy.target(id)), color = Muted, fontSize = 11.sp)
                if (!ToolPolicy.validVersion(options.version(id)))
                    Text(tr("Enter a numeric version"), color = Red)
                else if (options.version(id) != ToolPolicy.target(id))
                    Text(
                        ToolPolicy.notice(id, options.version(id)),
                        color = Amber,
                        fontSize = 11.sp,
                    )
            }
            if (ids.size == 1) Spacer(Modifier.weight(1f))
        }
        Action(tr("Restore target versions"), enabled = enabled) {
            update(options.copy(versions = ToolPolicy.ids.associateWith(ToolPolicy::target)))
        }
    }

    Text(tr("Components to download"), fontWeight = FontWeight.SemiBold)
    if (options.chosen && options.mode == SetupMode.Automatic) Text(tr("All tools"), color = Accent)
    else
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (id in ToolProvisioner.components) Surface(
                shape = MaterialTheme.shapes.small,
                border =
                    BorderStroke(
                        if (component == id) 2.dp else 1.dp,
                        if (component == id) Accent else Line,
                    ),
                color =
                    if (component == id) Accent.copy(alpha = .10f) else MaterialTheme.colors.surface,
            ) {
                Row(
                    Modifier.selectable(component == id, enabled, Role.RadioButton) {
                            component = id
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        component == id,
                        onClick = null,
                        enabled = enabled,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(componentTitle(id), fontSize = 12.sp)
                }
            }
        }
    if (options.chosen && options.mode != SetupMode.Manual) {
        if (hasComponent && needsPython) {
            Text(tr("Python installation method"), fontWeight = FontWeight.SemiBold)
            Text(
                tr("Bootstrap helper target: uv {0}", ToolPolicy.target("uv")),
                color = Muted,
                fontSize = 11.sp,
            )
            FlowRow(
                Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (method in SetupMethod.entries) SetupOptionCard(
                    tr(method.key),
                    tr(
                        when (method) {
                            SetupMethod.Managed ->
                                "Download portable uv and create a private Python here. No installed Python is required."
                            SetupMethod.Venv ->
                                "Use the selected Python to create a local virtual environment. Requires venv and pip."
                            SetupMethod.Target ->
                                "Use the selected Python and install packages into the component folders with pip --target."
                        }
                    ),
                    options.methodChosen && options.method == method,
                    enabled,
                ) {
                    update(options.copy(method = method, methodChosen = true))
                }
            }
        }
        Check(tr("Offline: use local files and cache only"), options.offline, enabled) {
            update(options.copy(offline = it))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(
                options.cache,
                { update(options.copy(cache = it)) },
                tr("Download cache (empty = tools/downloads)"),
                Modifier.weight(1f),
                enabled,
            )
            Action(tr("Folder…"), enabled = enabled) {
                selectFolder(options.cache) { update(options.copy(cache = it)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(
                options.adbArchive,
                { update(options.copy(adbArchive = it)) },
                tr("Local Platform Tools ZIP (optional)"),
                Modifier.weight(1f),
                enabled,
            )
            Action(tr("Select…"), enabled = enabled) {
                chooseFile(title = tr("Platform Tools ZIP"))?.let {
                    update(options.copy(adbArchive = it.absolutePath))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(
                options.uv,
                { update(options.copy(uv = it)) },
                tr("Existing uv executable (optional)"),
                Modifier.weight(1f),
                enabled,
            )
            Action(tr("Select…"), enabled = enabled) {
                chooseFile(title = "uv")?.let { update(options.copy(uv = it.absolutePath)) }
            }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Action(
            tr("Download links / folders"),
            Icons.Outlined.Link,
            enabled &&
                hasComponent &&
                ToolPolicy.ids.all { ToolPolicy.validVersion(options.version(it)) },
        ) {
            sourcesVisible = true
        }
        Action(tr("Check / create folders"), Icons.Outlined.CreateNewFolder, enabled) {
            model.updateSettings(draft)
            model.initializeToolFolders()
        }
        Action(tr("Folder structure"), Icons.Outlined.AccountTree, enabled) {
            model.outputs +=
                OutputWindow(title = tr("Folder structure"), text = ToolLayout.structure(draft))
        }
    }
    if (sourcesVisible)
        ToolSourcesDialog(
            model,
            draft,
            if (options.mode == SetupMode.Automatic) "all" else component,
        ) {
            sourcesVisible = false
        }
    if (model.toolDirectoryWarnings.isNotEmpty())
        Text(model.toolDirectoryWarnings.joinToString("\n"), color = Amber, fontSize = 11.sp)
    Text(
        tr(
            "All methods use user-writable folders. No administrator prompt or system package installation is triggered. If policy blocks a method, try another method, local archives or existing tools."
        ),
        color = Muted,
        fontSize = 12.sp,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Action(
            tr("Plan / manual instructions"),
            Icons.Outlined.Description,
            enabled && selectionReady,
        ) {
            model.outputs +=
                OutputWindow(
                    title = tr("Setup plan"),
                    text = ToolProvisioner.plan(draft, component),
                )
        }
        if (options.chosen && options.mode != SetupMode.Manual)
            Action(
                tr("Prepare selected tools"),
                Icons.Outlined.Download,
                enabled &&
                    selectionReady &&
                    ToolPolicy.ids.all { ToolPolicy.validVersion(options.version(it)) },
                primary = true,
            ) {
                model.updateSettings(draft)
                model.setupTools(component)
            }
        Action(tr("Use app data folder"), enabled = enabled) {
            change(
                draft.copy(
                    tools = File(AppFiles.data, "tools/${HostPlatform.current.key}").absolutePath
                )
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, url) in
            listOf(
                "Python" to "https://www.python.org/downloads/",
                "ADB" to "https://developer.android.com/tools/releases/platform-tools",
                "Frida" to "https://github.com/frida/frida/releases",
                "uv" to "https://docs.astral.sh/uv/getting-started/installation/",
            )) {
            Action(label, Icons.AutoMirrored.Outlined.OpenInNew) {
                uiScope.launch {
                    try {
                        withContext(Dispatchers.IO) { Desktop.getDesktop().browse(URI(url)) }
                    } catch (e: Exception) {
                        model.error = e.message.orEmpty()
                    }
                }
            }
        }
    }
    model.setupProgress?.let { progress ->
        Text(progress.phase, color = Accent, fontWeight = FontWeight.SemiBold)
        if (progress.fraction != null) {
            LinearProgressIndicator(
                progress = progress.fraction!!,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${(progress.fraction!! * 100).toInt()}% · ${progress.bytes / 1024} / ${(progress.total ?: 0) / 1024} KiB",
                color = Muted,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (progress.bytes > 0) Text("${progress.bytes / 1024} KiB", color = Muted)
        }
        Text(progress.detail, fontSize = 12.sp)
        Action(tr("Cancel"), Icons.Outlined.Close) { model.cancelOperation() }
    }
    if (model.setupLiveLog.isNotBlank()) {
        Text(tr("Setup log"), fontWeight = FontWeight.SemiBold)
        val scroll = rememberScrollState()
        LaunchedEffect(model.setupLiveLog) { scroll.scrollTo(scroll.maxValue) }
        Surface(
            color = MaterialTheme.colors.background,
            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
        ) {
            Text(
                model.setupLiveLog,
                Modifier.padding(10.dp).verticalScroll(scroll),
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
    for (finding in model.toolFindings) Text(
        finding.summary,
        color = if (finding.warning) Amber else Green,
        fontSize = 12.sp,
    )
}

private fun componentTitle(id: String): String =
    when (id) {
        "all" -> tr("All tools")
        "frida" -> tr("Frida bundle: client + server + Gadget")
        "python" -> "Python"
        "adb" -> "ADB"
        "capture" -> "Frida Python"
        "server" -> "frida-server"
        "gadget" -> "Gadget"
        "proxy" -> "mitmproxy Python"
        else -> "WireGuard APK"
    }

@Composable
private fun SetupOptionCard(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    click: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Accent else Line),
        color = if (selected) Accent.copy(alpha = .10f) else MaterialTheme.colors.surface,
        modifier = Modifier.width(330.dp),
    ) {
        Row(
            Modifier.selectable(selected, enabled, Role.RadioButton, onClick = click)
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(
                selected,
                onClick = null,
                enabled = enabled,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Muted, fontSize = 12.sp)
            }
        }
    }
}
