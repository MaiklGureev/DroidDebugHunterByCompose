package hunter

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

object AppFiles {
    val data =
        File(
                System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
                "DroidDebugHunterCompose",
            )
            .apply { mkdirs() }
    val runtime = File(data, "runtime")
    val capture = File(data, "capture").apply { mkdirs() }
    val ca
        get() = File(capture, "proxy/mitmproxy-ca-cert.cer")

    fun extract() {
        val loader = AppFiles::class.java.classLoader
        val manifest =
            loader.getResourceAsStream("runtime-manifest.txt")
                ?: error("Не найден runtime-manifest.txt")
        manifest.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val name = raw.trim().removePrefix("\uFEFF")
                if (name.isNotEmpty()) {
                    val target = File(data, name)
                    require(target.canonicalPath.startsWith(data.canonicalPath + File.separator))
                    val bytes =
                        loader.getResourceAsStream(name)?.use { it.readBytes() }
                            ?: error("Нет ресурса $name")
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
                File("desktop-resources/common/tools"),
                File("tools"),
                File(data, "tools"),
            )
        return (candidates.firstOrNull { File(it, "platform-tools/adb.exe").isFile }
                ?: candidates.last())
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
    val python: String = "python",
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
) {
    val adbCommand
        get() =
            adb.ifBlank {
                File(tools, "platform-tools/adb.exe").takeIf { it.isFile }?.absolutePath ?: "adb"
            }

    val pythonCommand
        get() =
            if (python in listOf("", "python", "python.exe"))
                File(tools, "python/python.exe").takeIf { it.isFile }?.absolutePath ?: "python"
            else python

    fun environment() =
        mapOf(
            "PYTHONIOENCODING" to "utf-8",
            "PYTHONUNBUFFERED" to "1",
            "NO_COLOR" to "1",
            "PYTHONPATH" to
                listOf("frida-python", "proxy-python").joinToString(File.pathSeparator) {
                    File(tools, it).absolutePath
                },
        )

    fun save() {
        val p = Properties()
        mapOf(
                "tools" to tools,
                "adb" to adb,
                "python" to python,
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
            )
            .forEach { (k, v) -> p.setProperty(k, v.toString()) }
        val output = java.io.ByteArrayOutputStream()
        p.store(output, "DroidDebugHunter Compose")
        AppFiles.atomicWrite(File(AppFiles.data, "settings.properties"), output.toByteArray())
    }

    companion object {
        fun load(): Settings {
            val p = Properties()
            val file = File(AppFiles.data, "settings.properties")
            if (file.isFile) file.inputStream().use { p.load(it) }
            fun s(k: String, d: String = "") = p.getProperty(k, d)
            return Settings(
                tools = s("tools", AppFiles.toolsDefault()),
                adb = s("adb"),
                python = s("python", "python"),
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
            )
        }
    }
}

data class CommandResult(val code: Int, val output: String, val error: String) {
    val text
        get() = listOf(output.trim(), error.trim()).filter { it.isNotEmpty() }.joinToString("\n")

    fun requireSuccess(): String {
        check(code == 0) { text.ifBlank { "Код завершения: $code" } }
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
                kill(process)
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
                Processes.kill(process)
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
            process.waitFor(3000, TimeUnit.MILLISECONDS)
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
                owned?.let(Processes::kill)
                throw e
            }
        }
    }
}
