package hunter

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*

/** Executes the plan displayed in Settings. Never elevates or installs into a system Python. */
object ToolProvisioner {
    val components =
        listOf("all", "frida", "python", "adb", "capture", "server", "gadget", "proxy", "wireguard")
    private val bundleComponents = listOf("capture", "server", "gadget", "proxy", "wireguard")

    fun plan(config: Settings, component: String): String {
        val options = config.setup
        val selected = if (options.mode == SetupMode.Automatic) "all" else component
        return buildString {
            appendLine(tr("Setup plan"))
            appendLine(tr("Directory: {0}", config.tools))
            appendLine(tr("Components: {0}", selected))
            val method =
                when {
                    options.mode == SetupMode.Manual -> tr("Manual: no automatic installation")
                    selected == "adb" -> tr("Direct native archive (no Python)")
                    else -> tr(options.method.key)
                }
            appendLine(tr("Method: {0}", method))
            appendLine(
                if (options.offline) tr("Offline: local archives, wheelhouse and uv cache only.")
                else tr("Network: official tool downloads and the Python package index.")
            )
            appendLine(
                tr(
                    "No elevation, global pip installs or PATH changes. Android installation and permissions remain separate."
                )
            )
            if (options.mode == SetupMode.Automatic)
                appendLine(
                    tr(
                        "Automatic: try another local setup method on failure; use the app data directory if the selected directory is not writable."
                    )
                )
            ToolPolicy.ids.forEach { id ->
                appendLine(
                    "${ToolPolicy.names[id]}: ${options.version(id)} · ${tr("Target")}: ${ToolPolicy.target(id)}"
                )
                if (options.version(id) != ToolPolicy.target(id))
                    appendLine(ToolPolicy.notice(id, options.version(id)))
            }
            if (options.adbArchive.isNotBlank())
                appendLine(tr("Local ADB archive: {0}", options.adbArchive))
            appendLine()
            appendLine(ToolLayout.structure(config))
            appendLine()
            appendLine(tr("Manual alternatives"))
            appendLine(
                tr(
                    "Select an existing executable, extract official archives into the component folders, or create a local venv:"
                )
            )
            val root = File(config.tools).absolutePath
            val q: (String) -> String = { value ->
                "'" +
                    value.replace(
                        "'",
                        if (HostPlatform.current.os == HostOs.Windows) "''" else "'\\''",
                    ) +
                    "'"
            }
            val prefix = if (HostPlatform.current.os == HostOs.Windows) "& " else ""
            appendLine(prefix + q(config.pythonCommand) + " -m venv " + q(File(root, ".venv").path))
            val python =
                File(
                    root,
                    if (HostPlatform.current.os == HostOs.Windows) ".venv/Scripts/python.exe"
                    else ".venv/bin/python",
                )
            appendLine(
                prefix +
                    q(python.path) +
                    " -m pip install " +
                    q("frida==${options.version("frida")}") +
                    " " +
                    q("frida-tools==${options.version("fridaTools")}")
            )
            appendLine(
                prefix +
                    q(python.path) +
                    " -m pip install " +
                    q("mitmproxy==${options.version("mitmproxy")}") +
                    " " +
                    q("mitmproxy_rs==${options.version("mitmproxyRs")}")
            )
            appendLine(tr("Package-manager alternatives (run yourself if permitted):"))
            appendLine(
                when (HostPlatform.current.os) {
                    HostOs.Windows ->
                        "winget install --id Python.Python.3.14 --exact --scope user\nscoop install python adb"
                    HostOs.MacOS -> "brew install python@3.14 android-platform-tools"
                    HostOs.Linux ->
                        tr(
                            "Use your distribution's Python/adb packages if permitted; otherwise use portable uv or an existing Python. USB udev rules may require an administrator; Wi-Fi ADB is an alternative."
                        )
                }
            )
            appendLine(
                tr(
                    "A package manager may choose another version or be blocked by policy. Recheck actual versions afterwards."
                )
            )
        }
    }

    fun writableRoot(preferred: File, fallback: File?, log: (String) -> Unit): File {
        fun probe(root: File): File {
            root.mkdirs()
            val temporary = Files.createTempFile(root.toPath(), ".ddhc-write-", ".tmp")
            Files.delete(temporary)
            return root.canonicalFile
        }
        return try {
            probe(preferred)
        } catch (e: Exception) {
            if (fallback == null || fallback.canonicalFile == preferred.canonicalFile)
                throw IllegalStateException(
                    tr(
                        "Directory is not writable. Choose a user-owned folder: {0}",
                        preferred.path,
                    ),
                    e,
                )
            log(tr("Directory is not writable; trying {0}", fallback.absolutePath))
            probe(fallback)
        }
    }

