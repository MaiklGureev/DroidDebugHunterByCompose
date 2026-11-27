package hunter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun ToolAction(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
    val padding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    if (primary) Button(onClick, enabled = enabled, contentPadding = padding, content = content)
    else OutlinedButton(onClick, enabled = enabled, contentPadding = padding, content = content)
}
