package hunter.app

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hunter.core.tr
import hunter.ui.Check
import hunter.ui.HunterTheme
import hunter.ui.chooseFile
import hunter.ui.isPlatformShortcutPressed

@Immutable
internal data class PaletteCommand(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val action: () -> Unit,
)

@Composable
fun CommandPalette(model: AppState, dismiss: () -> Unit) {
    val navigation = Page.entries.map { page ->
        PaletteCommand(page.name, page.label, page.icon()) { model.page = page }
    }
    val actions = listOf(
        PaletteCommand("import-har", tr("Import HAR"), Icons.Outlined.FileOpen) {
            chooseFile(title = tr("Import HAR"))?.let(model.traffic::importHar)
        },
        PaletteCommand("refresh-devices", tr("Refresh devices"), Icons.Outlined.Refresh) {
            model.connection.refresh()
        },
        PaletteCommand("refresh-apps", tr("Refresh apps"), Icons.Outlined.Apps, model.connection::loadApps),
        PaletteCommand("check-tools", tr("Check tools"), Icons.Outlined.Build) {
            model.page = Page.Connection
            model.toolSetup.checkTools()
        },
        PaletteCommand("capture", tr("Start / stop capture"), Icons.Outlined.SwapVert, model::toggleCapture),
        PaletteCommand("logcat", tr("Start / stop Logcat"), Icons.Outlined.Terminal, model.logcat::toggle),
    )
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CommandPaletteContent(navigation, actions, dismiss)
    }
}

@Composable
internal fun CommandPaletteContent(
    navigation: List<PaletteCommand>,
    actions: List<PaletteCommand>,
    dismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val search = query.trim()
    val visibleNavigation = navigation.filter { it.title.contains(search, ignoreCase = true) }
    val visibleActions = actions.filter { it.title.contains(search, ignoreCase = true) }
    val commands = visibleNavigation + visibleActions
    val selected = commands.getOrNull(selectedIndex)

    fun run(command: PaletteCommand) {
        dismiss()
        command.action()
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, selected?.id) {
        if (selected == null) return@LaunchedEffect
        if (selectedIndex == 0) {
            listState.scrollToItem(0)
            return@LaunchedEffect
        }
        val layout = listState.layoutInfo
        val fullyVisible = layout.visibleItemsInfo.any {
            it.key == selected.id && it.offset >= layout.viewportStartOffset &&
                it.offset + it.size <= layout.viewportEndOffset
        }
        if (!fullyVisible) {
            val headers = if (visibleNavigation.isNotEmpty() && selectedIndex >= visibleNavigation.size) 2 else 1
            listState.scrollToItem(selectedIndex + headers)
        }
    }

    Surface(
        modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth().padding(horizontal = 12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(20.dp).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.isPlatformShortcutPressed && event.key == Key.K
                ) {
                    dismiss()
                    true
                } else false
            },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("Commands"), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                query,
                {
                    query = it
                    selectedIndex = 0
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (commands.isNotEmpty()) {
                                val delta = if (event.key == Key.DirectionUp) -1 else 1
                                selectedIndex = (selectedIndex + delta).mod(commands.size)
                            }
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            selected?.let(::run)
                            true
                        }
                        else -> false
                    }
                },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(tr("Find a command…")) },
            )

            if (commands.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(tr("No commands found"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        query = ""
                        selectedIndex = 0
                        focus.requestFocus()
                    }) { Text(tr("Clear search")) }
                }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(max = 336.dp)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        state = listState,
                    ) {
                        if (visibleNavigation.isNotEmpty()) {
                            item("navigation-heading", contentType = "heading") {
                                PaletteHeading(tr("Navigation"))
                            }
                            itemsIndexed(visibleNavigation, key = { _, row -> row.id }, contentType = { _, _ -> "command" }) { index, command ->
                                PaletteRow(command, selectedIndex == index, { selectedIndex = index }) { run(command) }
                            }
                        }
                        if (visibleActions.isNotEmpty()) {
                            item("actions-heading", contentType = "heading") {
                                PaletteHeading(tr("Actions"))
                            }
                            itemsIndexed(visibleActions, key = { _, row -> row.id }, contentType = { _, _ -> "command" }) { index, command ->
                                val position = visibleNavigation.size + index
                                PaletteRow(command, selectedIndex == position, { selectedIndex = position }) { run(command) }
                            }
                        }
                    }
                    VerticalScrollbar(
                        rememberScrollbarAdapter(listState),
                        Modifier.matchParentSize().wrapContentWidth(Alignment.End),
                    )
                }
            }

            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    tr("↑ ↓ Select · Enter Run · Esc Close"),
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = dismiss) { Text(tr("Close")) }
            }
        }
    }
}

@Composable
private fun PaletteHeading(title: String) {
    Text(
        title,
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun PaletteRow(command: PaletteCommand, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .background(
                when {
                    selected -> scheme.secondaryContainer
                    hovered -> scheme.surfaceVariant
                    else -> Color.Transparent
                },
                MaterialTheme.shapes.small,
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .selectable(selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            command.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
        Text(
            command.title,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview
@Composable
private fun LightCommandPalettePreview() = CommandPalettePreview(dark = false)

@Preview
@Composable
private fun DarkCommandPalettePreview() = CommandPalettePreview(dark = true)

@Composable
private fun CommandPalettePreview(dark: Boolean) {
    HunterTheme(dark) {
        CommandPaletteContent(
            Page.entries.map { PaletteCommand(it.name, it.label, it.icon()) {} },
            listOf(PaletteCommand("import-har", tr("Import HAR"), Icons.Outlined.FileOpen) {}),
            dismiss = {},
        )
    }
}
