package hunter.features.logcat

import hunter.core.logHandledFailure

import hunter.core.StreamingProcess
import hunter.core.StreamSession
import hunter.core.tr
import hunter.features.android.Adb
import hunter.features.android.AndroidApp
import hunter.features.android.Device
import hunter.features.settings.Settings
import kotlinx.coroutines.*

data class LogOptions(
    val buffer: String = "main + system + crash",
    val history: Int = 500,
    val appOnly: Boolean = false,
)

class LogcatSession(
    private val settings: Settings,
    private val serial: String,
    private val app: AndroidApp?,
    private val options: LogOptions,
    private val scope: CoroutineScope,
    private val onEntry: (LogEntry) -> Unit,
    private val log: (String) -> Unit,
    private val ended: () -> Unit,
) : StreamSession {
    private var process: StreamingProcess? = null
    private var reader: Job? = null
    private var watcher: Job? = null
    @Volatile private var stopping = false

    override suspend fun start() {
        val adb = Adb(settings)
        check(adb.device(serial, "get-state").requireSuccess() == "device") {
            tr("Device unavailable")
        }
        if (options.appOnly) {
            require(app?.uid != null) { tr("Select an app with a UID") }
            adb.validateApp(serial, app)
        }
        val help = adb.device(serial, "logcat", "--help").requireSuccess()
        check(help.contains("-T")) { tr("Firmware does not support bounded initial history (-T)") }
        val filterUid = help.contains("--uid")
        val formatUid = Regex("(?im)^\\s*uid\\b").containsMatchIn(help)
        require(!options.appOnly || filterUid || formatUid) {
            tr("Firmware does not support exact UID filtering")
        }
        val args = mutableListOf(settings.adbCommand, "-s", serial, "logcat", "-v", "threadtime")
        if (help.contains("year")) args += listOf("-v", "year")
        if (formatUid) args += listOf("-v", "uid")
        val buffers =
            if (options.buffer == "main + system + crash") listOf("main", "system", "crash")
            else listOf(options.buffer)
        buffers.forEach { args += listOf("-b", it) }
        args += listOf("-T", options.history.coerceIn(1, 5000).toString())
        if (options.appOnly && filterUid) args += "--uid=${app!!.uid}"
        args += "*:V"
        val owned = StreamingProcess.start(args, terminateDescendants = false)
        process = owned
        reader =
            scope.launch(Dispatchers.IO) {
                var buffer = options.buffer
                var previous: LogEntry? = null
                try {
                    val code =
                        owned.read(
                            { line ->
                                if (line.startsWith("---------"))
                                    buffer = line.substringAfterLast(' ')
                                else if (line.isNotEmpty()) {
                                    val entry =
                                        LogParser.parse(
                                            line,
                                            serial,
                                            buffer,
                                            if (options.appOnly && filterUid) app!!.uid.toString()
                                            else "",
                                        )
                                            ?: previous?.copy(
                                                message = line,
                                                raw = line,
                                                id = java.util.UUID.randomUUID().toString(),
                                            )
                                            ?: if (!options.appOnly)
                                                LogEntry(
                                                    "",
                                                    "",
                                                    "",
                                                    "",
                                                    "V",
                                                    "",
                                                    line,
                                                    line,
                                                    serial,
                                                    buffer,
                                                )
                                            else null
                                    if (entry != null) {
                                        previous = entry
                                        if (!options.appOnly || entry.uid == app!!.uid.toString())
                                            onEntry(entry)
                                    }
                                }
                            },
                            { log(it) },
                        )
                    if (!stopping) log(tr("Logcat exited: {0}", code))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logHandledFailure("logcat", "ADB stream reader failed", e)
                    log("Logcat: ${e.message}")
                } finally {
                    if (!stopping) ended()
                }
            }
        if (options.appOnly)
            watcher =
                scope.launch {
                    try {
                        while (isActive) {
                            delay(5000)
                            adb.validateApp(serial, app!!)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logHandledFailure("logcat", "Android target validation failed", e)
                        log(tr("Logcat stopped: {0}", e.message))
                        ended()
                    }
                }
        delay(200)
        check(reader?.isActive == true) {
            tr("Logcat exited immediately after startup. See the journal.")
        }
    }

    override suspend fun stop() =
        withContext(NonCancellable) {
            stopping = true
            watcher?.cancelAndJoin()
            watcher = null
            process?.stop(false)
            reader?.cancelAndJoin()
            process = null
            reader = null
        }
}
