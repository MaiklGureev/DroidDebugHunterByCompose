@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlinx.coroutines.*

@Composable
fun ToolSourcesDialog(model: AppState, config: Settings, component: String, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sources =
        remember(config.setup.versions, component) { ToolLayout.sources(config, component) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("Downloads and file placement")) },
        text = {
            LazyColumn(
                Modifier.width(850.dp).heightIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text(
                        tr(
                            "Download components separately or prepare the complete Frida bundle. Links use the selected versions. Package pages include host-specific wheels; offline installation also needs dependencies."
                        ),
                        fontSize = 12.sp,
                    )
                }
                items(sources) { source ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectionContainer { Text(source.label, color = Accent) }
                        SelectionContainer { Text(source.url, fontSize = 11.sp) }
                        Text(tr(source.formatKey), color = Muted, fontSize = 12.sp)
                        SelectionContainer {
                            Text(File(config.tools, source.folder).absolutePath, fontSize = 11.sp)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (ToolLayout.isArchive(source))
                                Action(
                                    tr("Download file"),
                                    Icons.Outlined.Download,
                                    model.busy.isEmpty(),
                                ) {
                                    model.downloadToolSource(config, source)
                                }
                            Action(tr("Copy link"), Icons.Outlined.ContentCopy) {
                                copyText(model, source.url)
                            }
                            Action(tr("Open link"), Icons.AutoMirrored.Outlined.OpenInNew) {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            Desktop.getDesktop().browse(URI(source.url))
                                        }
                                    } catch (e: Exception) {
                                        model.error = e.message.orEmpty()
                                    }
                                }
                            }
                            Action(tr("Open destination folder"), Icons.Outlined.FolderOpen) {
                                val folder = File(config.tools, source.folder)
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            check(folder.isDirectory || folder.mkdirs()) {
                                                tr("Could not create directory: {0}", folder.path)
                                            }
                                        }
                                        openFile(model, folder)
                                    } catch (e: Exception) {
                                        model.error = e.message.orEmpty()
                                    }
                                }
                            }
                        }
                        Divider(color = Line)
                    }
                }
            }
        },
        buttons = {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (model.error.isNotBlank()) Text(model.error, color = Red, fontSize = 12.sp)
                model.setupProgress?.let { progress ->
                    Text(progress.phase + " · " + progress.detail, fontSize = 12.sp, maxLines = 2)
                    val fraction = progress.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(fraction, Modifier.fillMaxWidth())
                        Text(
                            "${(fraction * 100).toInt()}% · ${progress.bytes / 1024} KiB",
                            fontSize = 11.sp,
                        )
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Action(tr("Cancel")) { model.cancelOperation() }
                }
                model.lastDownloadedToolFile?.let {
                    SelectionContainer {
                        Text(tr("Downloaded: {0}", it.absolutePath), fontSize = 11.sp)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Action(tr("Copy all links"), Icons.Outlined.ContentCopy) {
                        copyText(
                            model,
                            sources.joinToString("\n\n") {
                                "${it.label}\n${it.url}\n${File(config.tools, it.folder)}\n${tr(it.formatKey)}"
                            },
                        )
                    }
                    Action(tr("Folder structure"), Icons.Outlined.Description) {
                        model.outputs +=
                            OutputWindow(
                                title = tr("Folder structure"),
                                text = ToolLayout.structure(config),
                            )
                    }
                    Action(tr("Open cache folder"), Icons.Outlined.FolderOpen) {
                        val cache =
                            File(
                                config.setup.cache.ifBlank {
                                    File(config.tools, "downloads").absolutePath
                                }
                            )
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    check(cache.isDirectory || cache.mkdirs()) {
                                        tr("Could not create directory: {0}", cache.path)
                                    }
                                }
                                openFile(model, cache)
                            } catch (e: Exception) {
                                model.error = e.message.orEmpty()
                            }
                        }
                    }
                    Action(tr("Close")) { close() }
                }
            }
        },
    )
}
