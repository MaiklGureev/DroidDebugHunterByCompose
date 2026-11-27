package hunter.features.tools

import hunter.core.AppFiles
import hunter.core.HostOs
import hunter.core.HostPlatform
import hunter.core.Processes
import hunter.core.StreamingProcess
import hunter.core.parseObject
import hunter.core.tr
import hunter.features.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*

/** Executes the plan displayed on Connection. Never elevates or installs into a system Python. */
object ToolProvisioner {
    private val bundleComponents = listOf("capture", "server", "gadget", "proxy", "wireguard")
    private val pythonPackages = setOf("capture", "proxy")

    fun expand(components: List<String>): List<String> =
        components.flatMap {
            when (it) {
                "all" -> listOf("adb", "python") + bundleComponents
                "frida" -> listOf("capture", "server", "gadget")
                "adb", "python", in bundleComponents -> listOf(it)
                else -> error("Unknown tool component: $it")
            }
        }.distinct()

    fun plan(config: Settings, components: List<String>): String {
        val options = config.setup
        val selected = expand(components)
        return buildString {
            appendLine(tr("Setup plan"))
            appendLine(tr("Directory: {0}", config.tools))
            appendLine(tr("Components: {0}", selected.joinToString { componentTitle(it) }))
            val method =
                when {
                    selected.all { it == "adb" || it == "wireguard" } -> tr("Direct native archive (no Python)")
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
            selected.flatMap(::toolFindingIds)
                .map { if (it in listOf("server", "gadget")) "frida" else it }
                .distinct().forEach { id ->
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

    fun writableRoot(directory: File): File =
        try {
            directory.mkdirs()
            val temporary = Files.createTempFile(directory.toPath(), ".ddhc-write-", ".tmp")
            Files.delete(temporary)
            directory.canonicalFile
        } catch (e: Exception) {
            throw IllegalStateException(
                tr("Directory is not writable. Choose a user-owned folder: {0}", directory.path),
                e,
            )
        }

    suspend fun prepare(
        initial: Settings,
        components: List<String>,
        progress: (SetupProgress) -> Unit,
        log: (String) -> Unit,
    ): ToolPreparation {
        require(initial.tools.isNotBlank()) { tr("Choose a tool directory first.") }
        require(components.isNotEmpty())
        ToolPolicy.validate(initial.setup.versions)
        val selected = expand(components)
        val root = withContext(Dispatchers.IO) { writableRoot(File(initial.tools)) }
        var config = initial.copy(tools = root.path)
        val steps = mutableListOf<ToolStep>()
        suspend fun step(name: String, block: suspend () -> Unit) {
            val title = componentTitle(name)
            progress(SetupProgress(tr("Preparing component: {0}", title)))
            log(tr("Preparing component: {0}", title))
            val outcome = try {
                if (name == "adb" || name == "wireguard") {
                    withTimeout(300_000) { block() }
                } else block()
                ToolStep(name, ToolStepState.Prepared)
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                ToolStep(name, ToolStepState.Failed, tr("The check timed out"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolStep(name, ToolStepState.Failed, e.message ?: e.javaClass.simpleName)
            }
            steps += outcome
            if (outcome.state == ToolStepState.Failed)
                log(tr("Component failed: {0} · {1}", title, outcome.detail))
        }

        if ("adb" in selected) step("adb") {
            config = config.copy(adb = ToolDownloads.installAdb(config, progress))
        }

        val assets = selected.filter { it in bundleComponents && it != "wireguard" }
        val packagesRequested = assets.any { it in pythonPackages }
        val nativeRequested = assets.any { it !in pythonPackages }
        var helper = if (nativeRequested && !packagesRequested && "python" !in selected)
            existingPython(config) else null
        var pythonReady = false
        if ("python" in selected || packagesRequested || (nativeRequested && helper == null)) {
            step("python") {
                val method = config.setup.method
                progress(SetupProgress(tr("Preparing Python"), tr(method.key)))
                log(tr("Trying setup method: {0}", tr(method.key)))
                val interpreter = preparePython(config, method, progress, log)
                config = config.copy(python = interpreter)
                helper = interpreter
                pythonReady = true
            }
            if (nativeRequested && helper == null) helper = existingPython(config)
        }
        for (name in assets) {
            val python = helper
            if ((name in pythonPackages && !pythonReady) || python == null) {
                val detail = tr("Prepare Python first, or use the download links for manual setup.")
                steps += ToolStep(name, ToolStepState.Skipped, detail)
                log(tr("Component skipped: {0} · {1}", componentTitle(name), detail))
                continue
            }
            step(name) { prepareComponent(config, python, name, progress, log) }
        }
        if ("wireguard" in selected) step("wireguard") {
            ToolLayout.prepareWireGuard(config, progress)
        }
        progress(SetupProgress(tr("Checking installed tools")))
        return ToolPreparation(config, steps)
    }

    private suspend fun <T> available(check: suspend () -> T): T? =
        try {
            check()
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun existingPython(config: Settings): String? = available {
        val command = config.pythonCommand
        Processes.run(
            listOf(command, "-c", "import sys; assert sys.version_info >= (3, 11)"),
            config.environment() + ("PYTHONPATH" to ""),
            8000,
        ).requireSuccess()
        command
    }

    private suspend fun prepareComponent(
        config: Settings,
        python: String,
        name: String,
        progress: (SetupProgress) -> Unit,
        log: (String) -> Unit,
    ) {
        val args = mutableListOf(
            python,
            "-u",
            File(AppFiles.runtime, "tool-setup/setup_tools.py").absolutePath,
            "--action", "download",
            "--component", name,
            "--root", config.tools,
        )
        for (id in listOf("frida", "fridaTools", "mitmproxy", "mitmproxyRs", "wireguard"))
            args += listOf("--version-$id", config.setup.version(id))
        if (config.setup.cache.isNotBlank()) args += listOf("--cache", config.setup.cache)
        if (config.setup.offline) args += "--offline"
        runStep(args, config.environment(), progress, log)
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
