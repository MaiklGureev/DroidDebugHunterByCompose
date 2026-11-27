@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package hunter.features.traffic.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
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
import hunter.core.tr
import hunter.features.android.ui.AppPicker
import hunter.features.traffic.Curl
import hunter.features.traffic.Exchange
import hunter.ui.Accent
import hunter.ui.Action
import hunter.ui.Amber
import hunter.ui.Choice
import hunter.ui.EmptyState
import hunter.ui.Field
import hunter.ui.Green
import hunter.ui.Line
import hunter.ui.MiniIcon
import hunter.ui.Muted
import hunter.ui.Pill
import hunter.ui.Red
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
    val snapshot = model.exchanges.toList()
    val visible by
        produceState(emptyList<Exchange>(), snapshot, query, pkg, identity, sort) {
            value =
                withContext(Dispatchers.Default) {
                    snapshot
                        .filter { it.matches(query, pkg, identity) }
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
    val selected = snapshot.firstOrNull { it.id == selectedId }
    val enabled = model.busy.isEmpty() && model.initialized
    fun openDetails(e: Exchange) {
        if (e.id !in model.detailIds) model.detailIds += e.id
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
        Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppPicker(model, Modifier.weight(1f), enabled && !model.capturing)
                Action(
                    when {
                        model.capturing -> tr("Stop")
                        model.selectedDevice?.state != "device" -> tr("Set up connection")
                        else -> tr("Start capture")
                    },
                    if (model.capturing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    enabled,
                    primary = true,
                    danger = model.capturing,
                    onClick = model::toggleCapture,
                )
            }
        }
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
            Choice(sort, TrafficSort.entries, { it.label }, { sort = it }, Modifier.width(145.dp))
            Action(tr("Import HAR"), Icons.Outlined.FileOpen, enabled) {
                chooseFile(title = tr("Import HAR"))?.let(model::importHar)
            }
            Action(tr("Export HAR"), Icons.Outlined.FileDownload, enabled && visible.isNotEmpty()) {
                chooseFile(true, "traffic.har", extension = "har")?.let {
                    model.saveHar(it, visible.toList())
                }
            }
            MiniIcon(Icons.Outlined.DeleteOutline, tr("Clear list"), enabled) {
                model.clearTraffic()
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
                            if (snapshot.isEmpty()) tr("Traffic will appear here")
                            else tr("No matches"),
                            if (snapshot.isEmpty())
                                tr("Start capture from Android or open a HAR file.")
                            else tr("Change the search or reset filters."),
                        ) {
                            if (snapshot.isEmpty())
                                Action(tr("Open HAR"), Icons.Outlined.FileOpen) {
                                    chooseFile(title = tr("Import HAR"))?.let(model::importHar)
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
            if (model.evictedRequests > 0)
                Text(tr("Evicted: {0}", model.evictedRequests), color = Amber, fontSize = 11.sp)
            if (model.capturing) Pill("● LIVE")
        }
    }
}

@Composable
private fun TableCell(text: String, width: Int) {
    Text(text, Modifier.width(width.dp), fontSize = 10.sp, color = Muted)
}