    suspend fun prepare(
        initial: Settings,
        component: String,
        progress: (SetupProgress) -> Unit,
        log: (String) -> Unit,
    ): Settings {
        require(initial.tools.isNotBlank()) { tr("Choose a tool directory first.") }
        require(component in components)
        require(initial.setup.mode != SetupMode.Manual) {
            tr("Manual mode only checks tools and displays instructions.")
        }
        ToolPolicy.validate(initial.setup.versions)
        val automatic = initial.setup.mode == SetupMode.Automatic
        val root =
            withContext(Dispatchers.IO) {
                writableRoot(
                    File(initial.tools),
                    if (automatic) File(AppFiles.data, "tools/${HostPlatform.current.key}")
                    else null,
                    log,
                )
            }
        var config = initial.copy(tools = root.path)
        val selected =
            when {
                automatic || component == "all" -> listOf("adb", "python") + bundleComponents
                component == "frida" -> listOf("capture", "server", "gadget")
                else -> listOf(component)
            }
        if ("adb" in selected) {
            log(tr("Preparing component: {0}", "ADB"))
            try {
                val existing =
                    if (automatic && config.setup.adbArchive.isBlank()) {
                        try {
                            ToolPolicy.adbVersion(
                                Adb(config).run("version", timeout = 10000).requireSuccess()
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                if (existing == config.setup.version("adb"))
                    log(tr("Using existing ADB: {0}", config.adbCommand))
                else config = config.copy(adb = ToolDownloads.installAdb(config, progress))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                if (!automatic) throw e
                log(e.message.orEmpty())
                val existing = Adb(config).run("version", timeout = 10000).requireSuccess()
                log(
                    tr("Using existing ADB: {0}", config.adbCommand) +
                        "\n" +
                        ToolPolicy.notice("adb", ToolPolicy.adbVersion(existing))
                )
            }
        }
        val needsPython = selected.any { it == "python" || it in bundleComponents }
        if (needsPython) {
            val methods =
                if (automatic)
                    (listOf(
                            config.setup.method,
                            SetupMethod.Managed,
                            SetupMethod.Venv,
                            SetupMethod.Target,
                        ))
                        .distinct()
                else listOf(config.setup.method)
            var interpreter: String? = null
            val failures = mutableListOf<String>()
            for (method in methods) {
                try {
                    progress(SetupProgress(tr("Preparing Python"), tr(method.key)))
                    log(tr("Trying setup method: {0}", tr(method.key)))
                    interpreter = preparePython(config, method, progress, log)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = "${tr(method.key)}: ${e.message}"
                    failures += failure
                    log(failure)
                    // Never work around a failed integrity check with another source.
                    if (e is SecurityException) throw e
                }
            }
            check(interpreter != null) {
                failures.joinToString("\n") +
                    "\n" +
                    tr(
                        "Try a different method, select an existing Python, use offline files or choose another writable folder."
                    )
            }
            config = config.copy(python = interpreter)
        }
        for ((index, name) in selected.filter { it in bundleComponents }.withIndex()) {
            progress(
                SetupProgress(
                    tr("Preparing component: {0}", name),
                    "${index + 1}/${selected.count { it in bundleComponents }}",
                )
            )
            val args =
                mutableListOf(
                    config.pythonCommand,
                    "-u",
                    File(AppFiles.runtime, "tool-setup/setup_tools.py").absolutePath,
                    "--action",
                    "download",
                    "--component",
                    name,
                    "--root",
                    config.tools,
                )
            for (id in
                listOf("frida", "fridaTools", "mitmproxy", "mitmproxyRs", "wireguard")) args +=
                listOf("--version-$id", config.setup.version(id))
            if (config.setup.cache.isNotBlank()) args += listOf("--cache", config.setup.cache)
            if (config.setup.offline) args += "--offline"
            runStep(args, config.environment(), progress, log)
        }
        progress(SetupProgress(tr("Checking installed tools")))
        return config
    }

    private suspend fun preparePython(
        config: Settings,
        method: SetupMethod,
        progress: (SetupProgress) -> Unit,
        log: (String) -> Unit,
    ): String {
        val root = File(config.tools)
        val version = config.setup.version("python")
        val venv = File(root, ".venvs/${method.name.lowercase()}-$version")
        val executable =
            File(
                venv,
                if (HostPlatform.current.os == HostOs.Windows) "Scripts/python.exe"
                else "bin/python",
            )
        val env =
            mapOf("PYTHONIOENCODING" to "utf-8", "PYTHONUNBUFFERED" to "1", "PYTHONPATH" to "")
        if (method == SetupMethod.Target) {
            runStep(
                listOf(
                    config.pythonCommand,
                    "-c",
                    "import sys; assert sys.version_info >= (3,11), 'Tool preparation needs Python 3.11+'; print(sys.version)",
                ),
                env,
                progress,
                log,
            )
            runStep(listOf(config.pythonCommand, "-m", "pip", "--version"), env, progress, log)
            return config.pythonCommand
        }
        if (!executable.isFile) {
            if (method == SetupMethod.Managed) {
                val uv = prepareUv(config, progress)
                val uvVersion = runStep(listOf(uv, "--version"), env, progress, log).trim()
                if (
                    !uvVersion.startsWith("uv ${ToolPolicy.target("uv")} ") &&
                        uvVersion != "uv ${ToolPolicy.target("uv")}"
                )
                    log(
                        tr(
                            "uv target {0}; found {1}. Continuing with the selected executable.",
                            ToolPolicy.target("uv"),
                            uvVersion,
                        )
                    )
                val cache = File(config.setup.cache.ifBlank { File(root, "downloads").path })
                val uvEnv =
                    env +
                        mapOf(
                            "UV_PYTHON_INSTALL_DIR" to File(root, "python-managed").absolutePath,
                            "UV_CACHE_DIR" to File(cache, "uv").absolutePath,
                            "UV_PYTHON_BIN_DIR" to File(root, "python-bin").absolutePath,
                            "UV_NO_MODIFY_PATH" to "1",
                            "UV_SYSTEM_CERTS" to "true",
                        )
                val args =
                    listOf(uv, "--no-config") +
                        (if (config.setup.offline) listOf("--offline") else emptyList()) +
                        listOf(
                            "venv",
                            "--python",
                            version,
                            "--python-preference",
                            "only-managed",
                            "--seed",
                            venv.absolutePath,
                        )
                runStep(args, uvEnv, progress, log)
            } else {
                runStep(
                    listOf(config.pythonCommand, "-m", "venv", venv.absolutePath),
                    env,
                    progress,
                    log,
                )
            }
        }
        check(executable.isFile) { tr("Python environment was not created: {0}", executable.path) }
        runStep(listOf(executable.path, "-m", "pip", "--version"), env, progress, log)
        return executable.absolutePath
    }

    private suspend fun prepareUv(config: Settings, progress: (SetupProgress) -> Unit): String =
        withContext(Dispatchers.IO) {
            if (config.setup.uv.isNotBlank()) return@withContext config.setup.uv.trim()
            val asset =
                ToolPolicy.catalog
                    .getAsJsonObject("uvAssets")
                    .getAsJsonArray(HostPlatform.current.key)
            val name = asset[0].asString
            val digest = asset[1].asString
            val version = ToolPolicy.target("uv")
            val root = File(config.tools, ".bootstrap/uv-$version")
            val binary = File(root, HostPlatform.current.executable("uv"))
            if (binary.isFile) return@withContext binary.absolutePath
            val cache = File(config.setup.cache.ifBlank { File(config.tools, "downloads").path })
            val archive =
                ToolDownloads.download(
                    "https://github.com/astral-sh/uv/releases/download/$version/$name",
                    File(cache, "uv/$name"),
                    digest,
                    config.setup.offline,
                    progress,
                )
            progress(SetupProgress(tr("Unpacking uv"), name))
            val context = currentCoroutineContext()
            ToolDownloads.staging(root.parentFile) { stage ->
                if (name.endsWith(".zip")) ToolDownloads.unzip(archive, stage)
                else ToolDownloads.untarUv(archive, stage)
                val uv =
                    stage.walkTopDown().firstOrNull { it.name == binary.name && it.isFile }
                        ?: error(tr("uv executable missing from archive"))
                context.ensureActive()
                root.mkdirs()
                uv.copyTo(binary, overwrite = false)
                binary.setExecutable(true)
            }
            binary.absolutePath
        }

    private suspend fun runStep(
        args: List<String>,
        env: Map<String, String>,
        progress: (SetupProgress) -> Unit,
        log: (String) -> Unit,
    ): String {
        log(args.joinToString(" "))
        val output = StringBuilder()
        val process = StreamingProcess.start(args, env)
        val code =
            try {
                withTimeout(1_200_000) {
                    suspend fun line(text: String) {
                        synchronized(output) {
                            if (output.length < 100_000) output.appendLine(text.take(4000))
                        }
                        if (text.startsWith("DDHC_SETUP_PROGRESS:")) {
                            val data =
                                runCatching { parseObject(text.substringAfter(':')) }.getOrNull()
                            if (data != null) {
                                progress(
                                    SetupProgress(
                                        tr("Downloading"),
                                        data.get("label").asString,
                                        data.get("bytes").asLong,
                                        data.get("total").asLong.takeIf { it > 0 },
                                    )
                                )
                                return
                            }
                        }
                        log(text.take(4000))
                        progress(SetupProgress(tr("Installing / checking"), text.take(500)))
                    }
                    process.read({ line(it) }, { line(it) })
                }
            } finally {
                process.stop(cooperative = false)
            }
        check(code == 0) { tr("Setup command failed ({0}): {1}", code, output.toString()) }
        return output.toString()
    }
}
