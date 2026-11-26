package hunter

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Toolkit
import java.awt.datatransfer.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OutputContent(model: AppState, output: OutputWindow) {
    Surface(color = MaterialTheme.colors.surface) {
        Column(
            Modifier.fillMaxSize()
                .padding(20.dp)
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        model.outputs.remove(output)
                        true
                    } else false
                }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    output.title,
                    Modifier.weight(1f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Action("Копировать всё", Icons.Outlined.ContentCopy) {
                    copyText(model, output.text)
                }
                Action("Сохранить TXT", Icons.Outlined.Save, model.busy.isEmpty()) {
                    chooseFile(true, "adb-result.txt", extension = "txt")?.let {
                        model.saveText(it, output.text)
                    }
                }
            }
            MonoText(output.text, Modifier.weight(1f).fillMaxWidth())
            Text("UTF-8 · Esc — закрыть окно", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun ExchangeContent(model: AppState, exchange: Exchange) {
    var tab by remember { mutableStateOf(0) }
    var shell by remember { mutableStateOf(CurlShell.native) }
    val curl = remember(exchange, shell) { runCatching { Curl.create(exchange, shell) } }
    Surface(color = MaterialTheme.colors.surface) {
        Column(
            Modifier.fillMaxSize()
                .padding(20.dp)
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        model.detailIds.remove(exchange.id)
                        true
                    } else false
                }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Pill(exchange.method, Accent)
                Pill(
                    exchange.statusLabel,
                    if (exchange.error.isNotBlank() || (exchange.status ?: 0) >= 400) Red else Green,
                )
                Text(
                    exchange.duration?.let { "%.1f мс".format(it) } ?: "Ожидание ответа",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                Action("URL", Icons.Outlined.Link) { copyText(model, exchange.url) }
                Action("cURL", Icons.Outlined.Code, curl.isSuccess) {
                    curl.getOrNull()?.let { copyText(model, it) }
                }
            }
            SelectionContainer {
                Text(exchange.url, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 3)
            }
            Text(
                "${exchange.source} · ${exchange.version} · ${exchange.time}\n${exchange.appName.ifBlank {exchange.packageName.ifBlank {"Источник приложения неизвестен"}}} · UID ${exchange.uid.ifBlank {"?"}} · App ID ${exchange.appId.ifBlank {"?"}} · устройство ${exchange.serial.ifBlank {"HAR"}}",
                fontSize = 11.sp,
                color = Muted,
            )
            if (exchange.error.isNotBlank())
                Text(exchange.error, color = MaterialTheme.colors.error)
            val tabs =
                listOf("Запрос", "Ответ", "cURL") +
                    if (exchange.diagnostic.isNotBlank()) listOf("Диагностика") else emptyList()
            TabRow(
                tab.coerceAtMost(tabs.lastIndex),
                backgroundColor = MaterialTheme.colors.surface,
            ) {
                tabs.forEachIndexed { i, title ->
                    Tab(tab == i, { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0,
                1 -> {
                    val headers =
                        if (tab == 0) exchange.requestHeaders else exchange.responseHeaders
                    val body = if (tab == 0) exchange.request else exchange.response
                    val type =
                        headers.firstOrNull { it.name.equals("Content-Type", true) }?.value ?: ""
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            Modifier.width(300.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "ЗАГОЛОВКИ · ${headers.size}",
                                    fontSize = 11.sp,
                                    color = Muted,
                                    modifier = Modifier.weight(1f),
                                )
                                MiniIcon(Icons.Outlined.ContentCopy, "Копировать заголовки") {
                                    copyText(
                                        model,
                                        headers.joinToString("\n") { "${it.name}: ${it.value}" },
                                    )
                                }
                            }
                            MonoText(
                                headers
                                    .joinToString("\n") { "${it.name}: ${it.value}" }
                                    .ifBlank { "Заголовков нет" },
                                Modifier.fillMaxSize(),
                            )
                        }
                        BodyViewer(model, body, type, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                2 ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Choice(
                                shell,
                                CurlShell.entries,
                                { it.title },
                                { shell = it },
                                Modifier.width(210.dp),
                            )
                            Text("Команда не выполнялась", fontSize = 12.sp, color = Muted)
                        }
                        MonoText(
                            curl.getOrElse { it.message ?: "cURL недоступен" },
                            Modifier.fillMaxSize(),
                        )
                    }
                else ->
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MonoText(exchange.diagnostic, Modifier.weight(1f).fillMaxHeight())
                        exchange.diagnosticBody
                            ?.takeIf { it.data.isNotEmpty() }
                            ?.let { BodyViewer(model, it, "", Modifier.weight(1f).fillMaxHeight()) }
                    }
            }
        }
    }
}

