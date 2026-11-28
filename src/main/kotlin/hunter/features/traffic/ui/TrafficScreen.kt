@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package hunter.features.traffic.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonDefaults as MaterialButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hunter.app.AppState
import hunter.app.Page
import hunter.core.tr
import hunter.features.android.ui.AppPicker
import hunter.features.traffic.CaptureMode
import hunter.features.traffic.Curl
import hunter.features.traffic.Exchange
import hunter.features.traffic.TrafficAppFilter
import hunter.ui.Accent
import hunter.ui.Action
import hunter.ui.Amber
import hunter.ui.Choice
import hunter.ui.EmptyState
import hunter.ui.Field
import hunter.ui.Green
import hunter.ui.HunterTheme
import hunter.ui.Line
import hunter.ui.MiniIcon
import hunter.ui.Muted
import hunter.ui.Pill
import hunter.ui.Red
import hunter.ui.ToolAction
import hunter.ui.chooseFile
import hunter.ui.copyText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class TrafficSort(private val labelKey: String) {
    Newest("Time ↓"),
    Oldest("Time ↑"),
    Status("Status"),
    Duration("Duration");

    val label
        get() = tr(labelKey)
}

@Composable
fun TrafficScreen(model: AppState) {
    var query by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var identity by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(TrafficSort.Newest) }
    val traffic = model.traffic
    val connectionState by model.connection.uiState.collectAsState()
    val state by traffic.uiState.collectAsState()
    val capture = state.capture
    val mode = capture?.mode ?: connectionState.mode
    val targetApp = if (mode.capturesSingleApp) capture?.app ?: connectionState.selectedApp else null
    val targetFilter = targetApp?.let {
        TrafficAppFilter(it.packageName, capture?.serial ?: connectionState.selectedDevice?.serial, it.uid)
    }
    var onlySelectedApp by remember(targetFilter) { mutableStateOf(true) }
    val appFilter = targetFilter.takeIf { onlySelectedApp }
    val snapshot = state.exchanges
    val visible = key(appFilter) {
        val result by produceState(emptyList<Exchange>(), snapshot, query, pkg, identity, sort) {
            value =
                withContext(Dispatchers.Default) {
                    snapshot
                        .filter { (appFilter == null || appFilter.matches(it)) && it.matches(query, pkg, identity) }
                        .let { entries ->
                            when (sort) {
                                TrafficSort.Oldest -> entries.sortedBy { it.instant }
                                TrafficSort.Status -> entries.sortedByDescending { it.status ?: 0 }
                                TrafficSort.Duration ->
                                    entries.sortedByDescending { it.duration ?: 0.0 }
                                else -> entries.sortedByDescending { it.instant }
                            }
                        }
                }
        }
        result
    }
    val selected = visible.firstOrNull { it.id == selectedId }
    val enabled = model.busy.isEmpty() && model.initialized
    fun openDetails(e: Exchange) {
        traffic.openDetails(e.id)
    }
    fun copyCurl() {
        if (selected != null)
            try {
                copyText(model, Curl.create(selected))
            } catch (e: Exception) {
                model.error = e.message ?: tr("cURL unavailable")
            }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TrafficCaptureBar(
            mode = mode,
            running = state.running,
            connected = connectionState.selectedDevice?.state == "device",
            appLabel = targetApp?.label,
            enabled = enabled,
            toggle = model::toggleCapture,
            configureSingleApp = {
                model.connection.setMode(CaptureMode.Vpn)
                model.page = Page.Connection
            },
            appPicker = { AppPicker(model, it, enabled && connectionState.selectedDevice?.state == "device") },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Field(
                query,
                { query = it },
                tr("Search URL, headers, method, status…"),
                Modifier.weight(1f),
            )
            Field(pkg, { pkg = it }, tr("App / package"), Modifier.width(175.dp))
            Field(identity, { identity = it }, "UID / App ID", Modifier.width(115.dp))
            MiniIcon(Icons.Outlined.FilterAltOff, tr("Reset filters")) {
                query = ""
                pkg = ""
                identity = ""
                onlySelectedApp = false
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                tr("{0} / {1} requests", visible.size, snapshot.size),
                fontSize = 12.sp,
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            if (targetFilter != null) {
                FilterChip(
                    selected = onlySelectedApp,
                    onClick = { onlySelectedApp = !onlySelectedApp },
                    label = { MaterialText(tr("Selected app only")) },
                )
            }
            Choice(sort, TrafficSort.entries, { it.label }, { sort = it }, Modifier.width(145.dp))
            Action(tr("Import HAR"), Icons.Outlined.FileOpen, enabled) {
                chooseFile(title = tr("Import HAR"))?.let(traffic::importHar)
            }
            Action(tr("Export HAR"), Icons.Outlined.FileDownload, enabled && visible.isNotEmpty()) {
                chooseFile(true, "traffic.har", extension = "har")?.let {
                    traffic.saveHar(it, visible.toList())
                }
            }
            MiniIcon(Icons.Outlined.DeleteOutline, tr("Clear list"), enabled) {
                traffic.clear()
                selectedId = null
            }
        }
        Surface(
            Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, Line),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colors.onSurface.copy(alpha = .025f))
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    TableCell(tr("TIME"), 105)
                    TableCell(tr("METHOD"), 65)
                    TableCell(tr("STATUS"), 65)
                    Text(tr("HOST / PATH"), Modifier.weight(1f), fontSize = 10.sp, color = Muted)
                    TableCell(tr("APP"), 150)
                    TableCell(tr("TIME, MS"), 85)
                }
                Divider(color = Line)
                if (visible.isEmpty())
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            Icons.Outlined.SwapVert,
                            when {
                                snapshot.isEmpty() -> tr("Traffic will appear here")
                                appFilter != null -> tr("No matches for the selected app")
                                else -> tr("No matches")
                            },
                            when {
                                snapshot.isEmpty() -> tr("Start capture from Android or open a HAR file.")
                                appFilter != null -> tr("Change the search or turn off Selected app only.")
                                else -> tr("Change the search or reset filters.")
                            },
                        ) {
                            if (snapshot.isEmpty())
                                Action(tr("Open HAR"), Icons.Outlined.FileOpen) {
                                    chooseFile(title = tr("Import HAR"))?.let(traffic::importHar)
                                }
                        }
                    }
                else {
                    val state = rememberLazyListState()
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = state,
                            modifier =
                                Modifier.fillMaxSize()
                                    .onPreviewKeyEvent {
                                        if (
                                            it.type == KeyEventType.KeyDown &&
                                                it.key == Key.Enter &&
                                                selected != null
                                        ) {
                                            openDetails(selected)
                                            true
                                        } else false
                                    }
                                    .focusable(),
                        ) {
                            items(visible, key = { it.id }) { e ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            if (selectedId == e.id) Accent.copy(alpha = .13f)
                                            else Color.Transparent
                                        )
                                        .combinedClickable(
                                            onClick = { selectedId = e.id },
                                            onDoubleClick = {
                                                selectedId = e.id
                                                openDetails(e)
                                            },
                                        )
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        e.time,
                                        Modifier.width(105.dp),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Muted,
                                    )
                                    Text(
                                        e.method,
                                        Modifier.width(65.dp),
                                        fontSize = 11.sp,
                                        color = if (e.method == "GET") Green else Accent,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        e.statusLabel,
                                        Modifier.width(65.dp),
                                        fontSize = 12.sp,
                                        color =
                                            if (e.error.isNotEmpty() || (e.status ?: 0) >= 400) Red
                                            else if ((e.status ?: 0) >= 300) Amber else Green,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            e.host,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 12.sp,
                                        )
                                        Text(
                                            e.path,
                                            modifier = Modifier.padding(top = 3.dp),
                                            color = Muted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Column(Modifier.width(150.dp)) {
                                        Text(
                                            e.appName.ifBlank {
                                                e.packageName.ifBlank { tr("Unknown") }
                                            },
                                            fontSize = 11.sp,
                                            color = Muted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (e.appId.isNotBlank())
                                            Text(
                                                "App ID ${e.appId}",
                                                fontSize = 10.sp,
                                                color = Muted,
                                            )
                                    }
                                    Text(
                                        e.duration?.let { "%.1f".format(it) } ?: "—",
                                        Modifier.width(85.dp),
                                        fontSize = 11.sp,
                                        color = Muted,
                                    )
                                }
                                Divider(color = Line.copy(alpha = .06f))
                            }
                        }
                        VerticalScrollbar(
                            rememberScrollbarAdapter(state),
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Action(tr("Details"), Icons.AutoMirrored.Outlined.OpenInNew, selected != null) {
                selected?.let(::openDetails)
            }
            Action(tr("Copy cURL"), Icons.Outlined.Code, selected != null && !selected.raw) {
                copyCurl()
            }
            Action(tr("Copy URL"), Icons.Outlined.Link, selected != null) {
                selected?.let { copyText(model, it.url) }
            }
            Spacer(Modifier.weight(1f))
            if (state.evictedRequests > 0)
                Text(tr("Evicted: {0}", state.evictedRequests), color = Amber, fontSize = 11.sp)
            if (state.running) Pill("● LIVE")
        }
    }
}

