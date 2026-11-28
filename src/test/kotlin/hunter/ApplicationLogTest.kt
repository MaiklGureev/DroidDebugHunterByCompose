package hunter

import hunter.core.ApplicationLog
import hunter.core.HostPlatform
import hunter.core.Processes
import java.io.File
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir

class ApplicationLogTest {
    @Test
    fun `export includes queued operations and process metadata with credential fields masked`(@TempDir directory: Path): Unit =
        runBlocking {
            val log = ApplicationLog(directory.resolve("application.log").toFile())
            val visible = mutableListOf<String>()
            log.start(this) { visible.addAll(it) }
            try {
                log.info("operation", "Loading applications")
                log.error("adb", "Connection failed", IllegalStateException("Authorization: secret-value"))
                val java = File(System.getProperty("java.home"), "bin/${HostPlatform.current.executable("java")}")
                withContext(log) {
                    Processes.run(listOf(java.path, "-Dfixture=private-argument", "-version")).requireSuccess()
                }
                val export = directory.resolve("export.log").toFile()
                log.export(export)
                val text = export.readText()
                assertContains(text, "[INFO] [operation] Loading applications")
                assertContains(text, "[ERROR] [adb] Connection failed")
                assertContains(text, "Authorization: [redacted]")
                assertContains(text, "[DEBUG] [process]")
                assertContains(text, "exited with code 0")
                assertFalse("secret-value" in text)
                assertFalse("private-argument" in text)
                assertEquals(2, visible.size)
                assertFailsWith<IllegalArgumentException> { log.export(log.file) }
            } finally {
                log.close()
            }
        }

    @Test
    fun `export combines rotated files and clear removes the entire previous journal`(@TempDir directory: Path) =
        runBlocking {
            val log = ApplicationLog(directory.resolve("application.log").toFile(), maxBytes = 150)
            val export = directory.resolve("export.log").toFile()
            val visible = mutableListOf<String>()
            log.start(this) { visible.addAll(it) }
            try {
                log.info("test", "old event · " + "x".repeat(75))
                log.export(export)
                log.info("test", "new event · " + "x".repeat(75))
                log.export(export)
                val history = export.readText()
                assertContains(history, "old event")
                assertTrue(history.indexOf("old event") < history.indexOf("new event"))
                assertTrue(directory.resolve("application.previous.log").toFile().isFile)
                log.clear { visible.clear() }
                assertTrue(visible.isEmpty())
                assertFalse(directory.resolve("application.previous.log").toFile().exists())
                assertEquals("", log.file.readText())
                assertContains(export.readText(), "old event")
                log.info("test", "after clear")
            } finally {
                log.close()
            }
            assertContains(log.file.readText(), "after clear")
            assertFalse("old event" in log.file.readText())
        }

    @Test
    fun `fatal exceptions reach disk even before the UI starts`(@TempDir directory: Path) {
        val log = ApplicationLog(directory.resolve("logs/application.log").toFile())
        log.crash(Thread.currentThread(), IllegalStateException("Startup failed"))
        assertContains(log.file.readText(), "[ERROR] [jvm]")
        assertContains(log.file.readText(), "Startup failed")
    }

    @Test
    fun `a disk write failure still delivers lines to the on-screen journal`(@TempDir directory: Path): Unit =
        runBlocking {
            val blocked = directory.resolve("blocked").toFile().apply { writeText("not a directory") }
            val log = ApplicationLog(File(blocked, "application.log"))
            val visible = mutableListOf<String>()
            log.start(this) { visible.addAll(it) }
            try {
                log.info("test", "visible even without a file")
            } finally {
                log.close()
            }
            assertTrue(visible.single().contains("visible even without a file"))
            assertNotNull(log.fileError.value)
        }
}
