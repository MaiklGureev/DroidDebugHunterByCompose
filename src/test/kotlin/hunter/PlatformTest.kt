package hunter

import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.*

class PlatformTest {
    @Test
    fun `runtime manifest references bundled source assets only`() {
        val loader = PlatformTest::class.java.classLoader
        val paths =
            loader
                .getResourceAsStream("runtime-manifest.txt")!!
                .bufferedReader()
                .use { it.readLines() }
                .map { it.trim().removePrefix("\uFEFF") }
                .filter(String::isNotBlank)
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            assertFalse(
                path.contains("__pycache__") || path.endsWith(".pyc") || path.endsWith(".pyo"),
                path,
            )
            assertNotNull(loader.getResource(path), "Missing bundled asset: $path")
        }
    }

    private val windows = HostPlatform(HostOs.Windows, "x64")
    private val mac = HostPlatform(HostOs.MacOS, "arm64")
    private val linux = HostPlatform(HostOs.Linux, "x64")

    private fun <T> temporary(block: (File) -> T): T {
        val root = Files.createTempDirectory("ddhc-platform-").toFile()
        try {
            return block(root)
        } finally {
            check(
                root.canonicalPath.startsWith(
                    File(System.getProperty("java.io.tmpdir")).canonicalPath + File.separator
                )
            )
            root.deleteRecursively()
        }
    }

    @Test
    fun `Darwin is macOS and CPU aliases are normalized`() {
        assertEquals(mac, HostPlatform.detect("Darwin", "aarch64"))
        assertEquals(windows, HostPlatform.detect("Windows 11", "amd64"))
        assertEquals(linux, HostPlatform.detect("Linux", "x86_64"))
        assertFails { HostPlatform.detect("Other", "x86_64") }
    }

    @Test
    fun `data directories use native platform conventions`() = temporary { root ->
        assertEquals(
            File(root, "Library/Application Support/DroidDebugHunterCompose"),
            mac.dataDirectory(root, emptyMap()),
        )
        assertEquals(
            File(root, ".local/share/DroidDebugHunterCompose"),
            linux.dataDirectory(root, emptyMap()),
        )
        assertEquals(
            File(root, "custom/DroidDebugHunterCompose"),
            linux.dataDirectory(root, mapOf("XDG_DATA_HOME" to File(root, "custom").path)),
        )
        assertEquals(
            File(root, ".local/share/DroidDebugHunterCompose"),
            linux.dataDirectory(root, mapOf("XDG_DATA_HOME" to "relative-path")),
        )
        assertEquals(
            File(root, "custom/DroidDebugHunterCompose"),
            windows.dataDirectory(root, mapOf("LOCALAPPDATA" to File(root, "custom").path)),
        )
    }

    @Test
    fun `adb selects a platform binary with spaces and respects overrides`() = temporary { root ->
        val toolRoot = File(root, "tools with spaces")
        val adb =
            File(toolRoot, "platform-tools/adb").apply {
                parentFile.mkdirs()
                writeText("test")
            }
        File(toolRoot, "platform-tools/adb.exe").writeText("windows")
        assertEquals(adb.absolutePath, ToolResolver.adb("", toolRoot, mac, emptyMap(), root))
        assertTrue(ToolResolver.adb("", toolRoot, windows, emptyMap(), root).endsWith("adb.exe"))
        assertEquals(
            "custom-adb",
            ToolResolver.adb("custom-adb", toolRoot, linux, emptyMap(), root),
        )
    }

    @Test
    fun `adb discovers Android SDK and PATH without requiring a shell`() = temporary { root ->
        val sdk = File(root, "sdk")
        val adb =
            File(sdk, "platform-tools/adb").apply {
                parentFile.mkdirs()
                writeText("test")
            }
        assertEquals(
            adb.absolutePath,
            ToolResolver.adb("", root, linux, mapOf("ANDROID_SDK_ROOT" to sdk.path), root),
        )
        val binary =
            File(root, "bin/adb.exe").apply {
                parentFile.mkdirs()
                writeText("test")
            }
        assertEquals(
            binary.absolutePath,
            ToolResolver.adb("", root, windows, mapOf("PATH" to binary.parent), root),
        )
    }

    @Test
    fun `python finds venvs and does not select a Windows interpreter on Unix`() =
        temporary { root ->
            File(root, "python/python.exe").apply {
                parentFile.mkdirs()
                writeText("windows")
            }
            val unix =
                File(root, "python/bin/python3").apply {
                    parentFile.mkdirs()
                    writeText("unix")
                }
            assertEquals(unix.absolutePath, ToolResolver.python("", root, mac, emptyMap()))
            assertTrue(ToolResolver.python("", root, windows, emptyMap()).endsWith("python.exe"))
            assertEquals(
                "chosen-python",
                ToolResolver.python("chosen-python", root, linux, emptyMap()),
            )
        }

    @Test
    fun `platform profiles persist without overwriting other OS settings`() = temporary { root ->
        val file = File(root, "settings.properties")
        Settings(tools = root.path, adb = "adb-win", python = "python-win").save(file, windows)
        Settings(tools = root.path, adb = "adb-mac", python = "python-mac").save(file, mac)
        Settings(tools = root.path, adb = "adb-linux", python = "python-linux").save(file, linux)
        assertEquals("adb-win", Settings.load(file, windows).adb)
        assertEquals("python-mac", Settings.load(file, mac).python)
        assertEquals("adb-linux", Settings.load(file, linux).adb)
    }

    @Test
    fun `legacy Windows paths are not imported on macOS`() = temporary { root ->
        val file = File(root, "settings.properties")
        Properties()
            .apply {
                setProperty("tools", root.path)
                setProperty("adb", "legacy-adb.exe")
                setProperty("python", "python")
            }
            .also { p -> file.outputStream().use { p.store(it, null) } }
        assertEquals("legacy-adb.exe", Settings.load(file, windows).adb)
        assertEquals("", Settings.load(file, windows).python)
        assertEquals("", Settings.load(file, mac).adb)
    }

    @Test
    fun `POSIX curl quotes arguments without expanding shell expressions`() {
        val command =
            Curl.create(
                Exchange(
                    url = "https://example.test/a'b",
                    requestHeaders = listOf(Header("X-Literal", "\$(id)")),
                ),
                CurlShell.Posix,
            )
        assertTrue(command.startsWith("curl "))
        assertTrue(command.contains("a'\\''b"))
        assertTrue(command.contains("'X-Literal: \$(id)'"))
        assertFalse(command.contains("curl.exe"))
    }

    @Test
    fun `POSIX curl represents binary body with portable printf and a temporary file`() {
        val command =
            Curl.create(
                Exchange(
                    method = "POST",
                    url = "https://example.test",
                    request = Body("AA3/gA==", "base64"),
                    hasBody = true,
                ),
                CurlShell.Posix,
            )
        assertTrue(command.contains("\\0000\\0015\\0377\\0200"))
        assertTrue(command.contains("--data-binary \"@\$ddh_file\""))
        assertTrue(command.contains("trap 'rm -f"))
    }
}
