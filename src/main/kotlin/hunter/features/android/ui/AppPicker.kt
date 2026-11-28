@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter.features.android.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hunter.app.AppState
import hunter.core.tr
import hunter.features.android.AndroidApp
import hunter.ui.HunterTheme
import hunter.ui.MiniIcon
import hunter.ui.ToolAction

@Composable
fun AppPicker(model: AppState, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val connection = model.connection
    val state by connection.uiState.collectAsState()
    var open by remember { mutableStateOf(false) }
    val connected = state.selectedDevice?.state == "device"
    val canLoad = enabled && model.busy.isEmpty() && connected
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            { open = true },
            modifier = Modifier.weight(1f).heightIn(min = 38.dp),
            enabled = enabled,
        ) {
            Icon(Icons.Outlined.Android, contentDescription = null, Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                state.selectedApp?.label ?: tr("Select an app"),
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, Modifier.size(17.dp))
        }
        MiniIcon(Icons.Outlined.Refresh, tr("Load apps"), canLoad, connection::loadApps)
    }
    if (open) {
        Dialog(
            onDismissRequest = { open = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AppPickerDialogContent(
                apps = state.apps,
                connected = connected,
                loading = model.busy == tr("Loading apps"),
                canLoad = canLoad,
                load = connection::loadApps,
                select = {
                    connection.selectApp(it)
                    open = false
                },
                dismiss = { open = false },
            )
        }
    }
}

private enum class AppGroup(val titleKey: String) {
    All("All"),
    User("User apps"),
    System("System apps"),
}

@Composable
internal fun AppPickerDialogContent(
    apps: List<AndroidApp>,
    connected: Boolean,
    loading: Boolean,
    canLoad: Boolean,
    load: () -> Unit,
    select: (AndroidApp) -> Unit,
    dismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(AppGroup.All) }
    val results = remember(apps, query, group) {
        val search = query.trim()
        apps.filter { it.matches(search) && (group == AppGroup.All || it.system == (group == AppGroup.System)) }
    }
    val focus = remember { FocusRequester() }
    val hasApps = apps.isNotEmpty()
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
        val listHeight = (maxHeight * .55f).coerceAtMost(380.dp)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surface,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Choose an app"), style = MaterialTheme.typography.titleLarge)
                if (hasApps) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(tr("Name, package, UID or App ID")) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                            val app = results.singleOrNull()
                            if (app != null && event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                select(app)
                                true
                            } else false
                        },
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (entry in AppGroup.entries) {
                            FilterChip(
                                selected = entry == group,
                                onClick = { group = entry },
                                label = { Text(tr(entry.titleKey)) },
                            )
                        }
                        Text(tr("{0} apps", results.size), Modifier.align(Alignment.CenterVertically),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant)
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (results.isEmpty()) {
                    val message = when {
                        loading -> "Loading apps"
                        hasApps -> "No matches"
                        !connected -> "Select an authorized Android device"
                        else -> "Load apps for the selected device."
                    }
                    Text(tr(message), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(query, group) { listState.scrollToItem(0) }
                    Box(Modifier.fillMaxWidth().heightIn(max = listHeight)) {
                        LazyColumn(Modifier.fillMaxWidth().padding(end = 10.dp), state = listState) {
                            items(results, key = { it.packageName }, contentType = { "app" }) { app ->
                                ListItem(
                                    headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = if (app.name.isBlank()) null else {
                                        { Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    },
                                    leadingContent = { Icon(Icons.Outlined.Android, contentDescription = null) },
                                    trailingContent = { Text("UID ${app.uid ?: "?"}\nApp ID ${app.appId}") },
                                    modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                                        .clickable { select(app) },
                                )
                            }
                        }
                        if (listState.canScrollForward || listState.canScrollBackward) {
                            VerticalScrollbar(
                                rememberScrollbarAdapter(listState),
                                Modifier.align(Alignment.CenterEnd).matchParentSize().wrapContentWidth(Alignment.End),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = dismiss) { Text(tr("Close")) }
                    ToolAction(
                        tr(if (hasApps) "Refresh" else "Load apps"),
                        Icons.Outlined.Refresh,
                        canLoad,
                        primary = !hasApps,
                        onClick = load,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun LightAppPickerPreview() = HunterTheme(dark = false) {
    AppPickerDialogContent(
        apps = listOf(AndroidApp("com.example.demo", 10001, 0, false, "Demo")),
        connected = true, loading = false, canLoad = true, load = {}, select = {}, dismiss = {},
    )
}

@Preview
@Composable
private fun DarkAppPickerPreview() = HunterTheme(dark = true) {
    AppPickerDialogContent(
        apps = emptyList(), connected = true, loading = false, canLoad = true,
        load = {}, select = {}, dismiss = {},
    )
}
