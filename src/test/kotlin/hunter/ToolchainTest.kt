package hunter

import com.sun.net.httpserver.HttpServer
import hunter.core.HostOs
import hunter.core.HostPlatform
import hunter.core.tr
import hunter.features.settings.Settings
import hunter.features.tools.SetupMethod
import hunter.features.tools.SetupProgress
import hunter.features.tools.ToolDownloads
import hunter.features.tools.ToolLayout
import hunter.features.tools.ToolPolicy
import hunter.features.tools.ToolProvisioner
import hunter.features.tools.ToolSetupOptions
import hunter.features.tools.ToolStepState
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir

class ToolchainTest {
    @Test
    fun `latest Platform Tools uses the official stable URL for each host`() {
        val pinned = ToolSetupOptions()
        val latest = pinned.copy(versions = pinned.versions + ("adb" to ToolPolicy.ADB_LATEST))
        ToolPolicy.validate(latest.versions)
        assertFalse(ToolPolicy.validDownloadVersion("python", ToolPolicy.ADB_LATEST))
        for ((os, suffix) in mapOf(HostOs.Windows to "windows", HostOs.MacOS to "darwin", HostOs.Linux to "linux")) {
            val platform = HostPlatform(os, "x64")
            assertEquals("https://dl.google.com/android/repository/platform-tools-latest-$suffix.zip",
                ToolLayout.adbSource(latest, platform).url)
            val versionedSuffix = if (os == HostOs.Windows) "win" else suffix
            assertTrue(ToolLayout.adbSource(pinned, platform).url.endsWith("-$versionedSuffix.zip"))
            assertNull(ToolLayout.adbChecksum(latest, platform))
            assertNotNull(ToolLayout.adbChecksum(pinned, platform))
        }
    }

    @Test
    fun `debug tool sources match macOS and Linux CPU`() {
        val config = Settings()
        val hosts = mapOf(
            HostPlatform(HostOs.MacOS, "x64") to "x86_64-apple-darwin",
            HostPlatform(HostOs.MacOS, "arm64") to "aarch64-apple-darwin",
            HostPlatform(HostOs.Linux, "x64") to "x86_64-unknown-linux-gnu",
            HostPlatform(HostOs.Linux, "arm64") to "aarch64-unknown-linux-gnu",
        )
        for ((platform, uvTarget) in hosts) {
            val pythonSources = ToolLayout.sources(config, listOf("python"), platform)
            assertEquals("uv-$uvTarget.tar.gz", pythonSources.single(ToolLayout::isArchive).url.substringAfterLast('/'))
        }
        val macIntel = HostPlatform(HostOs.MacOS, "x64")
        val macArm = HostPlatform(HostOs.MacOS, "arm64")
        assertEquals(ToolLayout.adbSource(config.setup, macIntel).url, ToolLayout.adbSource(config.setup, macArm).url)
        val linuxArm = HostPlatform(HostOs.Linux, "arm64")
        assertFailsWith<IllegalArgumentException> { ToolLayout.adbSource(config.setup, linuxArm) }
        val linuxArmSource = ToolLayout.sources(config, listOf("adb"), linuxArm).single()
        assertFalse(ToolLayout.isArchive(linuxArmSource))
        assertTrue(linuxArmSource.url.endsWith("/arm64/adb"))
        assertEquals(ToolLayout.sources(config, listOf("server", "gadget"), macIntel),
            ToolLayout.sources(config, listOf("server", "gadget"), linuxArm))
    }

    @Test
    fun `ADB headers distinguish native ELF and thin or universal Mach-O`() {
        for (os in listOf(HostOs.MacOS, HostOs.Linux)) {
            val intel = HostPlatform(os, "x64")
            val arm = HostPlatform(os, "arm64")
            assertTrue(ToolDownloads.adbBinaryMatches(adbHeader(intel), intel))
            assertTrue(ToolDownloads.adbBinaryMatches(adbHeader(arm), arm))
            assertFalse(ToolDownloads.adbBinaryMatches(adbHeader(intel), arm))
            assertFalse(ToolDownloads.adbBinaryMatches(adbHeader(arm), intel))
        }
        val fat = ByteBuffer.allocate(48).putInt(0xcafebabeu.toInt()).putInt(2)
            .putInt(8, 0x01000007).putInt(28, 0x0100000c).array()
        for (arch in listOf("x64", "arm64")) {
            val mac = HostPlatform(HostOs.MacOS, arch)
            assertTrue(ToolDownloads.adbBinaryMatches(fat, mac))
            assertFalse(ToolDownloads.adbBinaryMatches(fat.copyOf(28), mac))
        }
    }

