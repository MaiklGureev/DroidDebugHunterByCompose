package hunter

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir

object ProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args[0]) {
            "flood" -> {
                repeat(20000) {
                    println("stdout-$it")
                    System.err.println("stderr-$it")
                }
                println("Юникод ✓")
            }
            "sleep" -> {
                File(args[1]).writeText(ProcessHandle.current().pid().toString())
                Thread.sleep(120000)
            }
            "stop" -> {
                println("ready")
                System.out.flush()
                readlnOrNull()
                println("stopped")
            }
            "exit" -> {
                System.err.println("fixture failure")
                kotlin.system.exitProcess(7)
            }
        }
    }
}

class ProcessTest {
    @Test
    fun `runtime check selects a device and shuts down without touching user data`(
        @TempDir directory: Path
    ) = runBlocking {
        val sentinel =
            directory.resolve("settings.properties").toFile().apply { writeText("sentinel") }
        val report = directory.resolve("report.json").toFile()
        val result =
            Processes.run(
                listOf(
                    File(
                            System.getProperty("java.home"),
                            "bin/${HostPlatform.current.executable("java")}",
                        )
                        .absolutePath,
                    "-Djava.awt.headless=true",
                    "-Ddroiddebughunter.dataDir=${directory.toAbsolutePath()}",
                    "-cp",
                    System.getProperty("test.runtimeClasspath"),
                    "MainKt",
                    "--runtime-check",
                    report.absolutePath,
                ),
                timeoutMs = 30000,
            )
        assertEquals(0, result.code, result.text)
        val data = parseObject(report.readText())
        assertTrue(data.flag("deviceSelection"))
        assertTrue(data.flag("shutdown"))
        assertTrue(data.text("applicationSource").isNotBlank())
        assertEquals("sentinel", sentinel.readText())
    }

    private fun command(vararg args: String) =
        listOf(
            File(System.getProperty("java.home"), "bin/${HostPlatform.current.executable("java")}")
                .absolutePath,
            "-Dfile.encoding=UTF-8",
            "-cp",
            System.getProperty("test.runtimeClasspath"),
            "hunter.ProcessFixture",
        ) + args

    @Test
    fun `drains stdout and stderr concurrently without deadlock`() = runBlocking {
        val r = Processes.run(command("flood"), timeoutMs = 15000)
        assertEquals(0, r.code)
        assertTrue(r.output.contains("stdout-19999"))
        assertTrue(r.error.contains("stderr-19999"))
        assertTrue(r.output.contains("Юникод ✓"))
    }

    @Test
    fun `nonzero exit preserves error`() = runBlocking {
        val r = Processes.run(command("exit"))
        assertEquals(7, r.code)
        assertFailsWith<IllegalStateException> { r.requireSuccess() }
        assertTrue(r.text.contains("fixture failure"))
    }

    @Test
    fun `timeout kills owned process and returns promptly`() = runBlocking {
        val file = File.createTempFile("ddhc-pid", ".txt").apply { deleteOnExit() }
        val start = System.nanoTime()
        assertFailsWith<TimeoutCancellationException> {
            Processes.run(command("sleep", file.absolutePath), timeoutMs = 1800)
        }
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 8)
        val pid = file.readText().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `coroutine cancellation reaps subprocess`() = runBlocking {
        val file = File.createTempFile("ddhc-cancel", ".txt").apply { deleteOnExit() }
        val job =
            launch(Dispatchers.Default) {
                Processes.run(command("sleep", file.absolutePath), timeoutMs = 30000)
            }
        withTimeout(10000) { while (file.length() == 0L) delay(30) }
        val pid = file.readText().toLong()
        withTimeout(6000) { job.cancelAndJoin() }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `cancelling streaming reader unblocks its process pipes`() = runBlocking {
        val file = File.createTempFile("ddhc-stream-cancel", ".txt").apply { deleteOnExit() }
        val stream = StreamingProcess.start(command("sleep", file.absolutePath))
        val reader = launch(Dispatchers.Default) { stream.read({}, {}) }
        withTimeout(10000) { while (file.length() == 0L) delay(30) }
        val pid = file.readText().toLong()
        withTimeout(6000) { reader.cancelAndJoin() }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `streaming helper stops cooperatively and drains final line`() = runBlocking {
        val stream = StreamingProcess.start(command("stop"))
        val ready = CompletableDeferred<Unit>()
        val lines = mutableListOf<String>()
        val reader = async {
            stream.read(
                {
                    lines += it
                    if (it == "ready") ready.complete(Unit)
                },
                {},
            )
        }
        withTimeout(10000) { ready.await() }
        stream.stop()
        assertEquals(0, withTimeout(5000) { reader.await() })
        assertEquals(listOf("ready", "stopped"), lines)
    }
}
