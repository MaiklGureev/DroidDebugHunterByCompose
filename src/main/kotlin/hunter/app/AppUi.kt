@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package hunter.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hunter.core.tr
import hunter.features.android.Device
import hunter.features.android.ui.AppsScreen
import hunter.features.android.ui.DevicesScreen
import hunter.features.android.ui.ToolsScreen
import hunter.features.connection.ui.ConnectionScreen
import hunter.features.help.ui.HelpScreen
import hunter.features.logcat.ui.LogcatScreen
import hunter.features.settings.Settings
import hunter.features.settings.ui.SettingsScreen
import hunter.features.traffic.ui.TrafficScreen
import hunter.ui.Accent
import hunter.ui.Choice
import hunter.ui.Green
import hunter.ui.Line
import hunter.ui.MiniIcon
import hunter.ui.MonoText
import hunter.ui.Muted
import hunter.ui.Pill
import hunter.ui.Red
import hunter.ui.chooseFile
import hunter.ui.isDarkTheme
import hunter.ui.isPlatformShortcutPressed

fun Page.icon(): ImageVector =
    when (this) {
        Page.Connection -> Icons.Outlined.Cable
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
    val dark = model.settings.isDarkTheme()
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
                            event.isPlatformShortcutPressed && event.key == Key.K -> {
                                model.palette = !model.palette
                                true
                            }
                            event.isPlatformShortcutPressed && event.key == Key.O -> {
                                chooseFile(title = tr("Import HAR"))?.let(model::importHar)
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
                            tr("WORKSPACE"),
                            Modifier.padding(start = 10.dp, bottom = 12.dp),
                            fontSize = 9.sp,
                            color = Muted,
                            letterSpacing = 1.sp,
                        )
                        Page.entries.takeWhile { it != Page.Settings }.forEach {
                            NavigationItem(it, model.page == it) { model.page = it }
                        }
                        Spacer(Modifier.weight(1f))
                        if (model.capturing || model.logging)
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (model.capturing) Pill(tr("●  Capture active"))
                                if (model.logging) Pill(tr("●  Logcat recording"), Accent)
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
                                if (dark) Icons.Outlined.LightMode
                                else Icons.Outlined.DarkMode,
                                tr("Switch theme"),
                            ) {
                                model.updateSettings(
                                    model.settings.copy(dark = !dark, followSystemTheme = false)
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
                            if (model.page != Page.Connection && model.page != Page.Traffic) {
                                Box(Modifier.widthIn(max = 410.dp).weight(.75f)) {
                                    Choice(
                                        model.selectedDevice,
                                        model.devices.map { it as Device? },
                                        { it?.label ?: tr("Select a device") },
                                        { it?.let(model::selectDevice) },
                                        enabled = model.busy.isEmpty(),
                                    )
                                }
                                MiniIcon(
                                    Icons.Outlined.Refresh,
                                    tr("Refresh devices"),
                                    !model.refreshing && model.busy.isEmpty(),
                                ) {
                                    model.task(tr("Refreshing devices")) { model.refreshDevices(true) }
                                }
                            }
                            MiniIcon(Icons.Outlined.Search, tr("Commands · Ctrl+K")) {
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
                                    MiniIcon(Icons.Outlined.Close, tr("Dismiss error")) {
                                        model.error = ""
                                    }
                                }
                            }
                        Box(Modifier.weight(1f)) {
                            when (model.page) {
                                Page.Connection -> ConnectionScreen(model)
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
                                tr("Application journal · {0}", model.journal.size),
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
                        val progress = model.setupProgress
                        val caption =
                            if (closing) tr("Closing sessions…")
                            else
                                progress?.let {
                                    it.phase +
                                        (it.fraction?.let { value ->
                                            " · ${(value * 100).toInt()}%"
                                        } ?: "") +
                                        " · " +
                                        it.detail
                                } ?: model.busy
                        Text(
                            caption,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        progress?.fraction?.let {
                            LinearProgressIndicator(
                                progress = it,
                                modifier = Modifier.width(110.dp),
                            )
                        }
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
                        TextButton(model::cancelOperation, contentPadding = PaddingValues(0.dp)) {
                            Text(tr("Cancel"), fontSize = 11.sp)
                        }
                    }
                    TextButton(
                        { journalVisible = !journalVisible },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            if (journalVisible) tr("Hide journal") else tr("Journal"),
                            fontSize = 11.sp,
                        )
                    }
                    Text(tr("F1  help"), fontSize = 10.sp, color = Muted)
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
                    Text(tr("Confirm"), color = MaterialTheme.colors.error)
                }
            },
            dismissButton = { TextButton({ model.confirmation = null }) { Text(tr("Cancel")) } },
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
