package hunter

import hunter.features.settings.Settings
import hunter.features.tools.ToolComponentStatus
import hunter.features.tools.ToolFinding
import hunter.features.tools.ToolPolicy
import hunter.features.tools.ToolSetupUiState
import hunter.features.tools.needsToolPreparation
import hunter.features.traffic.CaptureMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureSetupTest {
    @Test
    fun `only the frida server route requires root`() {
        assertEquals(listOf(CaptureMode.OkHttp, CaptureMode.Native), CaptureMode.entries.filter { it.requiresRoot(false) })
        assertTrue(CaptureMode.entries.none { it.requiresRoot(true) })
    }

    @Test
    fun `working tools hide preparation and an explicit version edit enables it`() {
        val saved = Settings(tools = "tools")
        val tools = ToolSetupUiState(findings = listOf(ToolFinding("python", "3.12.0")))
        val status = tools.componentStatus("python")
        assertEquals(ToolComponentStatus.DifferentVersion, status)
        assertFalse(needsToolPreparation("python", status, false, saved, saved))

        val draft = saved.copy(setup = saved.setup.copy(versions = saved.setup.versions + ("python" to "3.13.0")))
        assertTrue(needsToolPreparation("python", status, false, saved, draft))
        assertTrue(needsToolPreparation("python", ToolComponentStatus.Failed, false, saved, saved))
        assertFalse(needsToolPreparation("wireguard", ToolComponentStatus.Missing, true, saved, saved))
    }

    @Test
    fun `Frida tool requirements distinguish the target ABI and the native decoder`() {
        val frida = ToolFinding("frida", ToolPolicy.target("frida"))
        val server = ToolFinding("server", frida.actual, abis = setOf("arm64"))
        val tools = ToolSetupUiState(findings = listOf(frida, server))
        assertEquals(ToolComponentStatus.Ready, tools.componentStatus("server", "arm64"))
        assertEquals(ToolComponentStatus.Missing, tools.componentStatus("server", "x86_64"))
        assertEquals(ToolComponentStatus.Missing, tools.componentStatus("capture"))
        assertEquals(ToolComponentStatus.Ready, tools.componentStatus("capture", nativeCapture = true))

        val brokenDecoder = tools.copy(findings = listOf(frida.copy(nativeDecoderError = "hpack unavailable")))
        assertEquals(ToolComponentStatus.Failed, brokenDecoder.componentStatus("capture", nativeCapture = true))
    }
}
