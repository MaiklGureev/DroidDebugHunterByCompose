package hunter

import hunter.core.array
import hunter.core.obj
import hunter.core.parseObject
import hunter.core.text
import hunter.features.android.Adb
import hunter.features.android.AndroidConnection
import hunter.features.logcat.LogEntry
import hunter.features.logcat.LogExport
import hunter.features.logcat.LogFilter
import hunter.features.logcat.LogParser
import hunter.features.traffic.Body
import hunter.features.traffic.Curl
import hunter.features.traffic.CurlShell
import hunter.features.traffic.Exchange
import hunter.features.traffic.Har
import hunter.features.traffic.Header
import hunter.features.traffic.applyCaptureEvent
import java.util.Base64
import kotlin.test.*

class CoreTest {
    private fun powerShellCurl(e: Exchange) = Curl.create(e, CurlShell.PowerShell)

    private fun har(
        request: String =
            """{"method":"GET","url":"https://example.test/путь?q=a","headers":[],"bodySize":0}""",
        response: String =
            """{"status":200,"headers":[],"content":{"text":"готово","mimeType":"text/plain"}}""",
    ) =
        """{"log":{"version":"1.2","entries":[{"startedDateTime":"2026-11-26T10:00:00Z","request":$request,"response":$response,"time":12.5}]}}"""

    @Test
    fun `HAR parses unicode and response MIME`() {
        val e = Har.read(har()).single()
        assertEquals("готово", e.response.view("Text"))
        assertEquals("text/plain", e.responseHeaders.single().value)
        assertEquals(12.5, e.duration)
        assertTrue(e.url.contains("путь"))
        assertFalse(e.hasBody)
    }

    @Test
    fun `HAR roundtrip preserves duplicate headers and binary bytes`() {
        val bytes = byteArrayOf(0, 1, 13, 10, -1, -128)
        val e =
            Exchange(
                method = "POST",
                url = "https://example.test/api",
                requestHeaders = listOf(Header("X-A", "a"), Header("X-A", "b")),
                request = Body(Base64.getEncoder().encodeToString(bytes), "base64"),
                hasBody = true,
                response = Body("ok"),
                status = 201,
                packageName = "com.test",
                uid = "1100123",
                appId = "100123",
            )
        val round = Har.read(Har.write(listOf(e))).single()
        assertContentEquals(bytes, round.request.bytes())
        assertEquals(e.requestHeaders, round.requestHeaders)
        assertEquals(e.uid, round.uid)
        assertEquals(201, round.status)
    }

    @Test
    fun `HAR flags missing request body instead of fabricating cURL`() {
        val e =
            Har.read(
                    har(
                        request =
                            """{"method":"POST","url":"https://example.test","headers":[{"name":"Content-Length","value":"100"}]}"""
                    )
                )
                .single()
        assertTrue(e.hasBody)
        assertFalse(e.request.complete)
        assertFailsWith<IllegalArgumentException> { powerShellCurl(e) }
    }

    @Test
    fun `HAR params do not imply exact captured bytes`() {
        val e =
            Har.read(
                    har(
                        request =
                            """{"method":"POST","url":"https://example.test","headers":[],"postData":{"mimeType":"application/x-www-form-urlencoded","params":[{"name":"a","value":"b"}]}}"""
                    )
                )
                .single()
        assertFalse(e.request.complete)
    }

    @Test
    fun `partial bodies survive export import`() {
        val e =
            Exchange(
                url = "https://example.test",
                request = Body("part", complete = false),
                hasBody = true,
                response = Body("reply", complete = false),
                status = 200,
            )
        val r = Har.read(Har.write(listOf(e))).single()
        assertFalse(r.request.complete)
        assertFalse(r.response.complete)
        assertEquals("part", r.request.data)
    }

    @Test
    fun `GET export has no artificial postData`() {
        val root = parseObject(Har.write(listOf(Exchange(url = "https://example.test"))))
        assertFalse(root.obj("log").array("entries")[0].asJsonObject.obj("request").has("postData"))
    }

    @Test
    fun `RAW events excluded from HAR`() {
        assertTrue(Har.read(Har.write(listOf(Exchange(raw = true)))).isEmpty())
    }

    @Test
    fun `HAR rejects malformed documents`() {
        assertFails { Har.read("{}") }
        assertFails { Har.read("""{"log":{"entries":[{}]}}""") }
    }

    @Test
    fun `missing response text is not complete`() {
        assertFalse(
            Har.read(har(response = """{"status":200,"content":{"size":100}}"""))
                .single()
                .response
                .complete
        )
    }

