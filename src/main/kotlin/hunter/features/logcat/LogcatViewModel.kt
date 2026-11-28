package hunter.features.logcat

import hunter.core.AppFiles
import hunter.core.StreamSession
import hunter.core.tr
import hunter.features.android.AndroidApp
import hunter.features.settings.Settings
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LogcatUiState(
    val running: Boolean = false,
    val options: LogOptions = LogOptions(),
    val entries: List<LogEntry> = emptyList(),
    val received: Long = 0,
    val evicted: Long = 0,
    val dropped: Long = 0,
)

class LogcatViewModel internal constructor(
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
    private val serial: () -> String,
    private val selectedApp: () -> AndroidApp?,
    private val runTask: (String, suspend () -> Unit) -> Unit,
    private val report: (String) -> Unit,
    private val showStatus: (String) -> Unit,
    private val createSession: (
        Settings, String, AndroidApp?, LogOptions, CoroutineScope,
        (LogEntry) -> Unit, (String) -> Unit, () -> Unit,
    ) -> StreamSession = ::LogcatSession,
) {
    private val state = MutableStateFlow(LogcatUiState())
    val uiState: StateFlow<LogcatUiState> = state.asStateFlow()
    val running = uiState.map { it.running }.distinctUntilChanged()

    private val incoming = Channel<LogEntry>(4096)
    private val dropped = AtomicLong()
    private val entries = ArrayList<LogEntry>()
    private var bytes = 0L
    private val sessionMutex = Mutex()
    private var session: StreamSession? = null

    init {
        scope.launch {
            while (isActive) {
                delay(100)
                publishBatch()
            }
        }
    }

    fun toggle() = runTask(if (state.value.running) tr("Stopping Logcat") else tr("Starting Logcat")) {
        sessionMutex.withLock {
            if (session != null) {
                stopSession()
                showStatus(tr("Logcat stopped"))
            } else {
                startSession()
                showStatus(tr("Logcat: recording active"))
            }
        }
    }

    private suspend fun startSession() {
        val options = state.value.options
        lateinit var owned: StreamSession
        owned = createSession(
            settings(), serial(), selectedApp().takeIf { options.appOnly }, options, scope,
            { if (incoming.trySend(it).isFailure) dropped.incrementAndGet() },
            report,
            {
                scope.launch {
                    sessionMutex.withLock {
                        if (session === owned) {
                            stopSession()
                            showStatus(tr("Logcat finished. See the journal."))
                        }
                    }
                }
            },
        )
        session = owned
        try {
            owned.start()
            state.update { it.copy(running = true) }
        } catch (e: Exception) {
            stopSession()
            throw e
        }
    }

    internal suspend fun stop() = sessionMutex.withLock { stopSession() }

    private suspend fun stopSession() {
        val owned = session ?: return
        session = null
        try {
            owned.stop()
        } finally {
            state.update { it.copy(running = false) }
        }
    }

    fun setAppOnly(appOnly: Boolean) {
        state.update { it.copy(options = it.options.copy(appOnly = appOnly)) }
    }

    fun setBuffer(buffer: String) {
        state.update { it.copy(options = it.options.copy(buffer = buffer)) }
    }

    fun setHistory(history: Int) {
        state.update { it.copy(options = it.options.copy(history = history)) }
    }

    fun clear() {
        entries.clear()
        bytes = 0
        dropped.set(0)
        while (incoming.tryReceive().isSuccess) {}
        state.update { it.copy(entries = emptyList(), received = 0, evicted = 0, dropped = 0) }
    }

    private fun publishBatch() {
        var received = 0
        for (index in 0 until 1000) {
            val entry = incoming.tryReceive().getOrNull() ?: break
            entries += entry
            bytes += entry.memoryCost()
            received++
        }
        val droppedCount = dropped.get()
        if (received == 0) {
            if (state.value.dropped != droppedCount) state.update { it.copy(dropped = droppedCount) }
            return
        }
        val maxLogs = settings().maxLogs
        var evicted = 0
        while (
            evicted < entries.size &&
            (entries.size - evicted > maxLogs || bytes > 64L * 1024 * 1024)
        ) {
            bytes -= entries[evicted].memoryCost()
            evicted++
        }
        if (evicted > 0) entries.subList(0, evicted).clear()
        val snapshot = Collections.unmodifiableList(ArrayList(entries))
        state.update {
            it.copy(
                entries = snapshot,
                received = it.received + received,
                evicted = it.evicted + evicted,
                dropped = droppedCount,
            )
        }
    }

    fun export(file: File, entries: List<LogEntry>, format: String) = runTask(tr("Export Logcat")) {
        val content = withContext(Dispatchers.Default) { LogExport.write(entries, format) }
        withContext(Dispatchers.IO) { AppFiles.atomicWrite(file, content.toByteArray(Charsets.UTF_8)) }
        showStatus(tr("Exported {0} rows", entries.size))
    }
}

private fun LogEntry.memoryCost() = 256L + 2L * (raw.length + message.length + tag.length)
