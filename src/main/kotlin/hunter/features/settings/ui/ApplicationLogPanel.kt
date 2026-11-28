@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hunter.features.settings.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hunter.core.tr
import hunter.ui.HunterTheme
import hunter.ui.ToolAction

@Composable
internal fun ApplicationLogPanel(
    path: String,
    fileError: String?,
    enabled: Boolean,
    export: () -> Unit,
    clear: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Application log"), style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(path, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (fileError != null) {
                Text(
                    tr("Cannot write application log: {0}", fileError),
                    color = scheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolAction(tr("Export log"), Icons.Outlined.Save, enabled, onClick = export)
                ToolAction(tr("Clear log…"), Icons.Outlined.DeleteOutline, enabled, onClick = clear)
            }
        }
    }
}

@Preview
@Composable
private fun LightApplicationLogPreview() = ApplicationLogPreview(dark = false)

@Preview
@Composable
private fun DarkApplicationLogPreview() = ApplicationLogPreview(dark = true)

@Composable
private fun ApplicationLogPreview(dark: Boolean) {
    HunterTheme(dark) {
        ApplicationLogPanel("logs/application.log", fileError = null, enabled = true, export = {}, clear = {})
    }
}
