package hunter

import com.google.gson.JsonObject
import java.io.File
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.*

enum class CaptureMode(private val titleKey: String, private val hintKey: String) {
    Proxy(
        "HTTP(S) proxy",
        "Rootless · system proxy over ADB reverse. HTTPS requires CA trust. Package and UID are unknown.",
    ),
    OkHttp(
        "Frida · OkHttp",
        "OkHttp 3/4 in the selected app. Requires root frida-server or a debuggable app + Gadget.",
    ),
    Native(
        "Frida · Native TLS",
        "Process TLS functions, HTTP/1 and HTTP/2. Static TLS functions and HTTP/3 may be unavailable.",
    ),
    Vpn(
        "VPN · WireGuard",
        "Rootless · WireGuard on Android. Package attribution uses the profile allowlist. HTTPS requires CA trust.",
    );

    val title
        get() = tr(titleKey)

    val hint
        get() = tr(hintKey)
}

class CaptureLock : AutoCloseable {
    private val channel =
        FileChannel.open(
            File(AppFiles.capture, "compose-session.lock").toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
    private val lock: FileLock

    init {
        lock =
            try {
                channel.tryLock() ?: error(tr("Another session is using the capture store"))
            } catch (e: Exception) {
                channel.close()
                throw e
            }
    }

    override fun close() {
        lock.release()
        channel.close()
    }
}

class ProxyLease(
    private val adb: Adb,
    private val serial: String,
    private val log: (String) -> Unit,
) {
    private val keys =
        listOf(
            "http_proxy",
            "global_http_proxy_host",
            "global_http_proxy_port",
            "global_http_proxy_exclusion_list",
            "global_proxy_pac_url",
        )
    private val original = linkedMapOf<String, String>()
    private val intended = linkedMapOf<String, String>()
    private var remote: String? = null
    private var host: String? = null
    private var touched = false

    suspend fun start(port: Int) {
        for (key in keys) original[key] = adb.shell(serial, "settings get global $key")
        val allocated =
            withContext(NonCancellable) {
                adb.device(serial, "reverse", "tcp:0", "tcp:$port", timeout = 5000)
                    .requireSuccess()
                    .toIntOrNull()
            }
        check(allocated != null && allocated in 1..65535) {
            tr("ADB reverse did not return a port")
        }
        remote = "tcp:$allocated"
        host = "tcp:$port"
        currentCoroutineContext().ensureActive()
        intended.putAll(
            mapOf(
                "http_proxy" to "127.0.0.1:$allocated",
                "global_http_proxy_host" to "127.0.0.1",
                "global_http_proxy_port" to "$allocated",
                "global_http_proxy_exclusion_list" to "",
                "global_proxy_pac_url" to "",
            )
        )
        touched = true
        for (key in keys.drop(1) + keys.first()) put(key, intended.getValue(key))
    }

    private suspend fun put(key: String, value: String) {
        adb.shell(serial, "settings put global $key ${Adb.quote(value)}")
    }

    suspend fun close() {
        if (touched) {
            var ours = true
            for (key in keys) {
                val current = adb.shell(serial, "settings get global $key")
                if (current != original[key] && current != intended[key]) ours = false
            }
            if (ours) {
                put("http_proxy", original["http_proxy"]?.takeUnless { it == "null" } ?: ":0")
                for (key in keys.drop(1) + keys.first()) {
                    if (original[key] == "null") adb.shell(serial, "settings delete global $key")
                    else put(key, original.getValue(key))
                }
            } else log(tr("Proxy changed by another owner; new settings preserved"))
            touched = false
        }
        remote?.let { owned ->
            val mappings = adb.device(serial, "reverse", "--list", timeout = 5000).requireSuccess()
            if (
                mappings.lineSequence().any {
                    it.trim().split(Regex("\\s+")).takeLast(2) == listOf(owned, host)
                }
            )
                adb.device(serial, "reverse", "--remove", owned, timeout = 5000).requireSuccess()
        }
        remote = null
    }
}

class CaptureSession(
    private val settings: Settings,
    private val serial: String,
    private val mode: CaptureMode,
    private val app: AndroidApp?,
    private val scope: CoroutineScope,
    private val onEvent: suspend (JsonObject) -> Unit,
    private val log: (String) -> Unit,
    private val onEnded: () -> Unit,
) {
    private val adb = Adb(settings)
    private var process: StreamingProcess? = null
    private var reader: Job? = null
    private var proxy: ProxyLease? = null
    private var forward: String? = null
    private var dataLock: CaptureLock? = null
    @Volatile private var stopping = false
    private val ready = CompletableDeferred<Unit>()

    suspend fun start() {
        val relevantTools =
            setOf("python", "adb") +
                if (mode == CaptureMode.Proxy || mode == CaptureMode.Vpn)
                    setOf("mitmproxy", "mitmproxyRs")
                else setOf("frida", "fridaTools")
        ToolInspector.inspect(settings)
            .filter { it.id in relevantTools && it.warning }
            .forEach { log(it.summary) }
        check(adb.device(serial, "get-state").requireSuccess() == "device") {
            tr("Device unauthorized / unavailable")
        }
        if (mode != CaptureMode.Proxy) {
            requireNotNull(app) { tr("Select an app") }
            adb.validateApp(serial, app)
        }
        dataLock = withContext(Dispatchers.IO) { CaptureLock() }
        val args =
            if (mode == CaptureMode.Proxy || mode == CaptureMode.Vpn) {
                prepareAssets(settings, if (mode == CaptureMode.Vpn) app else null, locked = true)
                val port =
                    if (mode == CaptureMode.Vpn)
                        settings.vpnPort.toIntOrNull()?.takeIf { it in 1024..65535 }
                            ?: error(tr("UDP port: 1024…65535"))
                    else withContext(Dispatchers.IO) { ServerSocket(0).use { it.localPort } }
                val command =
                    mutableListOf(
                        settings.pythonCommand,
                        "-u",
                        agent("proxy_backend.py"),
                        "--mode",
                        if (mode == CaptureMode.Vpn) "wireguard" else "regular",
                        "--confdir",
                        File(AppFiles.capture, "proxy").absolutePath,
                        "--port",
                        "$port",
                    )
                if (mode == CaptureMode.Vpn) {
                    val profile = profile(app!!, settings)
                    command +=
                        listOf(
                            "--endpoint",
                            endpoint(settings),
                            "--package",
                            app.packageName,
                            "--config-out",
                            File(profile, "ddh.conf").absolutePath,
                            "--keyfile",
                            File(profile, "keys.json").absolutePath,
                        )
                }
                command
            } else {
                val version = ToolInspector.fridaVersion(settings)
                if (version != ToolPolicy.target("frida")) log(ToolPolicy.notice("frida", version))
                var gadget: File? = null
                val connection: List<String>
                if (settings.gadget) {
                    require(settings.spawn) { tr("Enable app launch for Gadget") }
                    check(
                        adb.shell(serial, "run-as ${Adb.quote(app!!.packageName)} id")
                            .contains("uid=")
                    ) {
                        tr("Gadget requires working run-as / debuggable")
                    }
                    val arch = adb.architecture(serial, app)
                    gadget =
                        File(settings.tools, "frida-gadget/frida-gadget-$version-android-$arch.so")
                    withContext(Dispatchers.IO) { Adb.validateElf(gadget, arch) }
                    connection = listOf("--device", serial, "--gadget", gadget.absolutePath)
                } else {
                    val allocated =
                        withContext(NonCancellable) {
                            adb.device(serial, "forward", "tcp:0", "tcp:27042", timeout = 5000)
                                .requireSuccess()
                                .toIntOrNull()
                        }
                    check(allocated != null && allocated in 1..65535) {
                        tr("ADB forward did not return a port")
                    }
                    forward = "tcp:$allocated"
                    currentCoroutineContext().ensureActive()
                    connection = listOf("--host", "127.0.0.1:$allocated")
                }
                if (mode == CaptureMode.Native)
                    listOf(
                        settings.pythonCommand,
                        "-u",
                        agent("native_backend.py"),
                        "--package",
                        app!!.packageName,
                        "--uid",
                        app.uid?.toString() ?: "",
                    ) + connection + if (settings.spawn) listOf("--spawn") else emptyList()
                else
                    listOf(
                        settings.pythonCommand,
                        "-u",
                        "-m",
                        "frida_tools.repl",
                        if (settings.gadget) "-D" else "-H",
                        if (settings.gadget) serial
                        else "127.0.0.1:${forward!!.substringAfter(':')}",
                        if (settings.spawn) "-f" else "-N",
                        app!!.packageName,
                        "-l",
                        agent("okhttp.js"),
                        "-q",
                        "-t",
                        "inf",
                        "--exit-on-error",
                        "--no-auto-reload",
                    ) +
                        (gadget?.let { listOf("--aux", "gadget=(string)${it.absolutePath}") }
                            ?: emptyList())
            }
        val owned =
            StreamingProcess.start(
                args,
                settings.environment(
                    if (mode == CaptureMode.Proxy || mode == CaptureMode.Vpn) "proxy" else "capture"
                ),
            )
        process = owned
        reader =
            scope.launch(Dispatchers.IO) {
                try {
                    val code =
                        owned.read(
                            { line ->
                                val marker = line.indexOf("DDH_HTTP_EVENT:")
                                if (marker >= 0) {
                                    val event =
                                        runCatching { parseObject(line.substring(marker + 15)) }
                                            .getOrElse { error ->
                                                JsonObject().apply {
                                                    addProperty("kind", "diagnostic")
                                                    addProperty(
                                                        "message",
                                                        tr(
                                                            "Invalid engine event: {0}",
                                                            error.message,
                                                        ),
                                                    )
                                                    addProperty("rawBody", line.take(65536))
                                                    addProperty("rawEncoding", "text")
                                                    addProperty("rawTruncated", line.length > 65536)
                                                }
                                            }
                                    when (event.text("kind")) {
                                        "ready" -> ready.complete(Unit)
                                        "unsupported" ->
                                            ready.completeExceptionally(
                                                IllegalStateException(event.text("message"))
                                            )
                                    }
                                    event.addProperty("deviceSerial", serial)
                                    if (!event.has("source"))
                                        event.addProperty("source", mode.title)
                                    onEvent(event)
                                } else if (line.isNotBlank() && !line.contains("PrivateKey", true))
                                    log(line.take(4000))
                            },
                            { if (!it.contains("PrivateKey", true)) log(it.take(4000)) },
                        )
                    if (!stopping) {
                        ready.completeExceptionally(
                            IllegalStateException(
                                tr("The engine exited (code {0}). See the journal.", code)
                            )
                        )
                        log(tr("Engine exited: {0}", code))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ready.completeExceptionally(e)
                    log(tr("Engine error: {0}", e.message))
                } finally {
                    if (!stopping) onEnded()
                }
            }
        withTimeout(45000) { ready.await() }
        check(reader?.isActive == true) { tr("The engine exited after confirming readiness") }
        if (mode == CaptureMode.Proxy) {
            proxy = ProxyLease(adb, serial, log)
            proxy!!.start(args.last().toInt())
        }
    }

    suspend fun stop() =
        withContext(NonCancellable) {
            stopping = true
            suspend fun cleanup(name: String, block: suspend () -> Unit) {
                try {
                    withTimeout(15000) { block() }
                } catch (e: Exception) {
                    log("$name: ${e.message}")
                }
            }
            cleanup(tr("Restoring proxy")) { proxy?.close() }
            proxy = null
            cleanup(tr("Stopping engine")) {
                process?.stop(mode != CaptureMode.OkHttp)
                reader?.join()
            }
            process = null
            reader?.cancel()
            reader = null
            if (mode == CaptureMode.Vpn)
                cleanup("WireGuard") {
                    adb.shell(
                        serial,
                        "am broadcast -a com.wireguard.android.action.SET_TUNNEL_DOWN --es tunnel ddh -p com.wireguard.android",
                    )
                    log(tr("Check that the WireGuard tunnel is disabled on Android"))
                }
            forward?.let { owned ->
                cleanup("ADB forward") {
                    val lines = adb.run("forward", "--list").requireSuccess()
                    if (
                        lines.lineSequence().any {
                            it.trim().split(Regex("\\s+")) == listOf(serial, owned, "tcp:27042")
                        }
                    )
                        adb.device(serial, "forward", "--remove", owned).requireSuccess()
                }
            }
            forward = null
            dataLock?.close()
            dataLock = null
        }

    companion object {
        fun agent(name: String) = File(AppFiles.runtime, "agents/$name").absolutePath

        fun endpoint(settings: Settings): String {
            require(
                settings.vpnHost.matches(Regex("(?:[A-Za-z0-9][A-Za-z0-9.\\-]*|\\[[a-fA-F0-9:]+])"))
            ) {
                tr("Enter a computer IP / DNS name reachable from Android")
            }
            require(settings.vpnPort.toIntOrNull() in 1024..65535) { tr("UDP port: 1024…65535") }
            return "${settings.vpnHost}:${settings.vpnPort}"
        }

        fun profile(app: AndroidApp, settings: Settings): File {
            val hash =
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("${app.packageName}|${app.user}|${endpoint(settings)}".toByteArray())
                    .take(12)
                    .joinToString("") { "%02x".format(it) }
            return File(AppFiles.capture, "vpn/$hash").apply { mkdirs() }
        }

        suspend fun prepareAssets(
            settings: Settings,
            app: AndroidApp? = null,
            locked: Boolean = false,
        ): String =
            withContext(Dispatchers.IO) {
                val lock = if (!locked) CaptureLock() else null
                try {
                    val args =
                        mutableListOf(
                            settings.pythonCommand,
                            "-u",
                            agent("capture_setup.py"),
                            "--root",
                            AppFiles.capture.absolutePath,
                        )
                    if (app != null) {
                        val profile = profile(app, settings)
                        args +=
                            listOf(
                                "--keys",
                                File(profile, "keys.json").absolutePath,
                                "--config",
                                File(profile, "ddh.conf").absolutePath,
                                "--metadata",
                                File(profile, "metadata.json").absolutePath,
                                "--endpoint",
                                endpoint(settings),
                                "--package",
                                app.packageName,
                            )
                    }
                    Processes.run(args, settings.environment("proxy"), 45000).requireSuccess()
                } finally {
                    lock?.close()
                }
            }
    }
}

/** Immutable copies make in-flight response updates visible in every open detail window. */
fun applyCaptureEvent(existing: Exchange?, event: JsonObject, name: String = ""): Exchange? {
    fun body() = Body(event.text("body"), event.text("encoding", "text"), event.flag("complete"))
    return when (event.text("kind")) {
        "request" ->
            existing?.takeUnless { it.raw }
                ?: Exchange(
                    id = event.text("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    startedAt = event.text("startedAt", java.time.OffsetDateTime.now().toString()),
                    method = event.text("method"),
                    url = event.text("url"),
                    packageName = event.text("packageName"),
                    appName = name,
                    uid = event.text("uid"),
                    appId = event.text("appId"),
                    source = event.text("source"),
                    serial = event.text("deviceSerial"),
                    version = event.text("httpVersion", "HTTP/1.1"),
                    requestHeaders = event.headers(),
                    request = body(),
                    hasBody = event.flag("hasBody") || event.text("body").isNotEmpty(),
                    diagnostic = existing?.diagnostic ?: "",
                    diagnosticBody = existing?.diagnosticBody,
                )
        "request_update" ->
            existing?.copy(
                request = body(),
                hasBody = existing.hasBody || event.text("body").isNotEmpty(),
            )
        "response" ->
            existing?.copy(
                status = event.text("status").toIntOrNull(),
                statusText = event.text("statusText"),
                responseHeaders = event.headers(),
                response = body(),
                duration = event.text("durationMs").toDoubleOrNull(),
                version = event.text("httpVersion", existing.version),
            )
        "error" ->
            existing?.copy(
                error = event.text("message"),
                diagnostic = json.toJson(event),
                duration = event.text("durationMs").toDoubleOrNull(),
            )
        "diagnostic",
        "stream" ->
            (existing
                    ?: Exchange(
                        id = event.text("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        method = "RAW",
                        url = event.text("url"),
                        source = event.text("source"),
                        serial = event.text("deviceSerial"),
                        raw = true,
                        packageName = event.text("packageName"),
                    ))
                .copy(
                    error = event.text("error"),
                    diagnostic = json.toJson(event),
                    diagnosticBody =
                        Body(
                            event.text("rawBody"),
                            event.text("rawEncoding", "base64"),
                            !event.flag("rawTruncated"),
                        ),
                )
        else -> null
    }
}
