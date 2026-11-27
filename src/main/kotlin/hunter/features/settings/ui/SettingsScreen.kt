package hunter.features.settings.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hunter.app.AppState
import hunter.core.AppFiles
import hunter.core.UiLanguage
import hunter.core.tr
import hunter.ui.Action
import hunter.ui.Check
import hunter.ui.Choice
import hunter.ui.Muted
import hunter.ui.Panel
import hunter.ui.ToolCheck
import hunter.ui.isDarkTheme
import hunter.ui.openFile

@Composable
fun SettingsScreen(model: AppState) {
    val dark = model.settings.isDarkTheme()
    var draft by
        remember(model.settings.copy(language = UiLanguage.English, dark = false, followSystemTheme = false)) {
            mutableStateOf(model.settings)
        }
    val enabled = model.busy.isEmpty() && !model.capturing && !model.logging
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(tr("Interface language"), modifier = Modifier.fillMaxWidth()) {
            Choice(
                model.settings.language,
                UiLanguage.entries,
                { it.nativeName },
                {
                    model.changeLanguage(it)
                    draft = draft.copy(language = it)
                },
                Modifier.width(230.dp),
            )
            Text(tr("Language changes immediately and is saved automatically."), color = Muted)
        }
        Panel(tr("Behavior and memory"), modifier = Modifier.fillMaxWidth()) {
            Check(tr("Automatically refresh devices every 5 seconds"), draft.autoRefresh, enabled) {
                draft = draft.copy(autoRefresh = it)
            }
            ToolCheck(tr("Follow system theme"), model.settings.followSystemTheme) {
                model.updateSettings(model.settings.copy(followSystemTheme = it, dark = dark))
                draft = draft.copy(followSystemTheme = it, dark = dark)
            }
            ToolCheck(tr("Dark theme"), dark, !model.settings.followSystemTheme) {
                model.updateSettings(model.settings.copy(dark = it))
                draft = draft.copy(dark = it)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(tr("Logcat rows"), color = Muted)
                Choice(
                    draft.maxLogs,
                    listOf(20000, 50000, 100000),
                    { it.toString() },
                    { draft = draft.copy(maxLogs = it) },
                    Modifier.width(130.dp),
                    enabled,
                )
                Text(tr("HTTP requests"), color = Muted)
                Choice(
                    draft.maxRequests,
                    listOf(1000, 5000, 10000, 20000),
                    { it.toString() },
                    { draft = draft.copy(maxRequests = it) },
                    Modifier.width(130.dp),
                    enabled,
                )
            }
            Text(
                tr(
                    "Shutdown reaps owned processes, restores the proxy and removes owned ADB forwards/reverses. The shared ADB server keeps running."
                ),
                color = Muted,
                fontSize = 11.sp,
            )
            Action(tr("Apply"), Icons.Outlined.Check, enabled) {
                model.updateSettings(model.settings.copy(
                    autoRefresh = draft.autoRefresh,
                    maxLogs = draft.maxLogs,
                    maxRequests = draft.maxRequests,
                ))
                model.status = tr("Settings applied")
            }
        }
        Panel(
            tr("CA and VPN"),
            tr("CA and keys persist between launches. Reset creates a backup."),
            Modifier.fillMaxWidth(),
        ) {
            Text(AppFiles.capture.absolutePath, color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(tr("Open folder")) { openFile(model, AppFiles.capture) }
                Action(
                    tr("Reset CA and VPN…"),
                    enabled = enabled,
                    danger = true,
                    onClick = model::resetCapture,
                )
            }
        }
    }
}
