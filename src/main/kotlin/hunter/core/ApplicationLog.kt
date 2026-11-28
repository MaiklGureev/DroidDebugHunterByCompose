package hunter.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ApplicationLog(
    val file: File,
    private val maxBytes: Long = 5L * 1024 * 1024,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ApplicationLog> {
        private val storageMonitor = Any()
        private val credentials = Regex(
            """(?im)(\b(?:authorization|proxy-authorization|cookie|set-cookie|privatekey|presharedkey|password|access[_-]?token|api[_-]?key|pairing[_ -]?code)["']?\s*[:=]\s*)[^\r\n]+"""
        )
    }

    private sealed interface Entry {
        data class Line(val text: String, val visible: Boolean) : Entry
        data class Action(val run: suspend () -> Unit) : Entry
    }

    private val previous = File(file.parentFile, "application.previous.log")
    private val lockFile = File(file.parentFile, ".application-log.lock")
    private val queue = Channel<Entry>(1024)
    private val dropped = AtomicLong()
    private val writeError = MutableStateFlow<String?>(null)
    val fileError = writeError.asStateFlow()
    private var writer: Job? = null

    fun start(
        scope: CoroutineScope,
        onLines: suspend (List<String>) -> Unit,
    ) {
        check(writer == null)
        writer = scope.launch(Dispatchers.IO) {
            var pending: Entry? = null
            while (true) {
                val entry = pending ?: queue.receiveCatching().getOrNull() ?: break
                pending = null
                when (entry) {
                    is Entry.Line -> {
                        val batch = mutableListOf(entry)
                        while (batch.size < 128) {
                            when (val next = queue.tryReceive().getOrNull()) {
                                is Entry.Line -> batch += next
                                else -> {
                                    pending = next
                                    break
                                }
                            }
                        }
                        val missed = dropped.getAndSet(0)
                        if (missed > 0) {
                            batch.add(0, Entry.Line(format("WARN", "journal", "Dropped $missed log entries"), true))
                        }
                        try {
                            append(batch.joinToString("") { it.text + "\n" })
                            writeError.value = null
                        } catch (failure: IOException) {
                            writeError.value = failure.message ?: failure.javaClass.simpleName
                        } catch (failure: SecurityException) {
                            writeError.value = failure.message ?: failure.javaClass.simpleName
                        }
                        val visible = batch.filter { it.visible }.map { it.text }
                        if (visible.isNotEmpty()) onLines(visible)
                    }
                    is Entry.Action -> entry.run()
                }
            }
        }
    }

    fun info(source: String, message: String) = record("INFO", source, message, visible = true)
    fun debug(source: String, message: String) = record("DEBUG", source, message, visible = false)
    fun warning(source: String, message: String, failure: Throwable? = null) =
        record("WARN", source, message, failure, visible = false)
    fun error(source: String, message: String, failure: Throwable? = null) =
        record("ERROR", source, message, failure, visible = true)

    fun crash(thread: Thread, failure: Throwable) {
        runCatching { append(format("ERROR", "jvm", "Uncaught exception on ${thread.name}", failure) + "\n") }
    }

    suspend fun export(destination: File) = action {
        withFiles {
            val target = destination.canonicalFile
            require(target != file.canonicalFile && target != previous.canonicalFile && target != lockFile.canonicalFile) {
                tr("Choose a different file for the log export.")
            }
            val archived = if (previous.isFile) previous.readBytes() else ByteArray(0)
            val active = if (file.isFile) file.readBytes() else ByteArray(0)
            AppFiles.atomicWrite(target, archived + active)
        }
    }

    suspend fun clear(onCleared: suspend () -> Unit = {}) = action {
        withFiles {
            Files.deleteIfExists(previous.toPath())
            AppFiles.atomicWrite(file, ByteArray(0))
        }
        writeError.value = null
        onCleared()
    }

    suspend fun close() {
        queue.close()
        writer?.join()
    }

    private suspend fun action(block: suspend () -> Unit) {
        val running = checkNotNull(writer)
        val result = CompletableDeferred<Result<Unit>>(running)
        try {
            queue.send(Entry.Action {
                if (result.isActive) result.complete(runCatching { block() })
            })
            result.await().getOrThrow()
        } finally {
            result.cancel()
        }
    }

    private fun record(level: String, source: String, message: String, failure: Throwable? = null, visible: Boolean) {
        val accepted = queue.trySend(Entry.Line(format(level, source, message, failure), visible))
        if (accepted.isFailure && !accepted.isClosed) dropped.incrementAndGet()
    }

    private fun format(level: String, source: String, message: String, failure: Throwable? = null): String {
        val detail = if (failure == null) message else "$message\n${failure.stackTraceToString()}"
        val safe = credentials.replace(detail.take(8192)) { "${it.groupValues[1]}[redacted]" }
            .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n    ")
        return "${Instant.now()} [$level] [$source] $safe"
    }

    private fun append(text: String) = withFiles {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (file.length() > 0 && file.length() + bytes.size > maxBytes) {
            Files.move(file.toPath(), previous.toPath(), REPLACE_EXISTING)
        }
        file.appendBytes(bytes)
    }

    private fun <T> withFiles(block: () -> T): T = synchronized(storageMonitor) {
        Files.createDirectories(file.absoluteFile.parentFile.toPath())
        FileChannel.open(lockFile.toPath(), CREATE, WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }
}

internal suspend fun logHandledFailure(source: String, message: String, failure: Throwable) {
    currentCoroutineContext()[ApplicationLog]?.warning(source, message, failure)
}
