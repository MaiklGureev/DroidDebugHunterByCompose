package hunter.features.traffic

import com.google.gson.JsonObject
import hunter.core.AppFiles
import hunter.core.StreamSession
import hunter.core.text
import hunter.core.tr
import hunter.features.android.AndroidApp
import hunter.features.settings.Settings
import java.io.File
import java.util.Collections
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

data class TrafficCaptureTarget(val mode: CaptureMode, val serial: String, val app: AndroidApp?)

data class TrafficUiState(
    val capture: TrafficCaptureTarget? = null,
    val exchanges: List<Exchange> = emptyList(),
    val evictedRequests: Long = 0,
    val detailIds: List<String> = emptyList(),
) {
    val running: Boolean get() = capture != null
}

class TrafficViewModel internal constructor(
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
    private val appName: (String) -> String,
    private val runTask: (String, suspend () -> Unit) -> Unit,
    private val report: (String) -> Unit,
    private val showStatus: (String) -> Unit,
    private val showError: (String) -> Unit,
    private val showTraffic: () -> Unit,
    private val createSession: (
        Settings, String, CaptureMode, AndroidApp?, CoroutineScope,
        suspend (JsonObject) -> Unit, (String) -> Unit, () -> Unit,
    ) -> StreamSession = ::CaptureSession,
) {
    private val state = MutableStateFlow(TrafficUiState())
    val uiState: StateFlow<TrafficUiState> = state.asStateFlow()
    val running = uiState.map { it.running }.distinctUntilChanged()
    val detailIds = uiState.map { it.detailIds }.distinctUntilChanged()

    private val events = Channel<JsonObject>(512)
    private val exchanges = ArrayList<Exchange>()
    private val byId = HashMap<String, Int>()
    private var bytes = 0L
    private val sessionMutex = Mutex()
    private var session: StreamSession? = null

    init {
        scope.launch {
            while (isActive) {
                delay(100)
                var changed = false
                for (index in 0 until 512) {
                    val event = events.tryReceive().getOrNull() ?: break
                    if (accept(event)) changed = true
                }
                if (changed) publishExchanges()
            }
        }
    }

    internal suspend fun start(config: Settings, serial: String, mode: CaptureMode, app: AndroidApp?) {
        sessionMutex.withLock {
            check(session == null)
            val targetApp = if (mode.capturesSingleApp) requireNotNull(app) { tr("Select an app") } else null
            lateinit var owned: StreamSession
            owned = createSession(config, serial, mode, targetApp, scope, events::send, report) {
                scope.launch {
                    sessionMutex.withLock {
                        if (session === owned) {
                            stopSession()
                            showError(tr("The capture engine exited. See the journal for details."))
                        }
                    }
                }
            }
            session = owned
            try {
                owned.start()
                state.update { it.copy(capture = TrafficCaptureTarget(mode, serial, targetApp)) }
            } catch (e: Exception) {
                stopSession()
                throw e
            }
        }
    }

    internal suspend fun stop() = sessionMutex.withLock { stopSession() }

    private suspend fun stopSession() {
        val owned = session ?: return
        session = null
        try {
            owned.stop()
        } finally {
            state.update { it.copy(capture = null) }
        }
    }

    fun openDetails(id: String) {
        state.update { if (id in it.detailIds) it else it.copy(detailIds = it.detailIds + id) }
    }

    fun closeDetails(id: String) {
        state.update { it.copy(detailIds = it.detailIds - id) }
    }

    fun exchange(id: String) = uiState.map { snapshot ->
        snapshot.exchanges.firstOrNull { it.id == id }
    }.distinctUntilChanged()

    fun clear() {
        exchanges.clear()
        byId.clear()
        bytes = 0
        state.update { it.copy(exchanges = emptyList(), evictedRequests = 0) }
    }

    private fun accept(event: JsonObject): Boolean {
        when (event.text("kind")) {
            "ready", "unsupported", "status", "identity" -> {
                val message = event.text("message")
                if (message.isNotBlank()) report(message)
                return false
            }
        }
        val index = byId[event.text("id")]
        val previous = index?.let { exchanges.getOrNull(it) }
        val exchange = applyCaptureEvent(previous, event, appName(event.text("packageName")))
            ?: return false
        if (index != null && previous != null) {
            exchanges[index] = exchange
            bytes += exchange.memoryCost() - previous.memoryCost()
        } else {
            byId[exchange.id] = exchanges.size
            exchanges += exchange
            bytes += exchange.memoryCost()
        }
        return true
    }

    private fun publishExchanges() {
        val maxRequests = settings().maxRequests
        var evicted = 0
        while (
            evicted < exchanges.size &&
            (exchanges.size - evicted > maxRequests || bytes > 96L * 1024 * 1024)
        ) {
            bytes -= exchanges[evicted].memoryCost()
            evicted++
        }
        if (evicted > 0) {
            exchanges.subList(0, evicted).clear()
            byId.clear()
            exchanges.forEachIndexed { index, exchange -> byId[exchange.id] = index }
        }
        val snapshot = Collections.unmodifiableList(ArrayList(exchanges))
        state.update { it.copy(exchanges = snapshot, evictedRequests = it.evictedRequests + evicted) }
    }

    fun importHar(file: File) = runTask(tr("Import HAR")) {
        val content = withContext(Dispatchers.IO) {
            require(file.length() <= 64 * 1024 * 1024) { tr("HAR exceeds 64 MB") }
            file.readText(Charsets.UTF_8)
        }
        val imported = withContext(Dispatchers.Default) { Har.read(content) }
        for (exchange in imported) {
            byId[exchange.id] = exchanges.size
            exchanges += exchange
            bytes += exchange.memoryCost()
        }
        publishExchanges()
        showTraffic()
        showStatus(tr("Imported {0} requests · {1}", imported.size, file.name))
    }

    fun saveHar(file: File, snapshot: List<Exchange>) = runTask(tr("Export HAR")) {
        val content = withContext(Dispatchers.Default) { Har.write(snapshot) }
        withContext(Dispatchers.IO) { AppFiles.atomicWrite(file, content.toByteArray()) }
        showStatus(tr("Saved {0} HTTP requests", snapshot.count { !it.raw }))
    }

    fun saveBody(file: File, body: Body) = runTask(tr("Saving body")) {
        withContext(Dispatchers.IO) { AppFiles.atomicWrite(file, body.bytes()) }
        showStatus(tr("Body saved: {0}", file.name))
    }
}

private fun Exchange.memoryCost() =
    512L +
        2L *
        (url.length +
            request.data.length +
            response.data.length +
            diagnostic.length +
            (diagnosticBody?.data?.length ?: 0) +
            requestHeaders.sumOf { it.name.length + it.value.length } +
            responseHeaders.sumOf { it.name.length + it.value.length })
