@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package hunter

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Page.icon(): ImageVector =
    when (this) {
        Page.Traffic -> Icons.Outlined.SwapVert
        Page.Devices -> Icons.Outlined.Devices
        Page.Apps -> Icons.Outlined.Apps
        Page.Logcat -> Icons.Outlined.Terminal
        Page.Tools -> Icons.Outlined.Build
        Page.Settings -> Icons.Outlined.Settings
        Page.Help -> Icons.AutoMirrored.Outlined.HelpOutline
    }

@Composable
fun HunterApp(model: AppState, closing: Boolean) {
    var journalVisible by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colors.background) {
        Column(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else
                        when {
                            event.key == Key.F1 -> {
                                model.page = Page.Help
                                true
                            }
                            event.isCtrlPressed && event.key == Key.K -> {
                                model.palette = !model.palette
                                true
                            }
                            event.isCtrlPressed && event.key == Key.O -> {
                                chooseFile(title = "Импорт HAR")?.let(model::importHar)
                                true
                            }
                            else -> false
                        }
                }
                .focusable()
        ) {
            Row(Modifier.weight(1f)) {
                Surface(
                    Modifier.width(208.dp).fillMaxHeight(),
                    color = MaterialTheme.colors.surface,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.padding(8.dp, 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.BugReport,
                                null,
                                Modifier.size(30.dp),
                                tint = Accent,
                            )
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text("DROID DEBUG", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "HUNTER",
                                    fontSize = 11.sp,
                                    color = Muted,
                                    letterSpacing = 3.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        Text(
                            "РАБОЧЕЕ ПРОСТРАНСТВО",
                            Modifier.padding(start = 10.dp, bottom = 12.dp),
                            fontSize = 9.sp,
                            color = Muted,
                            letterSpacing = 1.sp,
                        )
                        Page.entries.take(5).forEach {
                            NavigationItem(it, model.page == it) { model.page = it }
                        }
                        Spacer(Modifier.weight(1f))
                        if (model.capturing || model.logging)
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (model.capturing) Pill("●  Захват активен")
                                if (model.logging) Pill("●  Logcat записывается", Accent)
                            }
                        NavigationItem(Page.Settings, model.page == Page.Settings) {
                            model.page = Page.Settings
                        }
                        NavigationItem(Page.Help, model.page == Page.Help) {
                            model.page = Page.Help
                        }
                        Divider(color = Line, modifier = Modifier.padding(vertical = 12.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Kotlin + Compose",
                                Modifier.weight(1f).padding(start = 8.dp),
                                fontSize = 10.sp,
                                color = Muted,
                            )
                            MiniIcon(
                                if (model.settings.dark) Icons.Outlined.LightMode
                                else Icons.Outlined.DarkMode,
                                "Переключить тему",
                            ) {
                                model.updateSettings(
                                    model.settings.copy(dark = !model.settings.dark)
                                )
                            }
                        }
                    }
                }
                Divider(Modifier.fillMaxHeight().width(1.dp), color = Line)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Surface(color = MaterialTheme.colors.surface) {
                        Row(
                            Modifier.fillMaxWidth().height(65.dp).padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    model.page.label,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(model.page.caption, fontSize = 11.sp, color = Muted)
                            }
                            Box(Modifier.widthIn(max = 410.dp).weight(.75f)) {
                                Choice(
                                    model.selectedDevice,
                                    model.devices.map { it as Device? },
                                    { it?.label ?: "Выберите устройство" },
                                    { it?.let(model::selectDevice) },
                                    enabled = model.busy.isEmpty(),
                                )
                            }
                            MiniIcon(
                                Icons.Outlined.Refresh,
                                "Обновить устройства",
                                !model.refreshing && model.busy.isEmpty(),
                            ) {
                                model.task("Обновление устройств") { model.refreshDevices(true) }
                            }
                            MiniIcon(Icons.Outlined.Search, "Команды · Ctrl+K") {
                                model.palette = true
                            }
                        }
                    }
                    Divider(color = Line)
                    Column(
                        Modifier.weight(1f).padding(horizontal = 22.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (model.error.isNotEmpty())
                            Surface(
                                color = MaterialTheme.colors.error.copy(alpha = .12f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.ErrorOutline,
                                        null,
                                        tint = MaterialTheme.colors.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        model.error,
                                        Modifier.weight(1f).padding(10.dp),
                                        color = MaterialTheme.colors.error,
                                        maxLines = 4,
                                    )
                                    MiniIcon(Icons.Outlined.Close, "Скрыть ошибку") {
                                        model.error = ""
                                    }
                                }
                            }
                        Box(Modifier.weight(1f)) {
                            when (model.page) {
                                Page.Traffic -> TrafficScreen(model)
                                Page.Devices -> DevicesScreen(model)
                                Page.Apps -> AppsScreen(model)
                                Page.Logcat -> LogcatScreen(model)
                                Page.Tools -> ToolsScreen(model)
                                Page.Settings -> SettingsScreen(model)
                                Page.Help -> HelpScreen(model)
                            }
                        }
                    }
                    if (journalVisible)
                        Column(Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 22.dp)) {
                            Text(
                                "Журнал приложения · ${model.journal.size}",
                                color = Muted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            MonoText(model.journal.joinToString("\n"), Modifier.fillMaxSize())
                        }
                }
            }
            Divider(color = Line)
            Surface(color = MaterialTheme.colors.surface) {
                Row(
                    Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (model.busy.isNotEmpty() || closing) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                        Text(if (closing) "Завершение сессий…" else model.busy, fontSize = 11.sp)
                    } else {
                        Box(
                            Modifier.size(6.dp)
                                .background(
                                    if (model.error.isEmpty()) Green else Red,
                                    RoundedCornerShape(3.dp),
                                )
                        )
                        Text(
                            model.status,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (model.busy.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        TextButton(model::cancelOperation, contentPadding = PaddingValues(0.dp)) {
                            Text("Отмена", fontSize = 11.sp)
                        }
                    }
                    TextButton(
                        { journalVisible = !journalVisible },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(if (journalVisible) "Скрыть журнал" else "Журнал", fontSize = 11.sp)
                    }
                    Text("F1  справка", fontSize = 10.sp, color = Muted)
                }
            }
        }
    }
    model.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { model.confirmation = null },
            title = { Text(confirmation.title) },
            text = { Text(confirmation.text) },
            confirmButton = {
                TextButton({
                    model.confirmation = null
                    confirmation.run()
                }) {
                    Text("Подтвердить", color = MaterialTheme.colors.error)
                }
            },
            dismissButton = { TextButton({ model.confirmation = null }) { Text("Отмена") } },
        )
    }
    if (model.palette) CommandPalette(model) { model.palette = false }
}

