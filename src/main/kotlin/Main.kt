import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import hunter.app.AppState
import hunter.app.HunterApp
import hunter.app.Page
import hunter.app.checkApplication
import hunter.app.checkToolSetup
import hunter.core.AppFiles
import hunter.core.ApplicationLog
import hunter.core.HostPlatform
import hunter.core.json
import hunter.core.tr
import hunter.features.settings.Settings
import hunter.features.traffic.ui.ExchangeContent
import hunter.ui.HunterTheme
import hunter.ui.OutputContent
import hunter.ui.chooseFile
import hunter.ui.isDarkTheme
import hunter.ui.isPlatformShortcutPressed
import java.awt.Dimension
import java.io.File
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--tool-setup-check") {
        require(args.size == 2) { "Usage: --tool-setup-check <report.json>" }
        File(args[1]).writeText(json.toJson(checkToolSetup()))
        return
    }
    if (args.firstOrNull() == "--runtime-check") {
        require(args.size == 2) { "Usage: --runtime-check <report.json>" }
        File(args[1])
            .writeText(
                json.toJson(
                    mapOf(
                        "javaHome" to System.getProperty("java.home"),
                        "javaVersion" to System.getProperty("java.version"),
                        "platform" to HostPlatform.current.key,
                    ) + checkApplication()
                )
            )
        return
    }
    val applicationLog = ApplicationLog(AppFiles.applicationLog)
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        applicationLog.crash(thread, failure)
        if (previousHandler != null) previousHandler.uncaughtException(thread, failure)
        else failure.printStackTrace(System.err)
    }
    launchHunter(Settings.load(), applicationLog)
}

private fun launchHunter(initialSettings: Settings, applicationLog: ApplicationLog) = application {
    val model = remember { AppState(initialSettings, applicationLog) }
    val settings by model.settings.uiState.collectAsState()
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun close() {
        if (!closing) {
            closing = true
            scope.launch {
                try {
                    model.shutdown()
                } finally {
                    exitApplication()
                }
            }
        }
    }
    Window(
        onCloseRequest = ::close,
        title = "DroidDebugHunter · Compose",
        state = rememberWindowState(width = 1440.dp, height = 920.dp),
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) false
            else
                when {
                    event.key == Key.F1 -> {
                        model.page = Page.Help
                        true
                    }
                    event.isPlatformShortcutPressed && event.key == Key.K -> {
                        model.palette = !model.palette
                        true
                    }
                    event.isPlatformShortcutPressed && event.key == Key.O -> {
                        chooseFile(title = tr("Import HAR"))?.let(model.traffic::importHar)
                        true
                    }
                    else -> false
                }
        },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(1100, 720) }
        HunterTheme(settings.isDarkTheme()) { HunterApp(model, closing) }
    }
    model.outputs.toList().forEach { output ->
        key(output.id) {
            Window(
                onCloseRequest = { model.outputs.remove(output) },
                title = output.title,
                state = rememberWindowState(width = 980.dp, height = 700.dp),
                onPreviewKeyEvent = {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        model.outputs.remove(output)
                        true
                    } else false
                },
            ) {
                HunterTheme(settings.isDarkTheme()) { OutputContent(model, output) }
            }
        }
    }
    val traffic = model.traffic
    val detailIds by traffic.detailIds.collectAsState(traffic.uiState.value.detailIds)
    detailIds.forEach { id ->
        key(id) {
            val initial = remember(traffic, id) {
                traffic.uiState.value.exchanges.firstOrNull { it.id == id }
            }
            val current by remember(traffic, id) { traffic.exchange(id) }.collectAsState(initial)
            val exchange = current ?: initial
            if (exchange != null)
                Window(
                    onCloseRequest = { traffic.closeDetails(id) },
                    title = "${exchange.method} · ${exchange.host}",
                    state = rememberWindowState(width = 1100.dp, height = 800.dp),
                    onPreviewKeyEvent = {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                            traffic.closeDetails(id)
                            true
                        } else false
                    },
                ) {
                    HunterTheme(settings.isDarkTheme()) { ExchangeContent(model, exchange) }
                }
        }
    }
}
