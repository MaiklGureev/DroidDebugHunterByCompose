package hunter.features.tools

import hunter.core.tr
import hunter.features.settings.Settings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class ToolComponentStatus(val label: String, val ready: Boolean = false) {
    Unchecked("Not checked"),
    Missing("Not found"),
    Failed("Check required"),
    DifferentVersion("Different version", ready = true),
    Ready("Ready", ready = true),
}

data class ToolSetupUiState(
    val directoryWarnings: List<String> = emptyList(),
    val findings: List<ToolFinding> = emptyList(),
    val steps: Map<String, ToolStep> = emptyMap(),
    val progress: SetupProgress? = null,
    val liveLog: String = "",
    val lastDownloadedFile: File? = null,
) {
    fun componentStatus(id: String, abi: String? = null, nativeCapture: Boolean = false): ToolComponentStatus {
        val ids = if (id == "capture" && nativeCapture) listOf("frida") else toolFindingIds(id)
        val detected = ids.map { name -> findings.firstOrNull { it.id == name } }
        return when {
            detected.all { it == null } -> ToolComponentStatus.Unchecked
            detected.any { it?.failed == true } -> ToolComponentStatus.Failed
            nativeCapture && detected.any { it?.nativeDecoderError != null } -> ToolComponentStatus.Failed
            detected.any { it == null || it.actual.isNullOrBlank() || (abi != null && abi !in it.abis) } ->
                ToolComponentStatus.Missing
            detected.any { it?.warning == true } -> ToolComponentStatus.DifferentVersion
            else -> ToolComponentStatus.Ready
        }
    }

    fun unfinished(components: List<String>): List<String> = components.filter { component ->
        steps[component]?.let { it.state != ToolStepState.Prepared } == true
    }
}

internal fun needsToolPreparation(
    id: String,
    status: ToolComponentStatus,
    availableOnAndroid: Boolean,
    saved: Settings,
    draft: Settings,
): Boolean =
    draft.tools != saved.tools ||
        toolFindingIds(id).map(::toolVersionId).any { draft.setup.version(it) != saved.setup.version(it) } ||
        (id == "adb" && (draft.adb != saved.adb || draft.setup.adbArchive != saved.setup.adbArchive)) ||
        (id == "python" && (draft.python != saved.python || draft.setup.method != saved.setup.method)) ||
        (!availableOnAndroid && !status.ready)

