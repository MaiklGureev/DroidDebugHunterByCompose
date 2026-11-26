@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package hunter

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun LogcatScreen(model: AppState) {
    var filter by remember { mutableStateOf(LogFilter()) }
    var advanced by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var anchor by remember { mutableStateOf<String?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf<List<LogEntry>?>(null) }
    val snapshot = model.logs.toList()
    val filtered by
        produceState(Pair(emptyList<LogEntry>(), ""), snapshot, filter) {
            delay(70)
            value =
                withContext(Dispatchers.Default) {
                    try {
                        snapshot.filter(filter.predicate()) to ""
                    } catch (e: Exception) {
                        emptyList<LogEntry>() to (e.message ?: "Некорректный фильтр")
                    }
                }
        }
    val visible = filtered.first
    val list = rememberLazyListState()
    LaunchedEffect(visible.lastOrNull()?.id, autoScroll) {
        if (autoScroll && visible.isNotEmpty()) list.scrollToItem(visible.lastIndex)
    }
    val enabled = model.busy.isEmpty()
    fun copySelected() {
        copyText(model, visible.filter { it.id in selected }.joinToString("\n") { it.raw })
    }
    fun open(entry: LogEntry) {
        model.outputs +=
            OutputWindow(
                title = "${entry.level} · ${entry.tag}",
                text =
                    "Устройство: ${entry.serial} · буфер ${entry.buffer}\nPID ${entry.pid} · TID ${entry.tid} · UID ${entry.uid}\n\n${entry.raw}",
            )
    }
    Column(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent {
                ctrl = it.isCtrlPressed
                shift = it.isShiftPressed
                if (
                    it.type == KeyEventType.KeyDown &&
                        it.isCtrlPressed &&
                        it.key == Key.C &&
                        selected.isNotEmpty()
                ) {
                    copySelected()
                    true
                } else if (
                    it.type == KeyEventType.KeyDown && it.key == Key.Enter && selected.isNotEmpty()
                ) {
                    visible.firstOrNull { it.id in selected }?.let(::open)
                    true
                } else false
            }
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Check("Приложение (UID)", model.logOptions.appOnly, enabled && !model.logging) {
                        model.logOptions = model.logOptions.copy(appOnly = it)
                    }
                    AppPicker(model, Modifier.weight(1f), enabled && !model.logging)
                    Action(
                        if (model.logging) "Остановить" else "Начать запись",
                        if (model.logging) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                        enabled && (model.logging || model.selectedDevice?.state == "device"),
                        primary = true,
                        danger = model.logging,
                        onClick = model::toggleLogcat,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Буферы", fontSize = 11.sp, color = Muted)
                    Choice(
                        model.logOptions.buffer,
                        listOf(
                            "main + system + crash",
                            "all",
                            "main",
                            "system",
                            "crash",
                            "events",
                            "radio",
                        ),
                        { it },
                        { model.logOptions = model.logOptions.copy(buffer = it) },
                        Modifier.width(210.dp),
                        enabled && !model.logging,
                    )
                    Text("История", fontSize = 11.sp, color = Muted)
                    Choice(
                        model.logOptions.history,
                        listOf(100, 500, 2000, 5000),
                        { "$it строк" },
                        { model.logOptions = model.logOptions.copy(history = it) },
                        Modifier.width(120.dp),
                        enabled && !model.logging,
                    )
                    Spacer(Modifier.weight(1f))
                    Check("Автопрокрутка", autoScroll) { autoScroll = it }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Field(
                filter.query,
                { filter = filter.copy(query = it) },
                "Поиск в журнале",
                Modifier.weight(1f),
            )
            Check("Regex", filter.regex) { filter = filter.copy(regex = it) }
            Choice(
                filter.level,
                listOf("V", "D", "I", "W", "E", "F"),
                { "$it+" },
                { filter = filter.copy(level = it) },
                Modifier.width(65.dp),
            )
            Action("Ошибки+", Icons.Outlined.ErrorOutline) { filter = filter.copy(level = "E") }
            MiniIcon(Icons.Outlined.Tune, "Дополнительные фильтры") { advanced = !advanced }
            MiniIcon(Icons.Outlined.FilterAltOff, "Сбросить фильтры") { filter = LogFilter() }
        }
        if (advanced)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Field(
                        filter.tag,
                        { filter = filter.copy(tag = it) },
                        "Tag: через запятую",
                        Modifier.weight(1f),
                    )
                    Field(
                        filter.excludeTag,
                        { filter = filter.copy(excludeTag = it) },
                        "Исключить tag",
                        Modifier.weight(1f),
                    )
                    Check("Точный tag", filter.exact) { filter = filter.copy(exact = it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        filter.pid,
                        { filter = filter.copy(pid = it) },
                        "PID",
                        Modifier.weight(1f),
                    )
                    Field(
                        filter.tid,
                        { filter = filter.copy(tid = it) },
                        "TID",
                        Modifier.weight(1f),
                    )
                    Field(
                        filter.uid,
                        { filter = filter.copy(uid = it) },
                        "UID",
                        Modifier.weight(1f),
                    )
                }
            }
        if (filtered.second.isNotEmpty())
            Text(
                "Фильтр: ${filtered.second}",
                color = MaterialTheme.colors.error,
                fontSize = 11.sp,
                maxLines = 2,
            )
        Surface(
            Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, Line),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text("ВРЕМЯ", Modifier.width(155.dp), fontSize = 10.sp, color = Muted)
                    Text("LVL", Modifier.width(35.dp), fontSize = 10.sp, color = Muted)
                    Text("PID / TID", Modifier.width(95.dp), fontSize = 10.sp, color = Muted)
                    Text("TAG", Modifier.width(150.dp), fontSize = 10.sp, color = Muted)
                    Text("СООБЩЕНИЕ", fontSize = 10.sp, color = Muted)
                }
                Divider(color = Line)
                if (visible.isEmpty())
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            Icons.Outlined.Terminal,
                            if (snapshot.isEmpty()) "Журнал пока пуст" else "Нет совпадений",
                            if (snapshot.isEmpty())
                                "Запустите запись системы или выбранного приложения."
                            else "Измените фильтры; исходные строки остаются в памяти.",
                        )
                    }
                else
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                            items(visible, key = { it.id }) { entry ->
                                ContextMenuArea(
                                    items = {
                                        listOf(
                                            ContextMenuItem("Копировать строку") {
                                                copyText(model, entry.raw)
                                            },
                                            ContextMenuItem("Полная строка") { open(entry) },
                                            ContextMenuItem("Только этот tag") {
                                                filter = filter.copy(tag = entry.tag, exact = true)
                                            },
                                            ContextMenuItem("Исключить tag") {
                                                filter =
                                                    filter.copy(
                                                        excludeTag =
                                                            (filter.excludeTag.split(',') +
                                                                    entry.tag)
                                                                .filter(String::isNotBlank)
                                                                .joinToString(",")
                                                    )
                                            },
                                        )
                                    }
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .background(
                                                if (entry.id in selected) Accent.copy(alpha = .16f)
                                                else Color.Transparent
                                            )
                                            .combinedClickable(
                                                onClick = {
                                                    selected =
                                                        if (shift && anchor != null) {
                                                            val from =
                                                                visible.indexOfFirst {
                                                                    it.id == anchor
                                                                }
                                                            val to =
                                                                visible.indexOfFirst {
                                                                    it.id == entry.id
                                                                }
                                                            if (from >= 0)
                                                                visible
                                                                    .subList(
                                                                        minOf(from, to),
                                                                        maxOf(from, to) + 1,
                                                                    )
                                                                    .map { it.id }
                                                                    .toSet()
                                                            else setOf(entry.id)
                                                        } else if (ctrl) {
                                                            if (entry.id in selected)
                                                                selected - entry.id
                                                            else selected + entry.id
                                                        } else setOf(entry.id)
                                                    if (!shift) anchor = entry.id
                                                },
                                                onDoubleClick = { open(entry) },
                                            )
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Text(
                                            entry.time.takeLast(18),
                                            Modifier.width(155.dp),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Muted,
                                            maxLines = 1,
                                        )
                                        Text(
                                            entry.level,
                                            Modifier.width(35.dp),
                                            fontSize = 11.sp,
                                            color =
                                                when (entry.level) {
                                                    "E",
                                                    "F",
                                                    "A" -> Red
                                                    "W" -> Amber
                                                    "I" -> Green
                                                    else -> Muted
                                                },
                                        )
                                        Text(
                                            "${entry.pid} / ${entry.tid}",
                                            Modifier.width(95.dp),
                                            fontSize = 10.sp,
                                            color = Muted,
                                            maxLines = 1,
                                        )
                                        Text(
                                            entry.tag,
                                            Modifier.width(150.dp).padding(end = 8.dp),
                                            fontSize = 11.sp,
                                            color = Accent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            entry.message.take(2000),
                                            Modifier.weight(1f),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Divider(color = Line.copy(alpha = .035f))
                            }
                        }
                        VerticalScrollbar(
                            rememberScrollbarAdapter(list),
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Action("Копировать", Icons.Outlined.ContentCopy, selected.isNotEmpty()) {
                copySelected()
            }
            Action("Экспорт", Icons.Outlined.FileDownload, snapshot.isNotEmpty()) {
                export = snapshot.toList()
            }
            MiniIcon(Icons.Outlined.DeleteOutline, "Очистить окно") {
                model.clearLogs()
                selected = emptySet()
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${visible.size} / ${snapshot.size} · получено ${model.receivedLogs}",
                fontSize = 10.sp,
                color = Muted,
            )
            if (model.evictedLogs > 0 || model.droppedLogs > 0)
                Text(
                    "Вытеснено ${model.evictedLogs} · пропущено ${model.droppedLogs}",
                    fontSize = 10.sp,
                    color = Amber,
                )
            if (model.logging) Pill("● LIVE", Accent)
        }
    }
    export?.let { frozen ->
        var format by remember { mutableStateOf("TXT") }
        var all by remember { mutableStateOf(false) }
        val frozenVisible =
            remember(frozen, filter) {
                runCatching { frozen.filter(filter.predicate()) }.getOrDefault(emptyList())
            }
        AlertDialog(
            onDismissRequest = { export = null },
            title = { Text("Экспорт Logcat") },
            text = {
                Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Choice(
                        format,
                        listOf("TXT", "CSV", "JSONL"),
                        { it },
                        { format = it },
                        Modifier.fillMaxWidth(),
                    )
                    Check("Все сохранённые строки (${frozen.size})", all) { all = it }
                    Text(
                        if (all) "Экспорт всех строк снимка."
                        else "Видимые по фильтру: ${frozenVisible.size} строк.",
                        color = Muted,
                    )
                    Text(
                        "Запись может продолжаться. Экспорт использует снимок на момент открытия этого окна.",
                        fontSize = 11.sp,
                        color = Muted,
                    )
                }
            },
            confirmButton = {
                TextButton({
                    val entries = if (all) frozen else frozenVisible
                    export = null
                    chooseFile(true, "logcat.${format.lowercase()}", extension = format.lowercase())
                        ?.let { file ->
                            model.task("Экспорт Logcat") {
                                val result =
                                    withContext(Dispatchers.Default) {
                                        LogExport.write(entries, format)
                                    }
                                withContext(Dispatchers.IO) {
                                    AppFiles.atomicWrite(file, result.toByteArray(Charsets.UTF_8))
                                }
                                model.status = "Экспортировано ${entries.size} строк"
                            }
                        }
                }) {
                    Text("Сохранить")
                }
            },
            dismissButton = { TextButton({ export = null }) { Text("Отмена") } },
        )
    }
}
