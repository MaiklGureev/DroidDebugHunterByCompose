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
import hunter.ui.chooseFile
import hunter.ui.isDarkTheme
import hunter.ui.openFile

@Composable
fun SettingsScreen(model: AppState) {
    val viewModel = model.settings
    val settings by viewModel.uiState.collectAsState()
    val dark = settings.isDarkTheme()
    var autoRefresh by remember(settings.autoRefresh) { mutableStateOf(settings.autoRefresh) }
    var maxLogs by remember(settings.maxLogs) { mutableStateOf(settings.maxLogs) }
    var maxRequests by remember(settings.maxRequests) { mutableStateOf(settings.maxRequests) }
    val capturing by model.traffic.running.collectAsState(model.traffic.uiState.value.running)
    val logging by model.logcat.running.collectAsState(model.logcat.uiState.value.running)
    val enabled = model.busy.isEmpty() && !capturing && !logging
    val logFileError by model.applicationLog.fileError.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(tr("Interface language"), modifier = Modifier.fillMaxWidth()) {
            Choice(
                settings.language,
                UiLanguage.entries,
                { it.nativeName },
                viewModel::setLanguage,
                Modifier.width(230.dp),
            )
            Text(tr("Language changes immediately and is saved automatically."), color = Muted)
        }
        Panel(tr("Behavior and memory"), modifier = Modifier.fillMaxWidth()) {
            Check(tr("Automatically refresh devices every 5 seconds"), autoRefresh, enabled) {
                autoRefresh = it
            }
            ToolCheck(tr("Follow system theme"), settings.followSystemTheme) {
                viewModel.setFollowSystemTheme(it, dark)
            }
            ToolCheck(tr("Dark theme"), dark, !settings.followSystemTheme, onChange = viewModel::setDarkTheme)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(tr("Logcat rows"), color = Muted)
                Choice(
                    maxLogs,
                    listOf(20000, 50000, 100000),
                    { it.toString() },
                    { maxLogs = it },
                    Modifier.width(130.dp),
                    enabled,
                )
                Text(tr("HTTP requests"), color = Muted)
                Choice(
                    maxRequests,
                    listOf(1000, 5000, 10000, 20000),
                    { it.toString() },
                    { maxRequests = it },
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
                viewModel.applyBehavior(autoRefresh, maxLogs, maxRequests)
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
                    onClick = model.connection::resetCapture,
                )
            }
        }
        ApplicationLogPanel(
            path = model.applicationLog.file.absolutePath,
            fileError = logFileError,
            enabled = model.busy.isEmpty(),
            export = {
                chooseFile(save = true, name = "droiddebughunter.log", title = tr("Export log"), extension = "log")
                    ?.let(model::exportApplicationLog)
            },
            clear = model::confirmClearApplicationLog,
        )
    }
}
