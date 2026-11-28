package hunter

import com.google.gson.JsonObject
import hunter.core.StreamSession
import hunter.core.parseObject
import hunter.features.android.AndroidApp
import hunter.features.logcat.LogEntry
import hunter.features.logcat.LogcatViewModel
import hunter.features.settings.Settings
import hunter.features.traffic.CaptureMode
import hunter.features.traffic.Exchange
import hunter.features.traffic.TrafficAppFilter
import hunter.features.traffic.TrafficViewModel
import hunter.features.traffic.applyCaptureEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModelTest {
    @Test
    fun `app scope matches the exact package and known device and UID`() {
        val app = TrafficAppFilter("com.example.app", "device-a", 10123)
        val request = Exchange(packageName = "com.example.app", serial = "device-a", uid = "10123")
        assertTrue(app.matches(request))
        assertTrue(app.matches(request.copy(uid = ""))) // WireGuard records a package from the profile.
        assertFalse(app.matches(request.copy(packageName = "")))
        assertFalse(app.matches(request.copy(packageName = "com.example.app.service")))
        assertFalse(app.matches(request.copy(serial = "device-b")))
        assertFalse(app.matches(request.copy(uid = "1010123")))
    }

    @Test
    fun `capture keeps the started app in scope and system proxy has no app target`() = runTest {
        val settings = Settings(tools = "unused")
        val app = AndroidApp("com.example.app", uid = 10123, user = 0, system = false)
        val sessionTargets = mutableListOf<AndroidApp?>()
        val traffic = TrafficViewModel(
            scope = backgroundScope,
            settings = { settings },
            appName = { "" },
            runTask = { _, block -> launch { block() } },
            report = {},
            showStatus = {},
            showError = {},
            showTraffic = {},
            createSession = { _, _, _, target, _, _, _, _ ->
                sessionTargets += target
                TestSession()
            },
        )

        traffic.start(settings, "device", CaptureMode.Vpn, app)
        assertEquals(app, traffic.uiState.value.capture?.app)
        assertEquals("device", traffic.uiState.value.capture?.serial)
        traffic.stop()
        assertNull(traffic.uiState.value.capture)

        traffic.start(settings, "device", CaptureMode.Proxy, app)
        assertEquals(CaptureMode.Proxy, traffic.uiState.value.capture?.mode)
        assertNull(traffic.uiState.value.capture?.app)
        assertEquals(listOf(app, null), sessionTargets)
        traffic.stop()
    }

    @Test
    fun `diagnostics keep app identity for package and UID filters`() {
        val event = parseObject("""{"kind":"diagnostic","packageName":"com.example.app","uid":"10123","appId":"10123"}""")
        val exchange = requireNotNull(applyCaptureEvent(null, event, "Test app"))
        assertTrue(exchange.matches("", "Test app", "10123"))
        assertTrue(TrafficAppFilter("com.example.app", null, 10123).matches(exchange))
        assertFalse(TrafficAppFilter("com.example.app", null, 10124).matches(exchange))
    }

    @Test
    fun `capture keeps receiving and updating requests while its screen is unobserved`() = runTest {
        val settings = Settings(tools = "unused", maxRequests = 2)
        val session = TestSession()
        lateinit var receive: suspend (JsonObject) -> Unit
        val traffic = TrafficViewModel(
            scope = backgroundScope,
            settings = { settings },
            appName = { "" },
            runTask = { _, block -> launch { block() } },
            report = {},
            showStatus = {},
            showError = {},
            showTraffic = {},
            createSession = { _, _, _, _, _, onEvent, _, _ ->
                receive = onEvent
                session
            },
        )

        val trafficTab = backgroundScope.launch { traffic.uiState.collect {} }
        traffic.start(settings, "device", CaptureMode.Proxy, null)
        receive(request("one"))
        advanceTimeBy(101)
        val firstSnapshot = traffic.uiState.value.exchanges
        assertEquals(listOf("one"), firstSnapshot.map { it.id })

        trafficTab.cancelAndJoin()
        receive(request("two"))
        receive(request("three"))
        advanceTimeBy(101)
        receive(parseObject("""{"kind":"response","id":"two","status":201,"complete":true}"""))
        advanceTimeBy(101)

        val restoredTab = traffic.uiState.first()
        assertTrue(restoredTab.running)
        assertEquals(listOf("two", "three"), restoredTab.exchanges.map { it.id })
        assertEquals(201, restoredTab.exchanges.first().status)
        assertEquals(1L, restoredTab.evictedRequests)
        assertEquals(listOf("one"), firstSnapshot.map { it.id })
        assertEquals(0, session.stops)

        traffic.stop()
        assertFalse(traffic.uiState.value.running)
        assertEquals(1, session.stops)
    }

    @Test
    fun `logcat keeps recording between subscribers and cleans up when the process exits`() = runTest {
        val session = TestSession()
        lateinit var receive: (LogEntry) -> Unit
        lateinit var ended: () -> Unit
        val logcat = LogcatViewModel(
            scope = backgroundScope,
            settings = { Settings(tools = "unused", maxLogs = 2) },
            serial = { "device" },
            selectedApp = { null },
            runTask = { _, block -> launch { block() } },
            report = {},
            showStatus = {},
            createSession = { _, _, _, options, _, onEntry, _, onEnded ->
                assertEquals("main", options.buffer)
                receive = onEntry
                ended = onEnded
                session
            },
        )

        logcat.setBuffer("main")
        val logcatTab = backgroundScope.launch { logcat.uiState.collect {} }
        logcat.toggle()
        runCurrent()
        receive(entry("one"))
        advanceTimeBy(101)
        val firstSnapshot = logcat.uiState.value.entries

        logcatTab.cancelAndJoin()
        receive(entry("two"))
        receive(entry("three"))
        advanceTimeBy(101)

        val restoredTab = logcat.uiState.first()
        assertTrue(restoredTab.running)
        assertEquals(listOf("two", "three"), restoredTab.entries.map { it.id })
        assertEquals(3L, restoredTab.received)
        assertEquals(1L, restoredTab.evicted)
        assertEquals(listOf("one"), firstSnapshot.map { it.id })
        assertEquals(0, session.stops)

        ended()
        runCurrent()
        assertFalse(logcat.uiState.value.running)
        assertEquals(1, session.stops)
    }

    private fun request(id: String) =
        parseObject("""{"kind":"request","id":"$id","method":"GET","url":"https://example.test/$id"}""")

    private fun entry(id: String) = LogEntry(
        time = "", pid = "", tid = "", uid = "", level = "I", tag = "Test",
        message = id, raw = id, serial = "device", id = id,
    )

    private class TestSession : StreamSession {
        var stops = 0
            private set

        override suspend fun start() = Unit

        override suspend fun stop() {
            stops++
        }
    }
}
