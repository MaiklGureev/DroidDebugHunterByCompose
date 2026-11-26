import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import hunter.*
import java.awt.Dimension
import java.io.File
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
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
    launchHunter()
}

private fun launchHunter() = application {
    val model = remember { AppState() }
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
                        chooseFile(title = tr("Import HAR"))?.let(model::importHar)
                        true
                    }
                    else -> false
                }
        },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(1100, 720) }
        HunterTheme(model.settings.dark) { HunterApp(model, closing) }
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
                HunterTheme(model.settings.dark) { OutputContent(model, output) }
            }
        }
    }
    model.detailIds.toList().forEach { id ->
        key(id) {
            val initial = remember { model.exchanges.firstOrNull { it.id == id } }
            val exchange = model.exchanges.firstOrNull { it.id == id } ?: initial
            if (exchange != null)
                Window(
                    onCloseRequest = { model.detailIds.remove(id) },
                    title = "${exchange.method} · ${exchange.host}",
                    state = rememberWindowState(width = 1100.dp, height = 800.dp),
                    onPreviewKeyEvent = {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                            model.detailIds.remove(id)
                            true
                        } else false
                    },
                ) {
                    HunterTheme(model.settings.dark) { ExchangeContent(model, exchange) }
                }
        }
    }
}