private data class BodyView(
    val text: String = "",
    val image: ImageBitmap? = null,
    val bytes: Int = 0,
    val error: String = "",
)

@Composable
fun BodyViewer(model: AppState, body: Body, type: String, modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf("Auto") }
    val view by
        produceState(BodyView(), body, mode, type) {
            value =
                withContext(Dispatchers.Default) {
                    try {
                        val bytes = body.bytes()
                        if (mode == "Image" || mode == "Auto" && type.startsWith("image/")) {
                            require(bytes.size <= 20 * 1024 * 1024) {
                                "Изображение больше 20 МБ; сохраните исходное тело"
                            }
                            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input
                                ->
                                val readers = ImageIO.getImageReaders(input)
                                require(readers.hasNext()) {
                                    "Формат изображения не поддерживается. Выберите Text/HEX."
                                }
                                val reader = readers.next()
                                try {
                                    reader.input = input
                                    require(
                                        reader.getWidth(0).toLong() * reader.getHeight(0) <=
                                            16_000_000
                                    ) {
                                        "Изображение больше 16 Мп; сохраните файл"
                                    }
                                } finally {
                                    reader.dispose()
                                }
                            }
                            BodyView(
                                image =
                                    org.jetbrains.skia.Image.makeFromEncoded(bytes)
                                        .toComposeImageBitmap(),
                                bytes = bytes.size,
                            )
                        } else BodyView(text = body.view(mode), bytes = bytes.size)
                    } catch (e: Exception) {
                        BodyView(error = e.message ?: "Ошибка просмотра тела")
                    }
                }
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Choice(
                mode,
                listOf("Auto", "Text", "JSON", "HTML-текст", "Image", "HEX", "Base64"),
                { it },
                { mode = it },
                Modifier.width(125.dp),
            )
            Text("${view.bytes} Б", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            MiniIcon(Icons.Outlined.ContentCopy, "Копировать представление", view.error.isEmpty()) {
                if (view.image != null)
                    model.task("Копирование изображения") {
                        val image =
                            withContext(Dispatchers.IO) {
                                ImageIO.read(ByteArrayInputStream(body.bytes()))
                            } ?: error("Изображение не поддерживается буфером обмена")
                        Toolkit.getDefaultToolkit()
                            .systemClipboard
                            .setContents(
                                object : Transferable {
                                    override fun getTransferDataFlavors() =
                                        arrayOf(DataFlavor.imageFlavor)

                                    override fun isDataFlavorSupported(flavor: DataFlavor) =
                                        flavor == DataFlavor.imageFlavor

                                    override fun getTransferData(flavor: DataFlavor): Any {
                                        if (!isDataFlavorSupported(flavor))
                                            throw UnsupportedFlavorException(flavor)
                                        return image
                                    }
                                },
                                null,
                            )
                        model.status = "Изображение скопировано"
                    }
                else copyText(model, view.text)
            }
            MiniIcon(Icons.Outlined.Save, "Сохранить исходные байты", model.busy.isEmpty()) {
                chooseFile(true, if (body.complete) "body.bin" else "body.partial.bin")?.let {
                    model.saveBody(it, body)
                }
            }
            MiniIcon(
                Icons.Outlined.Description,
                "Сохранить текст просмотра",
                model.busy.isEmpty() && view.image == null,
            ) {
                chooseFile(true, "body-view.txt", extension = "txt")?.let {
                    model.saveText(it, view.text)
                }
            }
        }
        Text(
            type.ifBlank { "Content-Type не указан" },
            color = Muted,
            fontSize = 10.sp,
            maxLines = 2,
        )
        if (!body.complete)
            Text(
                "Тело отсутствует или захвачено частично. Точное повторение недоступно.",
                color = Amber,
                fontSize = 11.sp,
            )
        when {
            view.error.isNotEmpty() -> MonoText(view.error, Modifier.fillMaxSize())
            view.image != null ->
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        view.image!!,
                        "Тело HTTP: изображение",
                        Modifier.fillMaxSize().padding(12.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            else ->
                MonoText(
                    view.text.ifEmpty { if (body.complete) "Тело пустое" else "Тело недоступно" },
                    Modifier.fillMaxSize(),
                )
        }
    }
}
