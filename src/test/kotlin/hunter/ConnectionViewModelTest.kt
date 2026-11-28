package hunter

import hunter.features.android.AndroidConnection
import hunter.features.android.Device
import hunter.features.connection.ConnectionViewModel
import hunter.features.connection.FridaDiagnosis
import hunter.features.connection.FridaNext
import hunter.features.settings.Settings
import hunter.features.traffic.CaptureCheck
import hunter.features.traffic.CaptureMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    @Test
    fun `refresh preserves transport types and stops streams when the selected transport drops`() = runTest {
        val usb = Device("usb-123", "device")
        val emulator = Device("emulator-5554", "device")
        val wifi = Device("192.0.2.4:37123", "device")
        val harness = Harness(this)
        harness.devices = listOf(usb, emulator, wifi)
        val connection = harness.connection

        connection.refreshDevices()
        assertEquals(
            listOf(AndroidConnection.Usb, AndroidConnection.Emulator, AndroidConnection.Wifi),
            connection.uiState.value.devices.map { it.connection },
        )
        assertEquals(usb, connection.uiState.value.selectedDevice)

        connection.selectDevice(emulator)
        runCurrent()
        assertEquals(emulator, connection.uiState.value.selectedDevice)
        assertEquals(emulator.serial, harness.settings.lastSerial)

        val stopsBeforeRefresh = harness.streamStops
        connection.refreshDevices()
        assertEquals(stopsBeforeRefresh, harness.streamStops)
        harness.devices = listOf(usb, emulator.copy(state = "offline"), wifi)
        connection.refreshDevices()
        assertEquals(stopsBeforeRefresh + 1, harness.streamStops)
        assertEquals("offline", connection.uiState.value.selectedDevice?.state)
        assertFalse(connection.uiState.value.refreshing)
    }

    @Test
    fun `pairing and connecting use separate endpoints and only connecting selects the device`() = runTest {
        val pairing = "192.0.2.4:35231"
        val endpoint = "192.0.2.4:37123"
        val usb = Device("usb-123", "device")
        val wifi = Device(endpoint, "device")
        val requests = mutableListOf<Pair<String, String>>()
        val harness = Harness(
            this,
            wireless = { _, address, code ->
                requests += address to code
                "OK"
            },
            discover = { listOf(endpoint) },
        )
        harness.devices = listOf(usb)
        val connection = harness.connection
        connection.refreshDevices()
        var paired = false
        connection.connect(" $pairing ", "123456") { paired = true }
        runCurrent()
        assertTrue(paired)
        assertEquals(usb, connection.uiState.value.selectedDevice)

        connection.discoverWireless()
        runCurrent()
        assertEquals(listOf(endpoint), connection.uiState.value.wirelessAddresses)

        harness.devices = listOf(usb, wifi)
        connection.connect(endpoint)
        runCurrent()
        assertEquals(listOf(pairing to "123456", endpoint to ""), requests)
        assertEquals(wifi, connection.uiState.value.selectedDevice)
        assertEquals(endpoint, harness.settings.lastSerial)
    }

    @Test
    fun `Frida publishes its next step only for the device and mode that were checked`() = runTest {
        val device = Device("usb-123", "device")
        val result = FridaDiagnosis(listOf(CaptureCheck("ABI", true, "arm64")), FridaNext.Start)
        val probe = CompletableDeferred<FridaDiagnosis>()
        val harness = Harness(this, diagnose = { current, mode ->
            assertEquals(device, current)
            assertEquals(CaptureMode.OkHttp, mode)
            probe.await()
        })
        harness.devices = listOf(device)
        val connection = harness.connection
        connection.refreshDevices()
        connection.setMode(CaptureMode.OkHttp)
        connection.checkFrida()
        runCurrent()

        harness.devices = emptyList()
        connection.refreshDevices()
        probe.complete(result)
        runCurrent()
        assertNull(connection.uiState.value.selectedDevice)
        assertNull(connection.uiState.value.fridaDiagnosis)

        harness.devices = listOf(device)
        connection.refreshDevices()
        connection.checkFrida()
        runCurrent()
        assertEquals(result, connection.uiState.value.fridaDiagnosis)
        connection.setMode(CaptureMode.Proxy)
        assertNull(connection.uiState.value.fridaDiagnosis)
    }

    private class Harness(
        scope: CoroutineScope,
        wireless: suspend (Settings, String, String) -> String = { _, _, _ -> error("Unexpected Wi-Fi request") },
        discover: suspend (Settings) -> List<String> = { emptyList() },
        diagnose: suspend (Device?, CaptureMode?) -> FridaDiagnosis = { _, _ -> error("Unexpected Frida probe") },
    ) {
        var settings = Settings(tools = "unused")
            private set
        var devices = emptyList<Device>()
        var streamStops = 0
            private set

        val connection = ConnectionViewModel(
            settings = { settings },
            saveSettings = { settings = it },
            runTask = { _, block -> scope.launch { block() } },
            stopStreams = { streamStops++ },
            stopCapture = {},
            isCapturing = { false },
            confirm = { _, _, _ -> error("Unexpected confirmation") },
            report = {},
            showStatus = {},
            showError = { error(it) },
            showOutput = { _, _ -> },
            listDevices = { devices },
            connectWireless = wireless,
            discoverAddresses = discover,
            probeFrida = { _, device, _, mode, _ -> diagnose(device, mode) },
        )
    }
}
