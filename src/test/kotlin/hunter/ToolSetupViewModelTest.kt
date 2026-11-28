package hunter

import hunter.features.settings.Settings
import hunter.features.tools.SetupProgress
import hunter.features.tools.ToolFinding
import hunter.features.tools.ToolPreparation
import hunter.features.tools.ToolSetupViewModel
import hunter.features.tools.ToolStep
import hunter.features.tools.ToolStepState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ToolSetupViewModelTest {
    @Test
    fun `retry runs only failed and skipped components and preserves successful results`() = runTest {
        val batches = mutableListOf<List<String>>()
        val harness = Harness(this, prepare = { config, components, _, _ ->
            batches += components
            val steps = if (batches.size == 1) {
                listOf(
                    ToolStep("python", ToolStepState.Failed, "Python unavailable"),
                    ToolStep("proxy", ToolStepState.Skipped),
                    ToolStep("wireguard", ToolStepState.Prepared),
                )
            } else components.map { ToolStep(it, ToolStepState.Prepared) }
            ToolPreparation(config, steps)
        })
        val viewModel = harness.viewModel
        val components = listOf("python", "proxy", "wireguard")
        viewModel.prepare(components, harness.settings)
        runCurrent()

        assertEquals(listOf("python", "proxy"), viewModel.uiState.value.unfinished(components))
        assertEquals(ToolStepState.Prepared, viewModel.uiState.value.steps["wireguard"]?.state)

        viewModel.retryUnfinished(components, harness.settings)
        runCurrent()
        assertEquals(listOf(components, listOf("python", "proxy")), batches)
        assertEquals(components.associateWith { ToolStepState.Prepared },
            viewModel.uiState.value.steps.mapValues { it.value.state })
        assertNull(viewModel.uiState.value.progress)
    }

    @Test
    fun `progress and log are observable and cancellation cleans up the operation`() = runTest {
        var cleanedUp = false
        val harness = Harness(this, prepare = { _, _, progress, log ->
            progress(SetupProgress("Download", bytes = 512, total = 1024))
            log("Downloading Python")
            try {
                awaitCancellation()
            } finally {
                cleanedUp = true
            }
        })
        val viewModel = harness.viewModel
        viewModel.prepare(listOf("python"), harness.settings)
        runCurrent()

        assertEquals(.5f, assertNotNull(viewModel.uiState.value.progress).fraction)
        assertEquals("Downloading Python\n", viewModel.uiState.value.liveLog)
        viewModel.cancel()
        runCurrent()

        assertTrue(cleanedUp)
        assertNull(viewModel.uiState.value.progress)
        assertEquals(listOf("Downloading Python\n"), harness.outputs)
    }

    @Test
    fun `checking a tool repaired manually clears its retry status`() = runTest {
        var installed: String? = null
        val harness = Harness(
            this,
            prepare = { config, _, _, _ ->
                ToolPreparation(config, listOf(ToolStep("python", ToolStepState.Failed)))
            },
            inspect = { listOf(ToolFinding("python", installed)) },
        )
        val viewModel = harness.viewModel
        viewModel.prepare(listOf("python"), harness.settings)
        runCurrent()
        assertEquals(listOf("python"), viewModel.uiState.value.unfinished(listOf("python")))

        installed = harness.settings.setup.version("python")
        viewModel.checkTools()
        runCurrent()

        assertEquals(installed, viewModel.uiState.value.findings.single().actual)
        assertTrue(viewModel.uiState.value.steps.isEmpty())
    }

    private class Harness(
        scope: CoroutineScope,
        prepare: suspend (Settings, List<String>, (SetupProgress) -> Unit, (String) -> Unit) -> ToolPreparation,
        inspect: suspend (Settings) -> List<ToolFinding> = { emptyList() },
    ) {
        var settings = Settings(tools = "unused")
            private set
        private var operation: Job? = null
        val outputs = mutableListOf<String>()
        val viewModel = ToolSetupViewModel(
            settings = { settings },
            saveSettings = ::save,
            runTask = { _, block -> operation = scope.launch { block() } },
            cancelTask = { operation?.cancel() },
            beforePrepare = {},
            refreshDevices = {},
            report = {},
            showStatus = {},
            showOutput = { _, log -> outputs += log },
            prepareTools = prepare,
            inspectTools = inspect,
        )

        private fun save(config: Settings) {
            settings = config
            viewModel.onSettingsChanged(config)
        }
    }
}
