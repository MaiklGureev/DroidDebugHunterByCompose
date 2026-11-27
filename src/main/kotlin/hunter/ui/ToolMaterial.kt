package hunter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
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

@Composable
fun ToolCheck(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.heightIn(min = 40.dp)
            .background(
                if (hovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .hoverable(interaction, enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .toggleable(checked, enabled, Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
