package hunter

import com.google.gson.JsonObject
import java.io.File
import kotlinx.coroutines.*

fun CaptureMode.toolComponents(gadget: Boolean): List<String> =
    when (this) {
        CaptureMode.Proxy -> listOf("python", "proxy")
        CaptureMode.Vpn -> listOf("python", "proxy", "wireguard")
        CaptureMode.OkHttp, CaptureMode.Native -> listOf("python", "capture", if (gadget) "gadget" else "server")
    }

fun toolFindingIds(component: String): List<String> =
    when (component) {
        "capture" -> listOf("frida", "fridaTools")
        "proxy" -> listOf("mitmproxy", "mitmproxyRs")
        else -> listOf(component)
    }

fun componentTitle(id: String): String =
    when (id) {
        "connection" -> tr("Tools for this method")
        "all" -> tr("All tools")
        "frida" -> tr("Frida bundle: client + server + Gadget")
        "python" -> "Python"
        "adb" -> "ADB"
        "capture" -> "Frida Python"
        "server" -> "frida-server"
        "gadget" -> "Gadget"
        "proxy" -> "mitmproxy Python"
        "wireguard" -> "WireGuard APK"
        else -> id
    }

enum class ToolStepState { Prepared, Failed, Skipped }

data class ToolStep(val component: String, val state: ToolStepState, val detail: String = "")

data class ToolPreparation(val config: Settings, val steps: List<ToolStep>)

data class ToolConfiguration(
    val directory: String,
    val adb: String,
    val python: String,
    val versions: Map<String, String>,
)

fun Settings.toolConfiguration() = ToolConfiguration(tools, adb, python, setup.versions)

enum class SetupMethod(val key: String) {
    Managed("Portable Python via uv"),
    Venv("Existing Python + local venv"),
    Target("Existing Python + local packages"),
}

object ToolPolicy {
    val catalog: JsonObject =
        ToolPolicy::class
            .java
            .getResourceAsStream("/runtime/tool-setup/tool-versions.json")!!
            .reader(Charsets.UTF_8)
            .use { com.google.gson.Gson().fromJson(it, JsonObject::class.java) }

    fun target(name: String): String = catalog.get(name).asString

    val ids =
        listOf("python", "frida", "fridaTools", "mitmproxy", "mitmproxyRs", "adb", "wireguard")
    val names =
        mapOf(
            "python" to "Python",
            "frida" to "Frida",
            "fridaTools" to "frida-tools",
            "mitmproxy" to "mitmproxy",
            "mitmproxyRs" to "mitmproxy_rs",
            "adb" to "ADB / Platform Tools",
            "wireguard" to "WireGuard Android",
        )
    private val versionPattern = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:(?:a|b|rc)[0-9]+)?")

    fun validVersion(value: String) = value.length <= 40 && versionPattern.matches(value)

    fun validate(versions: Map<String, String>) {
        require(ids.all { validVersion(versions[it].orEmpty()) }) {
            tr("Enter numeric tool versions, for example 3.14.10 or 17.26.2.")
        }
    }

    fun notice(id: String, actual: String?): String =
        when {
            actual.isNullOrBlank() -> tr("Not found or version unavailable")
            actual == target(id) -> tr("Target version")
            else ->
                tr(
                    "Different from target {0}; you may try this version. Compatibility is not guaranteed.",
                    target(id),
                )
        }

    fun adbVersion(text: String): String? =
        Regex("(?m)^Version ([0-9]+\\.[0-9]+\\.[0-9]+)").find(text)?.groupValues?.get(1)
}

data class ToolSetupOptions(
    val method: SetupMethod = SetupMethod.Managed,
    val offline: Boolean = false,
    val cache: String = "",
    val adbArchive: String = "",
    val uv: String = "",
    val versions: Map<String, String> = ToolPolicy.ids.associateWith(ToolPolicy::target),
) {
    fun version(id: String) = versions[id] ?: ToolPolicy.target(id)
}

data class ToolFinding(
    val id: String,
    val actual: String?,
    val detail: String = "",
    val failed: Boolean = false,
) {
    val title
        get() = when (id) {
            "server" -> "frida-server"
            "gadget" -> "Gadget"
            "wireguard" -> "WireGuard APK"
            else -> ToolPolicy.names[id] ?: id
        }

    val versionId
        get() = if (id == "server" || id == "gadget") "frida" else id

    val warning
        get() = failed || actual.isNullOrBlank() || actual != ToolPolicy.target(versionId)

    val summary
        get() =
            "$title · ${actual ?: "—"} · ${tr("Target")}: ${ToolPolicy.target(versionId)}\n${ToolPolicy.notice(versionId, actual)}" +
                if (detail.isBlank()) "" else "\n$detail"
}

