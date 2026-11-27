package hunter.features.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hunter.app.AppState
import hunter.core.tr
import hunter.ui.Choice
import hunter.ui.Field
import hunter.ui.Green
import hunter.ui.Line
import hunter.ui.MiniIcon
import hunter.ui.Muted

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
                model.selectedApp?.label ?: tr("Select an app"),
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(17.dp))
        }
        MiniIcon(
            Icons.Outlined.Refresh,
            tr("Load apps"),
            enabled && model.busy.isEmpty() && model.selectedDevice?.state == "device",
            model::loadApps,
        )
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        var group by remember { mutableStateOf(0) }
        val apps =
            model.apps.filter { it.matches(query) && (group == 0 || it.system == (group == 2)) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(tr("Choose an app")) },
            text = {
                Column(Modifier.width(650.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        query,
                        { query = it },
                        tr("Name, package, UID or App ID"),
                        Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Choice(
                            group,
                            listOf(0, 1, 2),
                            { listOf(tr("All"), tr("User apps"), tr("System apps"))[it] },
                            { group = it },
                            Modifier.width(180.dp),
                        )
                        Text(tr("{0} apps", apps.size), color = Muted)
                    }
                    if (model.apps.isEmpty())
                        Text(
                            tr("Load apps for the selected device."),
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
            confirmButton = { TextButton({ open = false }) { Text(tr("Close")) } },
            dismissButton = {
                TextButton(
                    model::loadApps,
                    enabled = model.busy.isEmpty() && model.selectedDevice?.state == "device",
                ) {
                    Text(tr("Refresh"))
                }
            },
        )
    }
}
