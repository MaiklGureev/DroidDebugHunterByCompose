package hunter

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

object AppFiles {
    val data =
        (System.getProperty("droiddebughunter.dataDir")?.let(::File)
                ?: HostPlatform.current.dataDirectory(
                    File(System.getProperty("user.home")),
                    System.getenv(),
                ))
            .apply { mkdirs() }
    val runtime = File(data, "runtime")
    val capture = File(data, "capture").apply { mkdirs() }
    val ca
        get() = File(capture, "proxy/mitmproxy-ca-cert.cer")

    fun extract() {
        val loader = AppFiles::class.java.classLoader
        val manifest =
            loader.getResourceAsStream("runtime-manifest.txt")
                ?: error(tr("runtime-manifest.txt not found"))
        manifest.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val name = raw.trim().removePrefix("\uFEFF")
                if (name.isNotEmpty()) {
                    val target = File(data, name)
                    require(target.canonicalPath.startsWith(data.canonicalPath + File.separator))
                    val bytes =
                        loader.getResourceAsStream(name)?.use { it.readBytes() }
                            ?: error(tr("Missing resource {0}", name))
                    if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                        target.parentFile.mkdirs()
                        atomicWrite(target, bytes)
                    }
                }
            }
        }
    }

    fun toolsDefault(): String {
        val candidates =
            listOfNotNull(
                System.getProperty("compose.application.resources.dir")?.let { File(it, "tools") },
                File("desktop-resources/${HostPlatform.current.key}/tools"),
                File("desktop-resources/${HostPlatform.current.os.id}/tools"),
                File(data, "tools/${HostPlatform.current.key}"),
            )
        return (candidates.firstOrNull {
                File(it, "platform-tools/${HostPlatform.current.executable("adb")}").isFile
            } ?: candidates.last())
            .absolutePath
    }

    fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".ddh-", ".tmp")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(
                    temp,
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

data class Settings(
    val tools: String = AppFiles.toolsDefault(),
    val adb: String = "",
    val python: String = "",
    val dark: Boolean = true,
    val autoRefresh: Boolean = true,
    val maxLogs: Int = 20000,
    val maxRequests: Int = 10000,
    val lastSerial: String = "",
    val lastPackage: String = "",
    val vpnHost: String = "10.0.3.2",
    val vpnPort: String = "51820",
    val spawn: Boolean = true,
    val gadget: Boolean = false,
    val language: UiLanguage = UiLanguage.default(),
    val setup: ToolSetupOptions = ToolSetupOptions(),
) {
    val adbCommand
        get() = ToolResolver.adb(adb, File(tools))

    val pythonCommand
        get() = ToolResolver.python(python, File(tools))

    fun environment(component: String = "all") =
        mapOf(
            "PYTHONIOENCODING" to "utf-8",
            "PYTHONUNBUFFERED" to "1",
            "NO_COLOR" to "1",
            "PYTHONPATH" to
                (when (component) {
                        "capture" -> listOf("frida-python")
                        "proxy" -> listOf("proxy-python")
                        else -> listOf("frida-python", "proxy-python")
                    })
                    .map { File(tools, it) }
                    .filter(File::isDirectory)
                    .joinToString(File.pathSeparator) { it.absolutePath },
        )

    fun save(
        file: File = File(AppFiles.data, "settings.properties"),
        platform: HostPlatform = HostPlatform.current,
    ) =
        synchronized(Companion) {
            val p = Properties()
            if (file.isFile) file.inputStream().use { p.load(it) }
            p.setProperty("tools.${platform.key}.root", tools)
            p.setProperty("tools.${platform.key}.adb", adb)
            p.setProperty("tools.${platform.key}.python", python)
            val setupKey = "tools.${platform.key}.setup."
            mapOf(
                    "mode" to setup.mode.name,
                    "method" to setup.method.name,
                    "chosen" to setup.chosen.toString(),
                    "methodChosen" to setup.methodChosen.toString(),
                    "offline" to setup.offline.toString(),
                    "cache" to setup.cache,
                    "adbArchive" to setup.adbArchive,
                    "uv" to setup.uv,
                )
                .forEach { (k, v) -> p.setProperty(setupKey + k, v) }
            ToolPolicy.ids.forEach { p.setProperty(setupKey + "version." + it, setup.version(it)) }
            mapOf(
                    "dark" to dark,
                    "autoRefresh" to autoRefresh,
                    "maxLogs" to maxLogs,
                    "maxRequests" to maxRequests,
                    "lastSerial" to lastSerial,
                    "lastPackage" to lastPackage,
                    "vpnHost" to vpnHost,
                    "vpnPort" to vpnPort,
                    "spawn" to spawn,
                    "gadget" to gadget,
                    "language" to language.code,
                )
                .forEach { (k, v) -> p.setProperty(k, v.toString()) }
            val output = java.io.ByteArrayOutputStream()
            p.store(output, "DroidDebugHunter Compose")
            AppFiles.atomicWrite(file, output.toByteArray())
        }

    companion object {
        fun load(
            file: File = File(AppFiles.data, "settings.properties"),
            platform: HostPlatform = HostPlatform.current,
        ): Settings {
            val p = Properties()
            if (file.isFile) file.inputStream().use { p.load(it) }
            fun s(k: String, d: String = "") = p.getProperty(k, d)
            val legacy =
                platform.os == HostOs.Windows && !p.containsKey("tools.${platform.key}.root")
            val storedRoot = s("tools.${platform.key}.root", if (legacy) s("tools") else "")
            val oldPython =
                if (legacy) s("python").takeUnless { it in listOf("python", "python.exe") } ?: ""
                else ""
            return Settings(
                tools = storedRoot.takeIf { it.isNotBlank() } ?: AppFiles.toolsDefault(),
                adb = s("tools.${platform.key}.adb", if (legacy) s("adb") else ""),
                python = s("tools.${platform.key}.python", oldPython),
                dark = s("dark", "true").toBoolean(),
                autoRefresh = s("autoRefresh", "true").toBoolean(),
                maxLogs = s("maxLogs").toIntOrNull()?.coerceIn(1000, 100000) ?: 20000,
                maxRequests = s("maxRequests").toIntOrNull()?.coerceIn(100, 20000) ?: 10000,
                lastSerial = s("lastSerial"),
                lastPackage = s("lastPackage"),
                vpnHost = s("vpnHost", "10.0.3.2"),
                vpnPort = s("vpnPort", "51820"),
                spawn = s("spawn", "true").toBoolean(),
                gadget = s("gadget").toBoolean(),
                language = UiLanguage.fromCode(s("language")),
                setup =
                    ToolSetupOptions(
                        chosen = s("tools.${platform.key}.setup.chosen").toBoolean(),
                        methodChosen = s("tools.${platform.key}.setup.methodChosen").toBoolean(),
                        mode =
                            SetupMode.entries.firstOrNull {
                                it.name == s("tools.${platform.key}.setup.mode")
                            } ?: SetupMode.Guided,
                        method =
                            SetupMethod.entries.firstOrNull {
                                it.name == s("tools.${platform.key}.setup.method")
                            } ?: SetupMethod.Managed,
                        offline = s("tools.${platform.key}.setup.offline").toBoolean(),
                        cache = s("tools.${platform.key}.setup.cache"),
                        adbArchive = s("tools.${platform.key}.setup.adbArchive"),
                        uv = s("tools.${platform.key}.setup.uv"),
                        versions =
                            ToolPolicy.ids.associateWith {
                                s("tools.${platform.key}.setup.version.$it", ToolPolicy.target(it))
                            },
                    ),
            )
        }
    }
}

data class CommandResult(val code: Int, val output: String, val error: String) {
    val text
        get() = listOf(output.trim(), error.trim()).filter { it.isNotEmpty() }.joinToString("\n")

    fun requireSuccess(): String {
        check(code == 0) { text.ifBlank { tr("Exit code: {0}", code) } }
        return output.trim()
    }
}

/** Every blocking read/wait is isolated on IO; cancellation always reaps the owned process tree. */
object Processes {
    fun start(args: List<String>, env: Map<String, String> = emptyMap()): Process =
        ProcessBuilder(args).apply { environment().putAll(env) }.start()

    fun kill(process: Process) {
        runCatching {
            process.descendants().use { descendants ->
                descendants.forEach { it.destroyForcibly() }
            }
        }
        if (process.isAlive) process.destroyForcibly()
    }

    /** destroyForcibly only requests termination; await the OS reaper even during cancellation. */
    suspend fun reap(process: Process) =
        withContext(Dispatchers.IO + NonCancellable) {
            kill(process)
            check(process.waitFor(3000, TimeUnit.MILLISECONDS)) {
                tr("Could not terminate owned process {0}", process.pid())
            }
        }

    suspend fun run(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30000,
    ): CommandResult =
        withContext(Dispatchers.IO) {
            val process = start(args, env)
            try {
                withTimeout(timeoutMs) {
                    coroutineScope {
                        val output = async {
                            runInterruptible {
                                process.inputStream.bufferedReader(Charsets.UTF_8).use {
                                    readLimited(it, 16 * 1024 * 1024)
                                }
                            }
                        }
                        val error = async {
                            runInterruptible {
                                process.errorStream.bufferedReader(Charsets.UTF_8).use {
                                    readLimited(it, 1024 * 1024)
                                }
                            }
                        }
                        try {
                            val code = runInterruptible { process.waitFor() }
                            CommandResult(code, output.await(), error.await())
                        } finally {
                            if (!isActive) kill(process)
                        }
                    }
                }
            } finally {
                reap(process)
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
        }

    private fun readLimited(reader: java.io.Reader, limit: Int): String {
        val result = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            if (result.length < limit) result.append(buffer, 0, minOf(n, limit - result.length))
        }
        return result.toString()
    }
}

class StreamingProcess(private val process: Process) {
    @Volatile
    var stopping = false
        private set

    suspend fun read(onOutput: suspend (String) -> Unit, onError: suspend (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            try {
                coroutineScope {
                    launch {
                        process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            while (isActive) {
                                val line = runInterruptible { reader.readLine() } ?: break
                                onError(line)
                            }
                        }
                    }
                    val output = async {
                        process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            while (isActive) {
                                val line = runInterruptible { reader.readLine() } ?: break
                                onOutput(line)
                            }
                        }
                    }
                    try {
                        output.await()
                        runInterruptible { process.waitFor() }
                    } finally {
                        // Close the pipes before coroutineScope waits for the stderr reader.
                        if (!isActive) Processes.kill(process)
                    }
                }
            } finally {
                Processes.reap(process)
            }
        }

    suspend fun stop(cooperative: Boolean = true) =
        withContext(Dispatchers.IO + NonCancellable) {
            stopping = true
            if (cooperative)
                runCatching {
                    process.outputStream.writer(Charsets.UTF_8).apply {
                        write("stop\n")
                        flush()
                    }
                }
            if (!cooperative || !process.waitFor(2000, TimeUnit.MILLISECONDS))
                Processes.kill(process)
            Processes.reap(process)
        }

    companion object {
        suspend fun start(
            args: List<String>,
            env: Map<String, String> = emptyMap(),
        ): StreamingProcess {
            var owned: Process? = null
            return try {
                withContext(Dispatchers.IO) {
                    val process = Processes.start(args, env)
                    owned = process
                    StreamingProcess(process)
                }
            } catch (e: CancellationException) {
                owned?.let { Processes.reap(it) }
                throw e
            }
        }
    }
}
