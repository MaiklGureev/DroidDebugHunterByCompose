package hunter.ui

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import hunter.core.HostOs
import hunter.core.HostPlatform

val KeyEvent.isPlatformShortcutPressed: Boolean
    get() = isCtrlPressed || (HostPlatform.current.os == HostOs.MacOS && isMetaPressed)