@Composable
private fun NavigationItem(page: Page, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(
                if (selected) Accent.copy(alpha = .14f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(page.icon(), null, Modifier.size(19.dp), tint = if (selected) Accent else Muted)
        Spacer(Modifier.width(12.dp))
        Text(
            page.label,
            color = if (selected) Accent else LocalContentColor.current,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun CommandPalette(model: AppState, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val commands =
        Page.entries.map { it.label to { model.page = it } } +
            listOf(
                "Импорт HAR" to
                    {
                        chooseFile(title = "Импорт HAR")?.let(model::importHar)
                        Unit
                    },
                "Обновить устройства" to
                    {
                        model.task("Обновление устройств") { model.refreshDevices(true) }
                    },
                "Обновить приложения" to model::loadApps,
                "Проверить утилиты" to model::checkTools,
                "Начать / остановить захват" to model::toggleCapture,
                "Начать / остановить Logcat" to model::toggleLogcat,
            )
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Команды") },
        text = {
            Column(Modifier.width(520.dp)) {
                Field(query, { query = it }, "Найти команду…", Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(commands.filter { it.first.contains(query, true) }) { (title, action) ->
                        Text(
                            title,
                            Modifier.fillMaxWidth()
                                .clickable {
                                    dismiss()
                                    action()
                                }
                                .padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("Закрыть") } },
    )
}

@Composable
fun AppPicker(model: AppState, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            { open = true },
            modifier = Modifier.weight(1f).height(38.dp),
            enabled = enabled,
            border = BorderStroke(1.dp, Line),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    backgroundColor = Color.Transparent,
                    contentColor = MaterialTheme.colors.onSurface,
                ),
        ) {
            Icon(Icons.Outlined.Android, null, Modifier.size(17.dp), tint = Muted)
            Spacer(Modifier.width(8.dp))
            Text(
                model.selectedApp?.label ?: "Выберите приложение",
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(17.dp))
        }
        MiniIcon(
            Icons.Outlined.Refresh,
            "Загрузить приложения",
            enabled && model.busy.isEmpty() && model.selectedDevice?.state == "device",
            model::loadApps,
        )
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        var group by remember { mutableStateOf("Все") }
        val apps =
            model.apps.filter {
                it.matches(query) && (group == "Все" || it.system == (group == "Системные"))
            }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Выбор приложения") },
            text = {
                Column(Modifier.width(650.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        query,
                        { query = it },
                        "Название, пакет, UID или App ID",
                        Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Choice(
                            group,
                            listOf("Все", "Сторонние", "Системные"),
                            { it },
                            { group = it },
                            Modifier.width(180.dp),
                        )
                        Text("${apps.size} приложений", color = Muted)
                    }
                    if (model.apps.isEmpty())
                        Text(
                            "Загрузите приложения выбранного устройства.",
                            color = Muted,
                            modifier = Modifier.padding(20.dp),
                        )
                    LazyColumn(Modifier.height(360.dp)) {
                        items(apps, key = { it.packageName }) { app ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        model.selectApp(app)
                                        open = false
                                    }
                                    .padding(vertical = 11.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Android,
                                    null,
                                    Modifier.size(24.dp),
                                    tint = if (app.system) Muted else Green,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, fontWeight = FontWeight.Medium)
                                    if (app.name.isNotBlank())
                                        Text(app.packageName, fontSize = 11.sp, color = Muted)
                                }
                                Text(
                                    "UID ${app.uid ?: "?"}\nApp ID ${app.appId}",
                                    fontSize = 10.sp,
                                    color = Muted,
                                )
                            }
                            Divider(color = Line)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ open = false }) { Text("Закрыть") } },
            dismissButton = {
                TextButton(
                    model::loadApps,
                    enabled = model.busy.isEmpty() && model.selectedDevice?.state == "device",
                ) {
                    Text("Обновить")
                }
            },
        )
    }
}