    @Test
    fun `cURL quotes literal PowerShell arguments`() {
        val e =
            Exchange(
                url = "https://example.test/a'b",
                requestHeaders =
                    listOf(
                        Header("X-Value", "don't expand `\$x"),
                        Header("Content-Length", "99"),
                        Header("Connection", "close"),
                    ),
            )
        val command = powerShellCurl(e)
        assertTrue(command.contains("a''b"))
        assertTrue(command.contains("don''t expand `\$x"))
        assertFalse(command.contains("--header 'Content-Length"))
        assertFalse(command.contains("--header 'Connection"))
    }

    @Test
    fun `cURL uses exact bytes through temporary file`() {
        val body = "тест\r\n'\"`$()"
        val command =
            powerShellCurl(
                Exchange(
                    method = "POST",
                    url = "https://example.test",
                    request = Body(body),
                    hasBody = true,
                )
            )
        assertTrue(command.contains(Base64.getEncoder().encodeToString(body.toByteArray())))
        assertTrue(command.contains("--data-binary"))
        assertTrue(command.contains("finally"))
    }

    @Test
    fun `cURL rejects header injection and RAW`() {
        assertFails {
            powerShellCurl(
                Exchange(
                    url = "https://example.test",
                    requestHeaders = listOf(Header("X-A", "a\r\nX-B: c")),
                )
            )
        }
        assertFails { powerShellCurl(Exchange(url = "tls://example.test", raw = true)) }
        assertFails { Curl.quote("a\u0000b") }
    }

    @Test
    fun `body hex and base64 preserve binary`() {
        val b = Body("AAH/gA==", "base64")
        assertContentEquals(byteArrayOf(0, 1, -1, -128), b.bytes())
        assertTrue(b.view("HEX").contains("00 01 FF 80"))
        assertEquals("AAH/gA==", b.view("Base64"))
    }

    @Test
    fun `body JSON pretty print and HTML inert text`() {
        assertTrue(Body("{\"a\":1}").view("JSON").contains("\n"))
        assertEquals(" hi ", Body("<script>evil()</script><p>hi</p>").view("HTML-текст"))
    }

    @Test
    fun `device parser keeps unauthorized and offline`() {
        val devices =
            Adb.parseDevices(
                "List of devices attached\nusb123 device product:a model:Pixel_8 transport_id:1\n192.168.1.1:1234 unauthorized\nemulator-5554 offline\n"
            )
        assertEquals(3, devices.size)
        assertEquals("Pixel 8", devices[0].model)
        assertEquals("unauthorized", devices[1].state)
        assertEquals(
            listOf(AndroidConnection.Usb, AndroidConnection.Wifi, AndroidConnection.Emulator),
            devices.map { it.connection },
        )
        assertEquals(
            AndroidConnection.Wifi,
            AndroidConnection.forSerial("adb-example._adb-tls-connect._tcp"),
        )
    }

    @Test
    fun `Wi-Fi discovery accepts only connection addresses with valid ports`() {
        val services = """
            List of discovered mdns services
            adb-example _adb-tls-pairing._tcp. 192.168.1.10:48901
            adb-example _adb-tls-connect._tcp. 192.168.1.10:37151
            adb-duplicate _adb-tls-connect._tcp 192.168.1.10:37151
            adb-ipv6 _adb-tls-connect._tcp. [2001:db8::1]:37152
            adb-invalid _adb-tls-connect._tcp 192.168.1.11:65536
            legacy _adb._tcp. 192.168.1.11:5555
        """.trimIndent()
        assertEquals(listOf("192.168.1.10:37151", "[2001:db8::1]:37152"), Adb.parseWirelessServices(services))
        assertFalse(Adb.validAddress("192.168.1.10:0"))
        assertFalse(Adb.validAddress("192.168.1.10:37151 --other"))
    }

    @Test
    fun `application UID and Android profile are distinct`() {
        val apps =
            Adb.parseApplications(
                "package:com.system uid:1001000\npackage:com.test uid:1010123\n",
                10,
                setOf("com.system"),
                mapOf("com.test" to "Test"),
            )
        assertEquals("com.test", apps.first().packageName)
        assertEquals(10, apps.first().user)
        assertEquals("10123", apps.first().appId)
        assertTrue(apps.first().matches("1010123"))
        assertTrue(apps.last().system)
    }

    @Test
    fun `permissions use only requested dangerous permissions of selected user`() {
        val dump =
            """
            requested permissions:
              android.permission.CAMERA
              android.permission.INTERNET
              android.permission.RECORD_AUDIO
            install permissions:
              android.permission.INTERNET: granted=true
            User 0: installed=true
              android.permission.CAMERA: granted=true
            User 10: installed=true
              android.permission.CAMERA: granted=false
              android.permission.RECORD_AUDIO: granted=true
        """
                .trimIndent()
        val result =
            Adb.parsePermissions(
                dump,
                "permission:android.permission.CAMERA\npermission:android.permission.RECORD_AUDIO\npermission:android.permission.ACCESS_FINE_LOCATION",
                10,
            )
        assertEquals(2, result.size)
        assertEquals(false, result[0].granted)
        assertEquals(true, result[1].granted)
    }

