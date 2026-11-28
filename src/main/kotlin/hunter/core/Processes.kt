package hunter.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

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
    fun start(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        log: ApplicationLog? = null,
    ): Process {
        val executable = args.firstOrNull()?.let { File(it).name }.orEmpty()
        return try {
            ProcessBuilder(args).apply { environment().putAll(env) }.start().also {
                log?.debug("process", "Started $executable · PID ${it.pid()}")
            }
        } catch (failure: Exception) {
            log?.debug("process", "Could not start $executable · ${failure.javaClass.simpleName}")
            throw failure
        }
    }

    fun kill(process: Process, terminateDescendants: Boolean = true) {
        if (terminateDescendants) runCatching {
            process.descendants().use { descendants ->
                descendants.forEach { it.destroyForcibly() }
            }
        }
        if (process.isAlive) process.destroyForcibly()
    }

    /** destroyForcibly only requests termination; await the OS reaper even during cancellation. */
    suspend fun reap(process: Process, terminateDescendants: Boolean = true) =
        withContext(Dispatchers.IO + NonCancellable) {
            kill(process, terminateDescendants)
            check(process.waitFor(3000, TimeUnit.MILLISECONDS)) {
                tr("Could not terminate owned process {0}", process.pid())
            }
        }

    suspend fun run(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30000,
        terminateDescendants: Boolean = true,
    ): CommandResult =
        withContext(Dispatchers.IO) {
            val log = currentCoroutineContext()[ApplicationLog]
            val started = System.nanoTime()
            val process = start(args, env, log)
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
                            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                            log?.debug("process", "PID ${process.pid()} exited with code $code · ${elapsed} ms")
                            CommandResult(code, output.await(), error.await())
                        } finally {
                            if (!isActive) kill(process, terminateDescendants)
                        }
                    }
                }
            } catch (failure: Exception) {
                log?.debug("process", "PID ${process.pid()} interrupted · ${failure.javaClass.simpleName}")
                throw failure
            } finally {
                reap(process, terminateDescendants)
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

class StreamingProcess(
    private val process: Process,
    private val terminateDescendants: Boolean = true,
) {
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
                        runInterruptible { process.waitFor() }.also { code ->
                            currentCoroutineContext()[ApplicationLog]
                                ?.debug("process", "Streaming PID ${process.pid()} exited with code $code")
                        }
                    } finally {
                        // Close the pipes before coroutineScope waits for the stderr reader.
                        if (!isActive) Processes.kill(process, terminateDescendants)
                    }
                }
            } finally {
                Processes.reap(process, terminateDescendants)
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
                Processes.kill(process, terminateDescendants)
            Processes.reap(process, terminateDescendants)
        }

    companion object {
        suspend fun start(
            args: List<String>,
            env: Map<String, String> = emptyMap(),
            terminateDescendants: Boolean = true,
        ): StreamingProcess {
            var owned: Process? = null
            return try {
                withContext(Dispatchers.IO) {
                    val process = Processes.start(args, env, currentCoroutineContext()[ApplicationLog])
                    owned = process
                    StreamingProcess(process, terminateDescendants)
                }
            } catch (e: CancellationException) {
                owned?.let { Processes.reap(it, terminateDescendants) }
                throw e
            }
        }
    }
}