@Composable
fun TrafficScreen(model: AppState) {
    var query by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var identity by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("Время ↓") }
    val snapshot = model.exchanges.toList()
    val visible by
        produceState(emptyList<Exchange>(), snapshot, query, pkg, identity, sort) {
            value =
                withContext(Dispatchers.Default) {
                    snapshot
                        .filter { it.matches(query, pkg, identity) }
                        .let { entries ->
                            when (sort) {
                                "Время ↑" -> entries.sortedBy { it.instant }
                                "Статус" -> entries.sortedByDescending { it.status ?: 0 }
                                "Длительность" -> entries.sortedByDescending { it.duration ?: 0.0 }
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
                model.error = e.message ?: "cURL недоступен"
            }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Choice(
                        model.mode,
                        CaptureMode.entries,
                        { it.title },
                        { model.mode = it },
                        Modifier.width(190.dp),
                        enabled && !model.capturing,
                    )
                    AppPicker(model, Modifier.weight(1f), enabled && !model.capturing)
                    Action(
                        if (model.capturing) "Остановить" else "Начать захват",
                        if (model.capturing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                        enabled && (model.capturing || model.selectedDevice?.state == "device"),
                        primary = true,
                        danger = model.capturing,
                        onClick = model::toggleCapture,
                    )
                    MiniIcon(Icons.Outlined.Tune, "Настроить захват") { setup = true }
                }
                Text(model.mode.hint, color = Muted, fontSize = 11.sp)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Field(
                query,
                { query = it },
                "Поиск по URL, заголовкам, методу, статусу…",
                Modifier.weight(1f),
            )
            Field(pkg, { pkg = it }, "Приложение / пакет", Modifier.width(175.dp))
            Field(identity, { identity = it }, "UID / App ID", Modifier.width(115.dp))
            MiniIcon(Icons.Outlined.FilterAltOff, "Сбросить фильтры") {
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
                "${visible.size} / ${snapshot.size} запросов",
                fontSize = 12.sp,
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            Choice(
                sort,
                listOf("Время ↓", "Время ↑", "Статус", "Длительность"),
                { it },
                { sort = it },
                Modifier.width(145.dp),
            )
            Action("Импорт HAR", Icons.Outlined.FileOpen, enabled) {
                chooseFile(title = "Импорт HAR")?.let(model::importHar)
            }
            Action("Экспорт HAR", Icons.Outlined.FileDownload, enabled && visible.isNotEmpty()) {
                chooseFile(true, "traffic.har", extension = "har")?.let {
                    model.saveHar(it, visible.toList())
                }
            }
            MiniIcon(Icons.Outlined.DeleteOutline, "Очистить список", enabled) {
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
                    TableCell("ВРЕМЯ", 105)
                    TableCell("МЕТОД", 65)
                    TableCell("СТАТУС", 65)
                    Text("ХОСТ / ПУТЬ", Modifier.weight(1f), fontSize = 10.sp, color = Muted)
                    TableCell("ПРИЛОЖЕНИЕ", 150)
                    TableCell("ВРЕМЯ, МС", 85)
                }
                Divider(color = Line)
                if (visible.isEmpty())
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            Icons.Outlined.SwapVert,
                            if (snapshot.isEmpty()) "Трафик появится здесь" else "Нет совпадений",
                            if (snapshot.isEmpty())
                                "Начните захват с Android или откройте HAR-файл."
                            else "Измените поиск или сбросьте фильтры.",
                        ) {
                            if (snapshot.isEmpty())
                                Action("Импортировать HAR", Icons.Outlined.FileOpen) {
                                    chooseFile(title = "Импорт HAR")?.let(model::importHar)
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
                                                e.packageName.ifBlank { "Неизвестно" }
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
            Action("Детали", Icons.AutoMirrored.Outlined.OpenInNew, selected != null) {
                selected?.let(::openDetails)
            }
            Action("Копировать cURL", Icons.Outlined.Code, selected != null && !selected.raw) {
                copyCurl()
            }
            Action("Копировать URL", Icons.Outlined.Link, selected != null) {
                selected?.let { copyText(model, it.url) }
            }
            Spacer(Modifier.weight(1f))
            if (model.evictedRequests > 0)
                Text("Вытеснено: ${model.evictedRequests}", color = Amber, fontSize = 11.sp)
            if (model.capturing) Pill("● LIVE")
        }
    }
    if (setup) CaptureSetup(model) { setup = false }
}

@Composable
private fun TableCell(text: String, width: Int) {
    Text(text, Modifier.width(width.dp), fontSize = 10.sp, color = Muted)
}

@Composable
fun CaptureSetup(model: AppState, dismiss: () -> Unit) {
    val enabled = model.busy.isEmpty() && !model.capturing
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Подготовка захвата") },
        text = {
            Column(
                Modifier.width(720.dp).heightIn(max = 580.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("1. Устройство и приложение", fontWeight = FontWeight.SemiBold)
                Text(
                    model.selectedDevice?.label
                        ?: "Подключите телефон по USB, разрешите отладку и выберите устройство.",
                    color = Muted,
                )
                AppPicker(model, Modifier.fillMaxWidth(), enabled)
                Choice(
                    model.mode,
                    CaptureMode.entries,
                    { it.title },
                    { model.mode = it },
                    Modifier.fillMaxWidth(),
                    enabled,
                )
                Text(model.mode.hint, color = Muted)
                Divider(color = Line)
                if (model.mode == CaptureMode.OkHttp || model.mode == CaptureMode.Native) {
                    Text("2. Frida", fontWeight = FontWeight.SemiBold)
                    Check(
                        "Без root: debuggable + run-as + Gadget",
                        model.settings.gadget,
                        enabled,
                    ) {
                        model.updateSettings(model.settings.copy(gadget = it))
                    }
                    Check("Запустить приложение при старте", model.settings.spawn, enabled) {
                        model.updateSettings(model.settings.copy(spawn = it))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("Установить сервер", enabled = enabled) { model.installFrida() }
                        Action("Запустить сервер", enabled = enabled, onClick = model::startFrida)
                    }
                    Text(
                        "Frida клиент/сервер/Gadget: ${Adb.FRIDA_VERSION}. Для сервера нужен root. Gadget работает только с debuggable-приложением.",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                } else {
                    Text("2. HTTPS / сертификат", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Подготовьте CA, передайте его на Android и вручную установите как «Сертификат CA». Приложение должно доверять пользовательским CA.",
                        color = Muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("Подготовить CA", enabled = enabled) { model.prepareCapture(false) }
                        Action(
                            "Передать CA",
                            enabled = model.busy.isEmpty(),
                            onClick = model::sendCa,
                        )
                    }
                    if (model.mode == CaptureMode.Vpn) {
                        Text("3. WireGuard", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(
                                model.settings.vpnHost,
                                { model.updateSettings(model.settings.copy(vpnHost = it)) },
                                "IP / DNS компьютера",
                                Modifier.weight(1f),
                                enabled,
                            )
                            Field(
                                model.settings.vpnPort,
                                { model.updateSettings(model.settings.copy(vpnPort = it)) },
                                "UDP-порт",
                                Modifier.width(130.dp),
                                enabled,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action("Подготовить профиль", enabled = enabled) {
                                model.prepareCapture(true)
                            }
                            Action(
                                "Передать в WireGuard",
                                enabled = model.busy.isEmpty(),
                                onClick = model::sendVpn,
                            )
                        }
                        Text(
                            "Откройте UDP-порт в брандмауэре. В WireGuard: + → Импорт из файла → Download/ddh.conf. Включите туннель после запуска сервера. Для автоматической остановки разрешите Remote control.",
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    }
                }
                Divider(color = Line)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(
                        "Проверить готовность",
                        enabled = model.busy.isEmpty(),
                        onClick = model::checkCapture,
                    )
                    Action("Руководство") {
                        model.page = Page.Help
                        dismiss()
                    }
                }
                model.modeChecks.forEach { check ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            when (check.ready) {
                                true -> Icons.Outlined.CheckCircleOutline
                                false -> Icons.Outlined.ErrorOutline
                                else -> Icons.Outlined.Info
                            },
                            null,
                            Modifier.size(17.dp),
                            tint =
                                when (check.ready) {
                                    true -> Green
                                    false -> Red
                                    else -> Amber
                                },
                        )
                        Column {
                            Text(check.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(check.detail, color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                if (model.error.isNotEmpty()) Text(model.error, color = MaterialTheme.colors.error)
                if (model.busy.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                {
                    dismiss()
                    model.toggleCapture()
                },
                enabled =
                    model.busy.isEmpty() &&
                        (model.capturing || model.selectedDevice?.state == "device"),
            ) {
                Text(if (model.capturing) "Остановить" else "Начать захват")
            }
        },
        dismissButton = { TextButton(dismiss) { Text("Закрыть") } },
    )
}