class ToolSetupViewModel internal constructor(
    private val settings: () -> Settings,
    private val saveSettings: (Settings) -> Unit,
    private val runTask: (String, suspend () -> Unit) -> Unit,
    private val cancelTask: () -> Unit,
    private val beforePrepare: () -> Unit,
    private val refreshDevices: suspend () -> Unit,
    private val report: (String) -> Unit,
    private val showStatus: (String) -> Unit,
    private val showOutput: (String, String) -> Unit,
    private val prepareTools: suspend (
        Settings, List<String>, (SetupProgress) -> Unit, (String) -> Unit,
    ) -> ToolPreparation = ToolProvisioner::prepare,
    private val inspectTools: suspend (Settings) -> List<ToolFinding> = ToolInspector::inspect,
) {
    private val state = MutableStateFlow(ToolSetupUiState())
    val uiState: StateFlow<ToolSetupUiState> = state.asStateFlow()
    private var configuration = settings().toolConfiguration()

    internal fun onSettingsChanged(config: Settings) {
        val current = config.toolConfiguration()
        if (configuration == current) return
        configuration = current
        state.update { it.copy(findings = emptyList(), steps = emptyMap()) }
    }

    fun save(draft: Settings) {
        saveSettings(
            settings().copy(
                adb = draft.adb.trim(),
                python = draft.python.trim(),
                tools = draft.tools.trim(),
                setup = draft.setup,
            ),
        )
    }

    internal suspend fun initializeFolders() {
        val directory = settings().tools
        val warnings = withContext(Dispatchers.IO) { ToolLayout.ensure(File(directory)) }
        if (settings().tools == directory) state.update { it.copy(directoryWarnings = warnings) }
        warnings.forEach(report)
    }

    fun checkFolders(draft: Settings) = runTask(tr("Checking tool folders")) {
        save(draft)
        initializeFolders()
        showStatus(
            if (state.value.directoryWarnings.isEmpty()) tr("Tool folders are ready")
            else tr("Some folders could not be created; choose a writable location"),
        )
    }

    fun download(config: Settings, source: ToolSource) = runTask(tr("Downloading")) {
        updateProgress(SetupProgress(tr("Downloading"), source.label))
        try {
            val file = ToolLayout.download(config, source, ::updateProgress)
            state.update { it.copy(lastDownloadedFile = file) }
            val message = tr("Downloaded: {0}", file.absolutePath)
            showStatus(message)
            report(message)
        } finally {
            updateProgress(null)
        }
    }

    fun cancel() {
        if (state.value.progress != null) cancelTask()
    }

    suspend fun refreshIfNeeded() {
        if (state.value.findings.isEmpty()) refreshFindings(settings())
    }

    internal suspend fun refreshFindings(config: Settings = settings()) {
        val key = config.toolConfiguration()
        val findings = inspectTools(config)
        if (configuration == key) state.update { it.copy(findings = findings) }
    }

    fun checkTools(refreshAdb: Boolean = false, draft: Settings? = null) = runTask(tr("Checking tools")) {
        draft?.let(::save)
        val config = settings()
        refreshFindings(config)
        if (configuration == config.toolConfiguration()) {
            state.update { snapshot ->
                val detected = snapshot.findings.associateBy { it.id }
                val unresolved = snapshot.steps.filterNot { (component, step) ->
                    step.state != ToolStepState.Prepared && toolFindingIds(component).all { id ->
                        val finding = detected[id]
                        finding != null && !finding.failed && finding.actual != null &&
                            (finding.actual == config.setup.version(finding.versionId) ||
                                (finding.id == "adb" && config.setup.adbLatest))
                    }
                }
                snapshot.copy(steps = unresolved)
            }
        }
        if (refreshAdb) refreshDevices()
        showStatus(tr("Check completed"))
    }

    fun retryUnfinished(components: List<String>, draft: Settings) =
        prepare(state.value.unfinished(components), draft)

    fun prepare(components: List<String>, draft: Settings) {
        if (components.isEmpty()) return
        runTask(tr("Preparing tools")) {
            beforePrepare()
            save(draft)
            val previousSteps = state.value.steps
            state.update { it.copy(progress = SetupProgress(tr("Preparing tools")), liveLog = "") }
            val log = StringBuilder()
            try {
                val preparation = prepareTools(settings(), components, ::updateProgress) { line ->
                    report(line)
                    synchronized(log) { if (log.length < 200_000) log.appendLine(line) }
                    state.update { it.copy(liveLog = (it.liveLog + line + "\n").takeLast(8000)) }
                }
                val config = preparation.config
                saveSettings(settings().copy(tools = config.tools, adb = config.adb, python = config.python))
                val steps = preparation.steps.associateBy { it.component }
                state.update { it.copy(steps = previousSteps + steps) }
                refreshFindings(settings())
                if (steps["adb"]?.state == ToolStepState.Prepared) refreshDevices()
                val unfinished = steps.values.count { it.state != ToolStepState.Prepared }
                showStatus(
                    if (unfinished == 0) tr("Tools: operation completed")
                    else tr("Setup finished with {0} unfinished components", unfinished),
                )
            } catch (e: CancellationException) {
                showOutput(tr("Operation cancelled"), synchronized(log) { log.toString() })
                throw e
            } catch (e: Exception) {
                showOutput(tr("Tool setup failed"), synchronized(log) { log.toString() } + "\n" + e.message)
                throw e
            } finally {
                updateProgress(null)
            }
        }
    }

    private fun updateProgress(progress: SetupProgress?) {
        state.update { it.copy(progress = progress) }
    }
}
