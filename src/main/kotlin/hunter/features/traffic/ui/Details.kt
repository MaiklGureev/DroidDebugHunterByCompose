package hunter.features.traffic.ui

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
import hunter.app.AppState
import hunter.core.tr
import hunter.features.traffic.Body
import hunter.features.traffic.Curl
import hunter.features.traffic.CurlShell
import hunter.features.traffic.Exchange
import hunter.ui.Accent
import hunter.ui.Action
import hunter.ui.Amber
import hunter.ui.Choice
import hunter.ui.Green
import hunter.ui.MiniIcon
import hunter.ui.MonoText
import hunter.ui.Muted
import hunter.ui.Pill
import hunter.ui.Red
import hunter.ui.chooseFile
import hunter.ui.copyText
import java.awt.Toolkit
import java.awt.datatransfer.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                    exchange.duration?.let { tr("%.1f ms").format(it) }
                        ?: tr("Waiting for response"),
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
                tr(
                    "{0} · {1} · {2}\n{3} · UID {4} · App ID {5} · device {6}",
                    exchange.source,
                    exchange.version,
                    exchange.time,
                    exchange.appName.ifBlank {
                        exchange.packageName.ifBlank { tr("App source unknown") }
                    },
                    exchange.uid.ifBlank { "?" },
                    exchange.appId.ifBlank { "?" },
                    exchange.serial.ifBlank { "HAR" },
                ),
                fontSize = 11.sp,
                color = Muted,
            )
            if (exchange.error.isNotBlank())
                Text(exchange.error, color = MaterialTheme.colors.error)
            val tabs =
                listOf(tr("Request"), tr("Response"), "cURL") +
                    if (exchange.diagnostic.isNotBlank()) listOf(tr("Diagnostics")) else emptyList()
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
                                    tr("HEADERS · {0}", headers.size),
                                    fontSize = 11.sp,
                                    color = Muted,
                                    modifier = Modifier.weight(1f),
                                )
                                MiniIcon(Icons.Outlined.ContentCopy, tr("Copy headers")) {
                                    copyText(
                                        model,
                                        headers.joinToString("\n") { "${it.name}: ${it.value}" },
                                    )
                                }
                            }
                            MonoText(
                                headers
                                    .joinToString("\n") { "${it.name}: ${it.value}" }
                                    .ifBlank { tr("No headers") },
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
                            Text(
                                tr("The command has not been executed"),
                                fontSize = 12.sp,
                                color = Muted,
                            )
                        }
                        MonoText(
                            curl.getOrElse { it.message ?: tr("cURL unavailable") },
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
                                tr("Image exceeds 20 MB; save the original body")
                            }
                            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input
                                ->
                                val readers = ImageIO.getImageReaders(input)
                                require(readers.hasNext()) {
                                    tr("Unsupported image format. Select Text/HEX.")
                                }
                                val reader = readers.next()
                                try {
                                    reader.input = input
                                    require(
                                        reader.getWidth(0).toLong() * reader.getHeight(0) <=
                                            16_000_000
                                    ) {
                                        tr("Image exceeds 16 MP; save the file")
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
                        BodyView(error = e.message ?: tr("Body preview error"))
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
                listOf("Auto", "Text", "JSON", "HTML text", "Image", "HEX", "Base64"),
                { tr(it) },
                { mode = it },
                Modifier.width(125.dp),
            )
            Text(
                tr("{0} B", view.bytes),
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            MiniIcon(Icons.Outlined.ContentCopy, tr("Copy view"), view.error.isEmpty()) {
                if (view.image != null)
                    model.task(tr("Copying image")) {
                        val image =
                            withContext(Dispatchers.IO) {
                                ImageIO.read(ByteArrayInputStream(body.bytes()))
                            } ?: error(tr("The clipboard does not support this image"))
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
                        model.status = tr("Image copied")
                    }
                else copyText(model, view.text)
            }
            MiniIcon(Icons.Outlined.Save, tr("Save original bytes"), model.busy.isEmpty()) {
                chooseFile(true, if (body.complete) "body.bin" else "body.partial.bin")?.let {
                    model.saveBody(it, body)
                }
            }
            MiniIcon(
                Icons.Outlined.Description,
                tr("Save view text"),
                model.busy.isEmpty() && view.image == null,
            ) {
                chooseFile(true, "body-view.txt", extension = "txt")?.let {
                    model.saveText(it, view.text)
                }
            }
        }
        Text(
            type.ifBlank { tr("Content-Type not specified") },
            color = Muted,
            fontSize = 10.sp,
            maxLines = 2,
        )
        if (!body.complete)
            Text(
                tr("The body is unavailable or partially captured. Exact replay is unavailable."),
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
                        tr("HTTP body: image"),
                        Modifier.fillMaxSize().padding(12.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            else ->
                MonoText(
                    view.text.ifEmpty {
                        if (body.complete) tr("Body is empty") else tr("Body unavailable")
                    },
                    Modifier.fillMaxSize(),
                )
        }
    }
}
