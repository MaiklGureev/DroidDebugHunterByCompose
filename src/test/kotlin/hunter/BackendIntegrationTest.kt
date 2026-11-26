package hunter

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Optional loopback smoke test: uses only a local HTTP server, never an Android device. */
class BackendIntegrationTest {
    @Test
    fun `bundled proxy captures exact binary request and response`() = runBlocking {
        val tools = File("desktop-resources/common/tools").absoluteFile
        val python = File(tools, "python/python.exe")
        assumeTrue(
            python.isFile && File(tools, "proxy-python/mitmproxy").isDirectory,
            "Local capture bundle is optional",
        )
        val settings = Settings(tools = tools.absolutePath, python = python.absolutePath)
        val root = Files.createTempDirectory("ddhc-proxy-test-").toFile()
        val bytes = byteArrayOf(0, 1, 2, 13, 10, -1, -128, 65)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/echo") { exchange ->
                    val input = exchange.requestBody.use { it.readBytes() }
                    exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                    exchange.sendResponseHeaders(201, input.size.toLong())
                    exchange.responseBody.use { it.write(input) }
                }
                start()
            }
        val port = ServerSocket(0).use { it.localPort }
        var stream: StreamingProcess? = null
        var reader: Job? = null
        val events = Channel<JsonObject>(128)
        val errors = StringBuilder()
        try {
            stream =
                StreamingProcess.start(
                    listOf(
                        python.absolutePath,
                        "-u",
                        File("src/main/resources/runtime/agents/proxy_backend.py").absolutePath,
                        "--mode",
                        "regular",
                        "--port",
                        "$port",
                        "--confdir",
                        root.absolutePath,
                    ),
                    settings.environment(),
                )
            val owned = stream
            reader = launch {
                owned.read(
                    { line ->
                        if (line.startsWith("DDH_HTTP_EVENT:"))
                            events.send(parseObject(line.substring(15)))
                    },
                    { errors.appendLine(it) },
                )
            }
            withTimeout(45000) {
                while (true) {
                    val e = events.receive()
                    if (e.text("kind") == "unsupported") fail(e.text("message"))
                    if (e.text("kind") == "ready") break
                }
            }
            val response =
                withContext(Dispatchers.IO) {
                    val client =
                        HttpClient.newBuilder()
                            .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", port)))
                            .build()
                    val request =
                        HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/echo"))
                            .header("Content-Type", "application/octet-stream")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                            .build()
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                }
            assertEquals(201, response.statusCode())
            assertContentEquals(bytes, response.body())
            var requestEvent: JsonObject? = null
            var responseEvent: JsonObject? = null
            withTimeout(10000) {
                while (requestEvent == null || responseEvent == null) {
                    val e = events.receive()
                    when (e.text("kind")) {
                        "request" -> requestEvent = e
                        "response" -> responseEvent = e
                    }
                }
            }
            val request = applyCaptureEvent(null, requestEvent!!)!!
            val exchange = applyCaptureEvent(request, responseEvent!!)!!
            assertContentEquals(bytes, exchange.request.bytes())
            assertContentEquals(bytes, exchange.response.bytes())
            assertTrue(exchange.request.complete)
            assertTrue(exchange.response.complete)
            assertEquals(requestEvent!!.text("id"), responseEvent!!.text("id"))
        } finally {
            withContext(NonCancellable) {
                stream?.stop()
                reader?.cancelAndJoin()
            }
            server.stop(0)
            check(
                root.canonicalPath.startsWith(
                    File(System.getProperty("java.io.tmpdir")).canonicalPath + File.separator
                )
            )
            root.deleteRecursively()
        }
    }
}