object ToolInspector {
    // Report every module independently, so a broken proxy does not hide a usable Frida
    // installation.
    private val probe =
        """
        import sys, json, platform, struct, importlib, importlib.metadata
        result = {'python': platform.python_version(), 'bits': struct.calcsize('P') * 8, 'executable': sys.executable}
        for key, dist, module in [('frida','frida','frida'), ('fridaTools','frida-tools','frida_tools.repl'), ('mitmproxy','mitmproxy','mitmproxy.tools.dump'), ('mitmproxyRs','mitmproxy_rs','mitmproxy_rs')]:
            if sys.argv[1] == 'capture' and key in ('mitmproxy', 'mitmproxyRs'): continue
            if sys.argv[1] == 'proxy' and key in ('frida', 'fridaTools'): continue
            try:
                imported = importlib.import_module(module)
                result[key] = {'path': getattr(imported, '__file__', '') or ''}
                try:
                    result[key]['version'] = importlib.metadata.version(dist)
                except importlib.metadata.PackageNotFoundError:
                    if key == 'frida': result[key]['version'] = imported.__version__
                    elif key == 'mitmproxy': result[key]['version'] = importlib.import_module('mitmproxy.version').VERSION
            except Exception as e:
                result[key] = {'error': str(e)}
        print(json.dumps(result))
    """
            .trimIndent()

    suspend fun inspect(config: Settings): List<ToolFinding> {
        val findings = mutableListOf<ToolFinding>()
        try {
            val result = Adb(config).run("version", timeout = 10000).requireSuccess()
            findings +=
                ToolFinding("adb", ToolPolicy.adbVersion(result), config.adbCommand + "\n" + result)
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            findings += ToolFinding("adb", null, tr("The check timed out"), true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            findings += ToolFinding("adb", null, e.message.orEmpty(), true)
        }
        try {
            val result =
                Processes.run(
                        listOf(config.pythonCommand, "-c", probe, "capture"),
                        config.environment("capture"),
                        20000,
                    )
                    .requireSuccess()
            val info = parseObject(result)
            val proxyInfo =
                try {
                    parseObject(
                        Processes.run(
                                listOf(config.pythonCommand, "-c", probe, "proxy"),
                                config.environment("proxy"),
                                20000,
                            )
                            .requireSuccess()
                    )
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    JsonObject()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    JsonObject().apply {
                        for (id in listOf("mitmproxy", "mitmproxyRs")) add(
                            id,
                            JsonObject().apply { addProperty("error", e.message) },
                        )
                    }
                }
            val version = info.get("python").asString
            val bits = info.get("bits").asInt
            findings +=
                ToolFinding(
                    "python",
                    version,
                    info.get("executable").asString +
                        " · $bits bit" +
                        if (
                            version.substringBeforeLast('.').let {
                                it !in listOf("3.12", "3.13", "3.14", "3.15")
                            } || bits != 64
                        )
                            "\n" +
                                tr(
                                    "The target proxy requires Python 3.12+ and host-compatible native wheels; 64-bit Python is recommended."
                                )
                        else "",
                    failed = bits != 64,
                )
            for (id in listOf("frida", "fridaTools", "mitmproxy", "mitmproxyRs")) {
                val module =
                    (if (id in listOf("mitmproxy", "mitmproxyRs")) proxyInfo else info)
                        .obj(id)
                findings +=
                    ToolFinding(
                        id,
                        module.get("version")?.asString,
                        module.get("error")?.asString ?: module.get("path")?.asString.orEmpty(),
                        module.has("error"),
                    )
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            findings += ToolFinding("python", null, tr("The check timed out"), true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            findings += ToolFinding("python", null, config.pythonCommand + "\n" + e.message, true)
            for (id in listOf("frida", "fridaTools", "mitmproxy", "mitmproxyRs")) findings +=
                ToolFinding(id, null)
        }
        val apk = withContext(Dispatchers.IO) { ToolLayout.wireGuardApk(config) }
        findings +=
            ToolFinding(
                "wireguard",
                apk?.name?.removePrefix("com.wireguard.android-")?.removeSuffix(".apk"),
                apk?.absolutePath ?: tr("Optional: VPN mode only; Android installation and VPN consent are separate."),
            )
        val fridaVersion = findings.firstOrNull { it.id == "frida" }?.actual ?: config.setup.version("frida")
        withContext(Dispatchers.IO) {
            for (id in listOf("server", "gadget")) {
                val suffix = if (id == "gadget") ".so" else ""
                val folder = File(config.tools, ToolLayout.folder(id))
                val abis = listOf("arm64", "arm", "x86_64", "x86").filter { abi ->
                    val file = File(folder, "frida-$id-$fridaVersion-android-$abi$suffix")
                    runCatching { Adb.validateElf(file, abi) }.isSuccess
                }
                findings += ToolFinding(
                    id,
                    fridaVersion.takeIf { abis.isNotEmpty() },
                    if (abis.isNotEmpty()) "${abis.joinToString()} · ${folder.absolutePath}" else folder.absolutePath,
                )
            }
        }
        return findings
    }

    suspend fun fridaVersion(config: Settings): String =
        Processes.run(
                listOf(
                    config.pythonCommand,
                    "-c",
                    "import frida, frida_tools.repl; print(frida.__version__)",
                ),
                config.environment("capture"),
                15000,
            )
            .requireSuccess()
            .also {
                require(ToolPolicy.validVersion(it)) { tr("Cannot read the Frida client version") }
            }
}
