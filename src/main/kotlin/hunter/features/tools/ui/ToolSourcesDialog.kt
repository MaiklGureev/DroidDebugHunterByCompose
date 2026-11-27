@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter.features.tools.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import hunter.app.AppState
import hunter.app.OutputWindow
import hunter.core.tr
import hunter.features.settings.Settings
import hunter.features.tools.ToolLayout
import hunter.features.tools.ToolProvisioner
import hunter.ui.ToolAction
import hunter.ui.copyText
import hunter.ui.openFile
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlinx.coroutines.*

@Composable
fun ToolSourcesDialog(model: AppState, config: Settings, components: List<String>, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sources =
        remember(config.setup.versions, components) { ToolLayout.sources(config, components) }
    AlertDialog(
        onDismissRequest = close,
        modifier = Modifier.widthIn(max = 850.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(tr("Downloads and file placement")) },
        text = {
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 540.dp).padding(end = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Text(
                            tr(
                                "Links use the selected versions. Package pages include host-specific wheels; offline installation also needs dependencies."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(sources, key = { it.url }, contentType = { "source" }) { source ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SelectionContainer { Text(source.label, color = MaterialTheme.colorScheme.primary) }
                            SelectionContainer { Text(source.url, style = MaterialTheme.typography.labelSmall) }
                            Text(tr(source.formatKey), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            SelectionContainer {
                                Text(File(config.tools, source.folder).absolutePath, style = MaterialTheme.typography.labelSmall)
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (ToolLayout.isArchive(source))
                                    ToolAction(
                                        tr("Download file"),
                                        Icons.Outlined.Download,
                                        model.busy.isEmpty(),
                                    ) {
                                        model.downloadToolSource(config, source)
                                    }
                                ToolAction(tr("Copy link"), Icons.Outlined.ContentCopy) {
                                    copyText(model, source.url)
                                }
                                ToolAction(tr("Open link"), Icons.AutoMirrored.Outlined.OpenInNew) {
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
                                ToolAction(tr("Open destination folder"), Icons.Outlined.FolderOpen) {
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
                            HorizontalDivider()
                        }
                    }
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(listState),
                    Modifier.align(Alignment.CenterEnd).matchParentSize().wrapContentWidth(Alignment.End),
                )
            }
        },
        confirmButton = {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (model.error.isNotBlank()) Text(model.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                model.setupProgress?.let { progress ->
                    Text(progress.phase + " · " + progress.detail, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    val fraction = progress.fraction
                    if (fraction != null) {
                        LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
                        Text(
                            "${(fraction * 100).toInt()}% · ${progress.bytes / 1024} KiB",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    ToolAction(tr("Cancel")) { model.cancelOperation() }
                }
                model.lastDownloadedToolFile?.let {
                    SelectionContainer {
                        Text(tr("Downloaded: {0}", it.absolutePath), style = MaterialTheme.typography.labelSmall)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ToolAction(tr("Copy all links"), Icons.Outlined.ContentCopy) {
                        copyText(
                            model,
                            sources.joinToString("\n\n") {
                                "${it.label}\n${it.url}\n${File(config.tools, it.folder)}\n${tr(it.formatKey)}"
                            },
                        )
                    }
                    ToolAction(tr("Folder structure"), Icons.Outlined.Description) {
                        model.outputs +=
                            OutputWindow(
                                title = tr("Folder structure"),
                                text = ToolLayout.structure(config),
                            )
                    }
                    ToolAction(tr("Plan / manual instructions"), Icons.Outlined.Description) {
                        model.outputs += OutputWindow(
                            title = tr("Plan / manual instructions"),
                            text = ToolProvisioner.plan(config, components),
                        )
                    }
                    ToolAction(tr("Open cache folder"), Icons.Outlined.FolderOpen) {
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
                    ToolAction(tr("Close")) { close() }
                }
            }
        },
    )
}
