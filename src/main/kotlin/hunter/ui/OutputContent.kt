package hunter.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hunter.app.AppState
import hunter.app.OutputWindow
import hunter.core.tr

@Composable
fun OutputContent(model: AppState, output: OutputWindow) {
    Surface(color = MaterialTheme.colors.surface) {
        Column(
            Modifier.fillMaxSize()
                .padding(20.dp)
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        model.outputs.remove(output)
                        true
                    } else false
                }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    output.title,
                    Modifier.weight(1f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Action(tr("Copy all"), Icons.Outlined.ContentCopy) { copyText(model, output.text) }
                Action(tr("Save TXT"), Icons.Outlined.Save, model.busy.isEmpty()) {
                    chooseFile(true, "adb-result.txt", extension = "txt")?.let {
                        model.saveText(it, output.text)
                    }
                }
            }
            MonoText(output.text, Modifier.weight(1f).fillMaxWidth())
            Text(tr("UTF-8 · Esc — close window"), color = Muted, fontSize = 11.sp)
        }
    }
}