    private fun adbHeader(platform: HostPlatform): ByteArray {
        val header = ByteBuffer.allocate(20)
        when (platform.os) {
            HostOs.Windows -> header.putShort(0x4d5a)
            HostOs.Linux -> header.putInt(0x7f454c46).put(2).put(1).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(18, if (platform.arch == "arm64") 183 else 62)
            HostOs.MacOS -> header.order(ByteOrder.LITTLE_ENDIAN).putInt(0xfeedfacfu.toInt())
                .putInt(if (platform.arch == "arm64") 0x0100000c else 0x01000007)
        }
        return header.array()
    }

    @Test
    fun `latest Platform Tools accepts the ZIP revision from the offline cache`(@TempDir dir: Path) =
        runBlocking {
            val root = dir.toFile()
            val platform = HostPlatform.current
            var options = ToolSetupOptions(offline = true).let {
                it.copy(versions = it.versions + ("adb" to ToolPolicy.ADB_LATEST))
            }
            val nativeZip = !ToolLayout.hasGoogleAdbArchive(platform)
            val name = if (nativeZip) "native.zip" else ToolLayout.adbSource(options, platform).url.substringAfterLast('/')
            val archive = File(root, "downloads/adb/$name").apply {
                parentFile.mkdirs()
            }
            val executable = platform.executable("adb")
            val header = adbHeader(platform)
            val entries = mutableMapOf(
                executable to header,
                "NOTICE.txt" to "fixture".toByteArray(),
                "source.properties" to "Pkg.Revision=38.0.0\n".toByteArray(),
            )
            if (platform.os == HostOs.Windows) {
                entries["AdbWinApi.dll"] = byteArrayOf(1)
                entries["AdbWinUsbApi.dll"] = byteArrayOf(1)
            }
            ZipOutputStream(archive.outputStream()).use { zip ->
                for ((name, bytes) in entries) {
                    zip.putNextEntry(ZipEntry("platform-tools/$name"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            if (nativeZip) options = options.copy(adbArchive = archive.path)
            val config = Settings(tools = root.path, setup = options)
            val installed = File(ToolDownloads.installAdb(config) {})
            assertEquals(File(root, "platform-tools/$executable"), installed)
            assertEquals("Pkg.Revision=38.0.0\n", File(installed.parentFile, "source.properties").readText())
            val pinned = config.copy(setup = ToolSetupOptions(offline = true, adbArchive = archive.path))
            assertFailsWith<IllegalArgumentException> { ToolDownloads.installAdb(pinned) {} }
            assertContentEquals(header, installed.readBytes())
        }

    @Test
    fun `a latest download refreshes the cache when online and uses it offline`(@TempDir dir: Path) =
        runBlocking {
            val destination = dir.resolve("platform-tools-latest.zip").toFile().apply { writeText("old ZIP") }
            val newContents = "new ZIP".toByteArray()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/archive") { response ->
                response.sendResponseHeaders(200, newContents.size.toLong())
                response.responseBody.use { it.write(newContents) }
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}/archive"
                ToolDownloads.download(url, destination, null, offline = true, refresh = true) {}
                assertEquals("old ZIP", destination.readText())
                ToolDownloads.download(url, destination, null, offline = false, refresh = true) {}
                assertContentEquals(newContents, destination.readBytes())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `offline preparation continues with an independent APK after ADB and Python fail`(
        @TempDir dir: Path,
    ) = runBlocking {
        val root = dir.toFile()
        val filename = "com.wireguard.android-${ToolPolicy.target("wireguard")}.apk"
        val cached = File(root, "downloads/wireguard/$filename").apply { parentFile.mkdirs() }
        ZipOutputStream(cached.outputStream()).use { zip ->
            for (entry in listOf("AndroidManifest.xml", "classes.dex")) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        val config = Settings(
            tools = root.path,
            adb = File(root, "unavailable-adb").path,
            python = File(root, "unavailable-python").path,
            setup = ToolSetupOptions(
                method = SetupMethod.Target,
                offline = true,
                adbArchive = File(root, "missing-platform-tools.zip").path,
            ),
        )
        val result = ToolProvisioner.prepare(config, listOf("adb", "python", "proxy", "wireguard"), {}, {})
        assertEquals(
            mapOf("adb" to ToolStepState.Failed, "python" to ToolStepState.Failed,
                "proxy" to ToolStepState.Skipped, "wireguard" to ToolStepState.Prepared),
            result.steps.associate { it.component to it.state },
        )
        assertEquals(config.python, result.config.python)
        assertContentEquals(
            cached.readBytes(),
            assertNotNull(ToolLayout.wireGuardApk(result.config)).readBytes(),
        )
    }

    @Test
    fun `replacing a component never deletes files behind a nested directory link`(
        @TempDir dir: Path
    ) {
        val outside =
            dir.resolve("outside").toFile().apply {
                mkdir()
                resolve("keep.txt").writeText("preserve")
            }
        val destination = dir.resolve("platform-tools").toFile().apply { mkdir() }
        try {
            java.nio.file.Files.createSymbolicLink(
                destination.resolve("linked").toPath(),
                outside.toPath(),
            )
        } catch (e: java.io.IOException) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                false,
                "Symlink creation is unavailable: ${e.message}",
            )
        }
        val stage =
            dir.resolve("stage").toFile().apply {
                mkdir()
                resolve("adb").writeText("new")
            }
        ToolDownloads.replaceDirectory(stage, destination)
        assertEquals("preserve", outside.resolve("keep.txt").readText())
        assertEquals("new", destination.resolve("adb").readText())
    }

    @Test
    fun `capture and proxy use separate package folders for alternative versions`(
        @TempDir dir: Path
    ) {
        val root = dir.toFile()
        File(root, "frida-python").mkdir()
        File(root, "proxy-python").mkdir()
        val config = Settings(tools = root.path)
        assertEquals(
            File(root, "frida-python").absolutePath,
            config.environment("capture")["PYTHONPATH"],
        )
        assertEquals(
            File(root, "proxy-python").absolutePath,
            config.environment("proxy")["PYTHONPATH"],
        )
        assertEquals(2, config.environment().getValue("PYTHONPATH").split(File.pathSeparator).size)
    }

    @Test
    fun `offline Frida download reuses local archives without querying release metadata`(
        @TempDir dir: Path
    ) = runBlocking {
        val config = Settings(tools = dir.toString(), setup = ToolSetupOptions(offline = true))
        val source = ToolLayout.sources(config, "server").first()
        val cache =
            File(
                dir.toFile(),
                "downloads/frida/${java.net.URI(source.url).path.substringAfterLast('/')}",
            )
        cache.parentFile.mkdirs()
        cache.writeText("local archive")
        assertEquals(cache, ToolLayout.download(config, source) {})
        cache.delete()
        assertFailsWith<IllegalStateException> { ToolLayout.download(config, source) {} }
    }

    @Test
    fun `a configured missing tools directory is preserved and created on startup`(
        @TempDir dir: Path
    ) {
        val tools = dir.resolve("not-created-yet").toFile()
        val file = dir.resolve("settings.properties").toFile()
        Settings(tools = tools.path).save(file)
        val loaded = Settings.load(file)
        assertEquals(tools.path, loaded.tools)
        assertTrue(ToolLayout.ensure(File(loaded.tools)).isEmpty())
        assertTrue(File(tools, "downloads/frida").isDirectory)
    }

    @Test
    fun `tool folders are created idempotently and failures remain nonfatal`(@TempDir dir: Path) {
        val root = dir.resolve("tools").toFile()
        assertTrue(ToolLayout.ensure(root).isEmpty())
        assertTrue(ToolLayout.ensure(root).isEmpty())
        ToolLayout.folders.forEach { assertTrue(File(root, it).isDirectory, it) }
        val blocked = dir.resolve("file").toFile().apply { writeText("preserve") }
        assertTrue(ToolLayout.ensure(blocked).isNotEmpty())
        assertEquals("preserve", blocked.readText())
    }

    @Test
    fun `Frida bundle links honor selected versions ABI and file placement`(@TempDir dir: Path) {
        val config =
            Settings(
                tools = dir.toString(),
                setup =
                    ToolSetupOptions(versions = ToolSetupOptions().versions + ("frida" to "16.5.9")),
            )
        val sources = ToolLayout.sources(config, "frida")
        assertEquals(10, sources.size)
        assertEquals(8, sources.count(ToolLayout::isArchive))
        assertEquals(4, sources.count { it.folder == "frida-server" })
        assertEquals(4, sources.count { it.folder == "frida-gadget" })
        assertTrue(
            sources.filter(ToolLayout::isArchive).all {
                "/16.5.9/" in it.url && it.url.endsWith(".xz")
            }
        )
        assertTrue(sources.all { it.url.startsWith("https://") })
        assertTrue(ToolLayout.structure(config).contains("frida-server-16.5.9-android-<ABI>"))
        assertEquals(1, ToolLayout.sources(config, "adb").size)
    }

    @Test
    fun `cancelled download leaves no partial or published file`(@TempDir dir: Path) = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") { response ->
            try {
                response.sendResponseHeaders(200, 16_000_000)
                response.responseBody.use { out ->
                    repeat(1000) {
                        out.write(ByteArray(16000))
                        out.flush()
                        Thread.sleep(10)
                    }
                }
            } catch (_: Exception) {
                response.close()
            }
        }
        server.start()
        try {
            val started = CompletableDeferred<Unit>()
            val destination = dir.resolve("file.zip").toFile()
            val job = launch {
                ToolDownloads.download(
                    "http://127.0.0.1:${server.address.port}/slow",
                    destination,
                    null,
                    false,
                ) {
                    if (it.bytes > 0) started.complete(Unit)
                }
            }
            withTimeout(5000) { started.await() }
            withTimeout(5000) { job.cancelAndJoin() }
            assertFalse(destination.exists())
            assertFalse(dir.toFile().listFiles()!!.any { it.extension == "part" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `older newer and prerelease versions are allowed but command expressions are rejected`() {
        for (value in listOf("3.12.0", "3.16.0rc1", "16.5.9", "18.0.0", "1.0.20260315")) assertTrue(
            ToolPolicy.validVersion(value),
            value,
        )
        for (value in
            listOf(
                "--upgrade",
                "latest",
                "17;echo",
                "1.0/../../",
                "https://x",
                "1.2\n",
                "1.0 --index-url x",
                "",
                "a".repeat(100),
            )) assertFalse(ToolPolicy.validVersion(value), value)
        for (value in listOf("16.5.9", "18.0.0")) {
            ToolPolicy.validate(ToolSetupOptions().versions + ("frida" to value))
            assertNotEquals(tr("Target version"), ToolPolicy.notice("frida", value))
        }
        assertEquals(tr("Target version"), ToolPolicy.notice("frida", ToolPolicy.target("frida")))
        assertNotEquals(tr("Target version"), ToolPolicy.notice("python", "3.14.0"))
    }

    @Test
    fun `ADB package revision is distinct from protocol version`() {
        assertEquals(
            "37.0.3",
            ToolPolicy.adbVersion(
                "Android Debug Bridge version 1.0.41\nVersion 37.0.3-123\nInstalled as /bin/adb"
            ),
        )
        assertNull(ToolPolicy.adbVersion("Android Debug Bridge version 1.0.41"))
    }

    @Test
    fun `setup options and custom versions persist separately per platform`(@TempDir dir: Path) {
        val file = dir.resolve("settings.properties").toFile()
        val options =
            ToolSetupOptions(
                method = SetupMethod.Venv,
                offline = true,
                cache = "cache with spaces",
                adbArchive = "local.zip",
                uv = "uv-custom",
                versions = ToolSetupOptions().versions + ("frida" to "18.0.0"),
            )
        Settings(tools = dir.toString(), setup = options)
            .save(file, HostPlatform(HostOs.Windows, "x64"))
        file.appendText("tools.windows-x64.setup.mode=Manual\ntools.windows-x64.setup.chosen=true\n")
        Settings(tools = dir.toString()).save(file, HostPlatform(HostOs.MacOS, "arm64"))
        assertEquals(options, Settings.load(file, HostPlatform(HostOs.Windows, "x64")).setup)
        assertEquals(
            ToolSetupOptions(),
            Settings.load(file, HostPlatform(HostOs.MacOS, "arm64")).setup,
        )
    }

    @Test
    fun `an invalid tools folder is rejected without replacing it`(@TempDir dir: Path) {
        val blocked = dir.resolve("a-file").toFile().apply { writeText("not a directory") }
        assertFailsWith<IllegalStateException> { ToolProvisioner.writableRoot(blocked) }
        assertEquals("not a directory", blocked.readText())
    }

    @Test
    fun `archive paths cannot escape on either Windows or Unix`(@TempDir dir: Path) {
        val root = dir.toFile()
        for (name in
            listOf(
                "../outside",
                "/tmp/outside",
                "C:/outside",
                "x\\..\\outside",
                "a/../../outside",
            )) {
            assertFailsWith<IllegalArgumentException>(name) { ToolDownloads.safeEntry(root, name) }
        }
        assertEquals(
            File(root, "uv/uv.exe").canonicalFile,
            ToolDownloads.safeEntry(root, "uv/uv.exe"),
        )
    }

    @Test
    fun `staging keeps the original installation when extraction fails`(@TempDir dir: Path) {
        val root = dir.toFile()
        val original =
            File(root, "platform-tools/adb").apply {
                parentFile.mkdirs()
                writeText("working")
            }
        val archive = File(root, "bad.zip")
        ZipOutputStream(archive.outputStream()).use { out ->
            for ((name, bytes) in mapOf("platform-tools/adb" to "new", "../escape" to "bad")) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes.toByteArray())
                out.closeEntry()
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ToolDownloads.staging(root) { ToolDownloads.unzip(archive, it) }
        }
        assertEquals("working", original.readText())
        assertFalse(File(root.parentFile, "escape").exists())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".ddhc-stage-") })
    }

    @Test
    fun `uv tar extraction supports regular binaries and rejects links`(@TempDir dir: Path) {
        val root = dir.toFile()
        fun archive(type: Char): File {
            val file = File(root, "uv-$type.tar.gz")
            val header = ByteArray(512)
            "uv/uv".toByteArray().copyInto(header)
            "00000000003\u0000".toByteArray().copyInto(header, 124)
            header[156] = type.code.toByte()
            GZIPOutputStream(file.outputStream()).use {
                it.write(header)
                it.write("bin".toByteArray())
                it.write(ByteArray(509))
                it.write(ByteArray(1024))
            }
            return file
        }
        ToolDownloads.staging(root) { stage ->
            ToolDownloads.untarUv(archive('0'), stage)
            assertEquals("bin", File(stage, "uv/uv").readText())
        }
        assertFailsWith<IllegalArgumentException> {
            ToolDownloads.staging(root) { ToolDownloads.untarUv(archive('2'), it) }
        }
    }

    @Test
    fun `download verifies hashes reports bytes and preserves old cache on mismatch`(
        @TempDir dir: Path
    ) = runBlocking {
        val bytes = ByteArray(160_000) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { response ->
            response.sendResponseHeaders(200, bytes.size.toLong())
            response.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val destination = dir.resolve("cached.zip").toFile()
            val source = dir.resolve("expected").toFile().apply { writeBytes(bytes) }
            val progress = mutableListOf<SetupProgress>()
            val url = "http://127.0.0.1:${server.address.port}/file"
            ToolDownloads.download(
                url,
                destination,
                ToolDownloads.digest(source),
                false,
                progress = progress::add,
            )
            assertContentEquals(bytes, destination.readBytes())
            assertEquals(bytes.size.toLong(), progress.last().bytes)
            assertEquals(1f, progress.last().fraction)
            destination.writeText("keep")
            assertFailsWith<SecurityException> {
                ToolDownloads.download(url, destination, "0".repeat(64), false) {}
            }
            assertEquals("keep", destination.readText())
            assertFalse(dir.toFile().listFiles()!!.any { it.extension == "part" })
            assertFailsWith<IllegalStateException> {
                ToolDownloads.download(
                    "http://127.0.0.1:1/never",
                    destination,
                    "0".repeat(64),
                    true,
                ) {}
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `progress remains indeterminate without a valid total`() {
        assertNull(SetupProgress("step").fraction)
        assertNull(SetupProgress("step", bytes = 20, total = 0).fraction)
        assertEquals(.5f, SetupProgress("step", bytes = 50, total = 100).fraction)
        assertEquals(1f, SetupProgress("step", bytes = 120, total = 100).fraction)
    }

    @Test
    fun `viewing download instructions does not create files`(@TempDir dir: Path) {
        val config = Settings(tools = dir.resolve("must-not-exist").toString())
        assertTrue(ToolProvisioner.plan(config, listOf("all")).contains(config.tools))
        assertTrue(ToolLayout.sources(config, "adb").isNotEmpty())
        assertFalse(File(config.tools).exists())
    }
}
