@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package hunter.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.LocalContentColor as Material3ContentColor
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hunter.app.AppState
import hunter.core.HostOs
import hunter.core.HostPlatform
import hunter.core.Processes
import hunter.core.tr
import hunter.features.settings.Settings
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

val Accent = Color(0xFF5B92FF)
val Green = Color(0xFF42CFA0)
val Amber = Color(0xFFF2B65E)
val Red = Color(0xFFF17483)
val Line
    @Composable get() = MaterialTheme.colors.onSurface.copy(alpha = .10f)
val Muted
    @Composable get() = MaterialTheme.colors.onSurface.copy(alpha = .56f)

@Composable
fun Settings.isDarkTheme(): Boolean = if (followSystemTheme) isSystemInDarkTheme() else dark

@Composable
fun HunterTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors =
        if (dark)
            darkColors(
                primary = Accent,
                primaryVariant = Color(0xFF3672DD),
                secondary = Green,
                background = Color(0xFF0F1420),
                surface = Color(0xFF171E2D),
                onPrimary = Color.White,
                error = Red,
            )
        else
            lightColors(
                primary = Color(0xFF356BD7),
                secondary = Color(0xFF148661),
                background = Color(0xFFF3F5F9),
                surface = Color.White,
                error = Color(0xFFBC354B),
            )
    MaterialTheme(
        colors = colors,
        typography =
            Typography(
                defaultFontFamily = FontFamily.SansSerif,
                body1 = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                body2 = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                button =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            ),
        shapes = Shapes(small = RoundedCornerShape(7.dp), medium = RoundedCornerShape(10.dp)),
    ) {
        val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
            primary = colors.primary,
            onPrimary = if (dark) colors.background else colors.onPrimary,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.surface,
            onSurface = colors.onSurface,
            error = colors.error,
        )
        Material3Theme(colorScheme = scheme) {
            CompositionLocalProvider(Material3ContentColor provides scheme.onSurface, content = content)
        }
    }
}

@Composable
fun Action(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, maxLines = 1)
    }
    if (primary)
        Button(
            onClick,
            enabled = enabled,
            colors =
                ButtonDefaults.buttonColors(
                    backgroundColor =
                        if (danger) MaterialTheme.colors.error else MaterialTheme.colors.primary
                ),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp),
            elevation = ButtonDefaults.elevation(0.dp),
            content = content,
        )
    else
        OutlinedButton(
            onClick,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 9.dp),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    backgroundColor = Color.Transparent,
                    contentColor =
                        if (danger) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
                ),
            border = BorderStroke(1.dp, Line),
            content = content,
        )
}

@Composable
fun MiniIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(elevation = 4.dp, shape = RoundedCornerShape(6.dp)) {
                Text(description, Modifier.padding(8.dp))
            }
        }
    ) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(icon, description, Modifier.size(18.dp))
        }
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    password: Boolean = false,
) {
    OutlinedTextField(
        value,
        onChange,
        modifier.height(54.dp),
        label = { Text(placeholder, fontSize = 11.sp) },
        singleLine = true,
        enabled = enabled,
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
        visualTransformation =
            if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        colors =
            TextFieldDefaults.outlinedTextFieldColors(
                unfocusedBorderColor = Line,
                disabledBorderColor = Line,
            ),
    )
}

@Composable
fun <T> Choice(
    value: T,
    items: List<T>,
    label: (T) -> String,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(38.dp),
            border = BorderStroke(1.dp, Line),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    backgroundColor = Color.Transparent,
                    contentColor = MaterialTheme.colors.onSurface,
                ),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Text(label(value), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(17.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem({
                    expanded = false
                    onChange(item)
                }) {
                    Text(label(item), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun Check(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Checkbox(checked, onChange, enabled = enabled, modifier = Modifier.size(32.dp))
        Text(text, color = if (enabled) LocalContentColor.current else Muted, fontSize = 12.sp)
    }
}

@Composable
fun Pill(text: String, color: Color = Green) {
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(5.dp)) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun Panel(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 12.sp)
            content()
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(color = Accent.copy(alpha = .1f), shape = RoundedCornerShape(16.dp)) {
            Icon(icon, null, Modifier.padding(18.dp).size(34.dp), tint = Accent)
        }
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = Muted, fontSize = 13.sp)
        action()
    }
}

@Composable
fun MonoText(value: String, modifier: Modifier = Modifier) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    Box(modifier.background(MaterialTheme.colors.background, RoundedCornerShape(7.dp))) {
        SelectionContainer {
            Text(
                value.take(524288) +
                    (if (value.length > 524288)
                        tr("\n\n[Showing the first 512 KB. Save the file for full output.]")
                    else ""),
                Modifier.fillMaxSize()
                    .padding(14.dp)
                    .verticalScroll(vertical)
                    .horizontalScroll(horizontal),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                softWrap = false,
            )
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(vertical),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
        HorizontalScrollbar(
            rememberScrollbarAdapter(horizontal),
            Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        )
    }
}

fun chooseFile(
    save: Boolean = false,
    name: String = "",
    title: String = if (save) tr("Save file") else tr("Open file"),
    extension: String = "",
): File? {
    // An unowned native chooser remains independently selectable in Windows,
    // including when the Compose window is activated during file selection.
    val dialog = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    try {
        if (name.isNotBlank()) dialog.file = name
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
        val selected = dialog.file ?: return null
        val actual =
            if (save && extension.isNotBlank() && !selected.endsWith(".$extension", true))
                "$selected.$extension"
            else selected
        return File(dialog.directory, actual)
    } finally {
        dialog.dispose()
    }
}

fun copyText(model: AppState, text: String) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        model.status = tr("Copied to clipboard")
    } catch (e: Exception) {
        model.error = e.message ?: tr("Clipboard unavailable")
    }
}

fun openFile(model: AppState, file: File) {
    try {
        file.takeIf { it.exists() } ?: error(tr("File not found: {0}", file))
        if (
            java.awt.Desktop.isDesktopSupported() &&
                java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)
        ) {
            java.awt.Desktop.getDesktop().open(file)
        } else {
            val command =
                when (HostPlatform.current.os) {
                    HostOs.MacOS -> "open"
                    HostOs.Linux -> "xdg-open"
                    HostOs.Windows -> error(tr("Opening files is unavailable in this environment"))
                }
            model.task(tr("Opening file")) {
                Processes.run(listOf(command, file.absolutePath)).requireSuccess()
            }
        }
    } catch (e: Exception) {
        model.error = e.message ?: tr("Cannot open file")
    }
}