    @Test
    fun `shell quote protects package and names are validated`() {
        assertEquals("'a'\\''b'", Adb.quote("a'b"))
        assertFails { Adb.validateName("com.test; reboot") }
        assertEquals("arm64", Adb.arch("arm64-v8a"))
    }

    @Test
    fun `logcat parses numeric UID and threadtime without UID`() {
        val a =
            LogParser.parse(
                "2026-11-26 12:34:56.789 10123 123 456 E My Tag: first: second",
                "device",
            )!!
        assertEquals("10123", a.uid)
        assertEquals("123", a.pid)
        assertEquals("456", a.tid)
        assertEquals("My Tag", a.tag)
        assertEquals("first: second", a.message)
        val b =
            LogParser.parse(
                "11-26 12:34:56.789 123 456 D Tag: text",
                "device",
                forcedUid = "10123",
            )!!
        assertEquals("10123", b.uid)
        assertEquals("123", b.pid)
    }

    @Test
    fun `logcat resolves symbolic Android UIDs`() {
        assertEquals(1010123, LogParser.uidNumber("u10_a123"))
        assertEquals(1000, LogParser.uidNumber("system"))
        assertEquals(99012, LogParser.uidNumber("u0_i12"))
    }

    private fun log() =
        LogEntry(
            "",
            "123",
            "456",
            "10123",
            "E",
            "Network",
            "failure",
            "E Network: failure",
            "device",
        )

    @Test
    fun `log filters combine query severity tags and IDs`() {
        assertTrue(
            LogFilter(query = "failure", tag = "Net", level = "W", pid = "123,12", uid = "10123")
                .predicate()(log())
        )
        assertFalse(LogFilter(query = "failure", tag = "Other").predicate()(log()))
        assertFalse(LogFilter(query = "failure", pid = "999").predicate()(log()))
        assertFalse(LogFilter(excludeTag = "Network", exact = true).predicate()(log()))
    }

    @Test
    fun `log filter regex errors and invalid numeric filters are explicit`() {
        assertFails { LogFilter(query = "[", regex = true).predicate() }
        assertFails { LogFilter(pid = "-1").predicate() }
        assertTrue(LogFilter(query = "fail.*", regex = true).predicate()(log()))
    }

    @Test
    fun `pathological regular expressions complete in linear time`() {
        val predicate = LogFilter(query = "(a+)+$", regex = true).predicate()
        assertFalse(predicate(log().copy(raw = "a".repeat(100000) + "!")))
    }

    @Test
    fun `CSV neutralizes formulas and JSONL preserves raw lines`() {
        val e = log().copy(message = "=SUM(1,2)", raw = "a\nb")
        assertTrue(LogExport.write(listOf(e), "CSV").contains("'="))
        assertEquals("a\nb", parseObject(LogExport.write(listOf(e), "JSONL")).text("raw"))
    }

    @Test
    fun `capture merges interleaved request response events by caller ID`() {
        val req =
            parseObject(
                """{"kind":"request","id":"one","method":"GET","url":"https://example.test","complete":true,"headers":[{"name":"X-A","value":"1"}]}"""
            )
        val a = applyCaptureEvent(null, req)!!
        val response =
            applyCaptureEvent(
                a,
                parseObject(
                    """{"kind":"response","id":"one","status":201,"body":"ok","complete":true,"durationMs":5}"""
                ),
            )!!
        assertEquals("one", response.id)
        assertEquals(201, response.status)
        assertEquals("ok", response.response.data)
        assertEquals(a.requestHeaders, response.requestHeaders)
    }

    @Test
    fun `capture diagnostics never overwrite HTTP response body`() {
        val e =
            Exchange(
                id = "one",
                url = "https://example.test",
                response = Body("actual response"),
                status = 200,
            )
        val diagnostic =
            applyCaptureEvent(
                e,
                parseObject(
                    """{"kind":"diagnostic","id":"one","error":"oops","rawBody":"YWJj","rawEncoding":"base64"}"""
                ),
            )!!
        assertEquals("actual response", diagnostic.response.data)
        assertEquals("abc", diagnostic.diagnosticBody!!.view("Text"))
        assertFalse(diagnostic.raw)
    }

    @Test
    fun `diagnostic before request does not prevent HTTP decoding`() {
        val raw =
            applyCaptureEvent(
                null,
                parseObject("""{"kind":"diagnostic","id":"one","message":"observed"}"""),
            )!!
        val req =
            applyCaptureEvent(
                raw,
                parseObject(
                    """{"kind":"request","id":"one","method":"POST","url":"https://example.test","complete":true}"""
                ),
            )!!
        assertFalse(req.raw)
        assertEquals("POST", req.method)
        assertTrue(req.diagnostic.contains("observed"))
    }
}
