package hunter.features.help.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hunter.app.AppState
import hunter.app.Page
import hunter.core.tr
import hunter.features.help.HelpTopic
import hunter.features.help.HelpUiState
import hunter.features.traffic.CaptureMode
import hunter.ui.Action
import hunter.ui.Choice
import hunter.ui.HunterTheme
import hunter.ui.MiniIcon
import hunter.ui.Muted
import hunter.ui.Panel
import hunter.ui.chooseFile
import hunter.ui.copyText
import hunter.ui.openFile

@Composable
fun HelpScreen(model: AppState) {
    val help = model.help
    val state by help.uiState.collectAsState()
    HelpScreenContent(
        state = state,
        onTopic = help::selectTopic,
        onBack = help::back,
        onOpenMarkdown = { chooseFile(title = tr("Open Markdown"))?.let(help::openMarkdown) },
        onOpenFolder = { openFile(model, help.helpRoot) },
        onConnection = { model.page = Page.Connection },
        onOpenHar = { chooseFile(title = tr("Import HAR"))?.let(model.traffic::importHar) },
        onLink = help::followLink,
        onCopy = { copyText(model, it) },
    )
}

@Composable
private fun HelpScreenContent(
    state: HelpUiState,
    onTopic: (HelpTopic) -> Unit,
    onBack: () -> Unit,
    onOpenMarkdown: () -> Unit,
    onOpenFolder: () -> Unit,
    onConnection: () -> Unit,
    onOpenHar: () -> Unit,
    onLink: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val currentLocation = state.location
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniIcon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Back"), state.canGoBack, onClick = onBack)
            Choice(
                state.topic,
                HelpTopic.entries,
                { tr(it.titleKey) },
                onTopic,
                Modifier.width(320.dp),
            )
            Action(tr("Open Markdown"), Icons.Outlined.FileOpen, onClick = onOpenMarkdown)
            Action(tr("Open help folder"), Icons.Outlined.FolderOpen, onClick = onOpenFolder)
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
                    Action(tr("Set up connection"), onClick = onConnection)
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
                    Action(tr("Open HAR"), Icons.Outlined.FileOpen, onClick = onOpenHar)
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
            val content = state.content
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
                            document = content.getOrThrow(),
                            modifier = Modifier.fillMaxSize(),
                            anchor = currentLocation.anchor,
                            onLink = onLink,
                            onCopy = onCopy,
                        )
                }
            }
        }
    }
}

@Preview
@Composable
private fun HelpLightPreview() = HelpPreview(dark = false)

@Preview
@Composable
private fun HelpDarkPreview() = HelpPreview(dark = true)

@Composable
private fun HelpPreview(dark: Boolean) {
    HunterTheme(dark) {
        Surface(Modifier.size(width = 920.dp, height = 580.dp)) {
            HelpScreenContent(HelpUiState(), {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
