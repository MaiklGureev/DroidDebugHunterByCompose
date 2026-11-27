package hunter.features.help.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hunter.app.AppState
import hunter.app.Page
import hunter.core.AppFiles
import hunter.core.tr
import hunter.features.help.Markdown
import hunter.features.help.MarkdownLocation
import hunter.features.help.MarkdownTarget
import hunter.features.help.isMarkdownFile
import hunter.features.help.resolveMarkdownLink
import hunter.features.traffic.CaptureMode
import hunter.ui.Action
import hunter.ui.Choice
import hunter.ui.MiniIcon
import hunter.ui.Muted
import hunter.ui.Panel
import hunter.ui.chooseFile
import hunter.ui.copyText
import hunter.ui.openFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HelpScreen(model: AppState) {
    val topics =
        listOf(
            tr("Quick start"),
            "Windows / macOS / Linux",
            tr("Android: complete setup"),
            tr("ADB tools"),
            "Logcat",
            tr("Request and response content"),
            tr("Tools and downloads"),
            tr("Third-party components"),
        )
    val files =
        listOf(
            "",
            "setup/PLATFORMS.md",
            "setup/ANDROID_SETUP_GUIDE.md",
            "android/ADB_TOOLS.md",
            "android/LOGCAT.md",
            "guides/BODY_VIEWER.md",
            "setup/DOWNLOADS.md",
            "legal/THIRD_PARTY_NOTICES.md",
        )
    var topic by remember { mutableStateOf(0) }
    val language = model.settings.language
    val helpRoot = remember { File(AppFiles.runtime, "help") }
    var location by remember { mutableStateOf<MarkdownLocation?>(null) }
    var history by remember { mutableStateOf<List<MarkdownLocation?>>(emptyList()) }
    val currentLocation =
        location
            ?: if (topic == 0) null
            else MarkdownLocation(File(helpRoot, language.helpFile(files[topic])), helpRoot)
    val text by
        produceState<Pair<File, Result<String>>?>(null, currentLocation?.file) {
            value = null
            currentLocation?.let { document ->
                value =
                    document.file to
                        withContext(Dispatchers.IO) { runCatching { Markdown.read(document.file) } }
            }
        }
    fun openDocument(document: MarkdownLocation) {
        history = history + listOf(currentLocation)
        location = document
    }
    fun followLink(destination: String) {
        val current = currentLocation ?: return
        model.scope.launch {
            try {
                when (
                    val target =
                        withContext(Dispatchers.IO) { resolveMarkdownLink(current, destination) }
                ) {
                    is MarkdownTarget.Document -> openDocument(target.location)
                    is MarkdownTarget.Web ->
                        withContext(Dispatchers.IO) {
                            java.awt.Desktop.getDesktop().browse(target.uri)
                        }
                    null ->
                        model.error =
                            tr("This link is unavailable in the Markdown viewer: {0}", destination)
                }
            } catch (e: Exception) {
                model.error = e.message ?: tr("Guide unavailable")
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniIcon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Back"), history.isNotEmpty()) {
                location = history.last()
                history = history.dropLast(1)
            }
            Choice(
                topic,
                topics.indices.toList(),
                { topics[it] },
                {
                    topic = it
                    location = null
                    history = emptyList()
                },
                Modifier.width(320.dp),
            )
            Action(tr("Open Markdown"), Icons.Outlined.FileOpen) {
                chooseFile(title = tr("Open Markdown"))?.let { file ->
                    if (isMarkdownFile(file))
                        openDocument(MarkdownLocation(file, file.absoluteFile.parentFile))
                    else model.error = tr("Select a Markdown file (.md or .markdown)")
                }
            }
            Action(tr("Open help folder"), Icons.Outlined.FolderOpen) { openFile(model, helpRoot) }
        }
        if (currentLocation == null)
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Panel(
                    tr("Start by connecting"),
                    tr("USB or Android 11+ wireless debugging"),
                    Modifier.fillMaxWidth(),
                ) {
                    Text(
                        tr(
                            "Open Connection to set up USB, Wi-Fi or an emulator. Then choose the target app on Traffic."
                        )
                    )
                    Action(tr("Set up connection")) { model.page = Page.Connection }
                }
                Panel(tr("Choose a capture mode"), modifier = Modifier.fillMaxWidth()) {
                    CaptureMode.entries.forEach {
                        Text(it.title, fontWeight = FontWeight.SemiBold)
                        Text(it.hint, color = Muted)
                    }
                    Text(
                        tr(
                            "Prepare the certificate, VPN or Frida on Connection, then start capture on Traffic."
                        ),
                        color = Muted,
                    )
                }
                Panel(tr("Work without a phone"), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        tr(
                            "HAR import and request analysis work without ADB, Python or Frida. Double-click for headers, bodies and cURL in a separate window. HAR export preserves visible HTTP requests, including partial-body markers."
                        )
                    )
                    Action(tr("Open HAR"), Icons.Outlined.FileOpen) {
                        chooseFile(title = tr("Import HAR"))?.let(model::importHar)
                    }
                }
                Panel(tr("Keyboard and limitations"), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        tr(
                            "Ctrl+O — open HAR\nCtrl+K — commands\nF1 — help\nEnter / double-click — row details\nCtrl+C in Logcat — copy selected rows\n\nThe system proxy cannot identify package/UID. VPN attributes packages from its allowlist. Pinning can block HTTPS; Native TLS does not guarantee HTTP/3 support. Only inspect devices/apps you are authorized to test."
                        ),
                        color = Muted,
                    )
                }
            }
        else {
            val content = text?.takeIf { it.first == currentLocation.file }?.second
            key(currentLocation.file.absolutePath) {
                when {
                    content == null ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    content.isFailure ->
                        Text(
                            content.exceptionOrNull()?.message ?: tr("Guide unavailable"),
                            color = MaterialTheme.colors.error,
                        )
                    else ->
                        MarkdownViewer(
                            source = content.getOrThrow(),
                            modifier = Modifier.fillMaxSize(),
                            anchor = currentLocation.anchor,
                            onLink = ::followLink,
                            onCopy = { copyText(model, it) },
                        )
                }
            }
        }
    }
}