@Composable
private fun TrafficCaptureBar(
    mode: CaptureMode,
    running: Boolean,
    connected: Boolean,
    appLabel: String?,
    enabled: Boolean,
    toggle: () -> Unit,
    configureSingleApp: () -> Unit,
    appPicker: @Composable (Modifier) -> Unit,
) {
    val scheme = Material3Theme.colorScheme
    MaterialSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = Material3Theme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mode.capturesSingleApp && !running) {
                appPicker(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MaterialText(
                        appLabel ?: tr("System proxy · All apps"),
                        style = Material3Theme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MaterialText(
                        if (mode.capturesSingleApp) mode.title
                        else tr("Requests sent through the system proxy have no app or UID information."),
                        style = Material3Theme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (running) {
                MaterialButton(
                    toggle,
                    enabled = enabled,
                    colors = MaterialButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                ) {
                    MaterialIcon(Icons.Outlined.Stop, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    MaterialText(tr("Stop"))
                }
            } else {
                if (!mode.capturesSingleApp) {
                    ToolAction(tr("Set up WireGuard for one app"), Icons.Outlined.VpnKey, enabled, onClick = configureSingleApp)
                }
                ToolAction(
                    tr(if (connected) "Start capture" else "Set up connection"),
                    Icons.Outlined.PlayArrow,
                    enabled && (!connected || !mode.capturesSingleApp || appLabel != null),
                    primary = true,
                    onClick = toggle,
                )
            }
        }
    }
}

@Preview
@Composable
private fun ProxyCaptureBarPreview() = HunterTheme(dark = false) {
    TrafficCaptureBar(CaptureMode.Proxy, running = false, connected = true, appLabel = null,
        enabled = true, toggle = {}, configureSingleApp = {}, appPicker = {})
}

@Preview
@Composable
private fun ActiveAppCaptureBarPreview() = HunterTheme(dark = true) {
    TrafficCaptureBar(CaptureMode.Vpn, running = true, connected = true, appLabel = "Example app",
        enabled = true, toggle = {}, configureSingleApp = {}, appPicker = {})
}

@Composable
private fun TableCell(text: String, width: Int) {
    Text(text, Modifier.width(width.dp), fontSize = 10.sp, color = Muted)
}
