package hunter

import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*

data class SetupProgress(
    val phase: String,
    val detail: String = "",
    val bytes: Long = 0,
    val total: Long? = null,
) {
    val fraction: Float?
        get() =
            total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

/**
 * Only explicit setup actions call this downloader. Downloads and unpacking are bounded and staged.
 */
object ToolDownloads {
    suspend fun metadata(url: String): com.google.gson.JsonObject =
        withContext(Dispatchers.IO) {
            val request =
                HttpRequest.newBuilder(URI(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "DroidDebugHunter-tool-setup")
                    .GET()
                    .build()
            val response = runInterruptible {
                client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            }
            response.body().use { stream ->
                check(response.statusCode() == 200) { "HTTP ${response.statusCode()}: $url" }
                val bytes = runInterruptible { stream.readNBytes(4 * 1024 * 1024 + 1) }
                require(bytes.size <= 4 * 1024 * 1024) { "Metadata exceeds size limit" }
                parseObject(bytes.toString(Charsets.UTF_8))
            }
        }

    private const val MAX_DOWNLOAD = 128L * 1024 * 1024
    private const val MAX_UNPACK = 512L * 1024 * 1024
    private val client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()

    fun digest(file: File): String =
        file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        }

    suspend fun download(
        url: String,
        destination: File,
        expected: String?,
        offline: Boolean,
        progress: (SetupProgress) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            if (destination.isFile && (expected == null || digest(destination) == expected)) {
                progress(SetupProgress(tr("Using cached download"), destination.absolutePath))
                return@withContext destination
            }
            check(!offline) {
                tr("Offline file missing or checksum differs: {0}", destination.absolutePath)
            }
            destination.parentFile.mkdirs()
            val partial =
                Files.createTempFile(destination.parentFile.toPath(), ".ddhc-download-", ".part")
                    .toFile()
            try {
                val request =
                    HttpRequest.newBuilder(URI(url))
                        .timeout(Duration.ofMinutes(3))
                        .header("User-Agent", "DroidDebugHunter-tool-setup")
                        .GET()
                        .build()
                progress(SetupProgress(tr("Downloading"), url))
                val response = runInterruptible {
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                }
                response.body().use { source ->
                    check(response.statusCode() == 200) { "HTTP ${response.statusCode()}: $url" }
                    val total =
                        response.headers().firstValueAsLong("Content-Length").orElse(-1).takeIf {
                            it >= 0
                        }
                    require(total == null || total <= MAX_DOWNLOAD) {
                        tr("Download exceeds size limit")
                    }
                    var count = 0L
                    var previous = 0L
                    partial.outputStream().use { target ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            ensureActive()
                            val n = runInterruptible { source.read(buffer) }
                            if (n < 0) break
                            count += n
                            require(count <= MAX_DOWNLOAD) { tr("Download exceeds size limit") }
                            target.write(buffer, 0, n)
                            val now = System.nanoTime()
                            if (now - previous > 100_000_000) {
                                progress(
                                    SetupProgress(tr("Downloading"), destination.name, count, total)
                                )
                                previous = now
                            }
                        }
                    }
                    check(total == null || total == count) { tr("Download is incomplete") }
                    if (expected != null && digest(partial) != expected)
                        throw SecurityException(
                            tr("Download checksum mismatch; existing files preserved")
                        )
                    ensureActive()
                    Files.move(
                        partial.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    progress(
                        SetupProgress(tr("Download completed"), destination.name, count, total)
                    )
                }
            } finally {
                partial.delete()
            }
            destination
        }

    fun safeEntry(root: File, name: String): File {
        require(
            name.isNotBlank() &&
                !name.startsWith('/') &&
                !name.contains('\\') &&
                !name.contains(':') &&
                name.split('/').none { it == ".." }
        ) {
            "Unsafe archive entry: $name"
        }
        val target = File(root, name).canonicalFile
        require(target.toPath().startsWith(root.canonicalFile.toPath())) {
            "Archive escaped staging directory"
        }
        return target
    }

    fun unzip(archive: File, stage: File) {
        var count = 0L
        ZipInputStream(archive.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = safeEntry(stage, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile.mkdirs()
                target.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= MAX_UNPACK) { "Archive exceeds size limit" }
                        output.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    // uv's tarballs contain regular executables. Never materialize links, devices or PAX paths.
    fun untarUv(archive: File, stage: File) {
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            var total = 0L
            var entries = 0
            while (true) {
                val header = input.readNBytes(512)
                if (header.isEmpty() || header.all { it == 0.toByte() }) break
                require(header.size == 512 && ++entries <= 100) { "Invalid tar header" }
                fun field(start: Int, length: Int) =
                    header
                        .copyOfRange(start, start + length)
                        .toString(Charsets.UTF_8)
                        .substringBefore('\u0000')
                        .trim()
                val prefix = field(345, 155)
                val name = (if (prefix.isBlank()) "" else "$prefix/") + field(0, 100)
                val target = safeEntry(stage, name)
                val size = field(124, 12).toLongOrNull(8) ?: error("Invalid tar size")
                total += size
                require(size >= 0 && total <= MAX_UNPACK) { "Tar exceeds size limit" }
                val type = header[156].toInt()
                require(type in listOf(0, '0'.code, '5'.code)) { "Unsupported tar entry" }
                if (type == '5'.code) target.mkdirs()
                else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { output -> copyExactly(input, output, size) }
                }
                copyExactly(input, null, (512 - size % 512) % 512)
            }
        }
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream?, size: Long) {
        var remaining = size
        val buffer = ByteArray(65536)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            require(n > 0) { "Truncated tar archive" }
            output?.write(buffer, 0, n)
            remaining -= n
        }
    }

    fun <T> staging(parent: File, block: (File) -> T): T {
        parent.mkdirs()
        val stage =
            Files.createTempDirectory(parent.toPath(), ".ddhc-stage-").toFile().canonicalFile
        try {
            return block(stage)
        } finally {
            require(
                stage.parentFile == parent.canonicalFile && stage.name.startsWith(".ddhc-stage-")
            )
            stage.deleteRecursively()
        }
    }

    /** Retain the previous installation if an executable is locked or a copy fails. */
    fun replaceDirectory(stage: File, destination: File) {
        require(
            !Files.isSymbolicLink(destination.toPath()) &&
                destination.canonicalFile ==
                    File(destination.parentFile.canonicalFile, destination.name)
        ) {
            tr(
                "The component directory links to another location. Choose another tools folder or configure its executable path."
            )
        }
        val backup = File(destination.parentFile, ".ddhc-backup-${UUID.randomUUID()}").canonicalFile
        require(backup.parentFile == destination.parentFile.canonicalFile)
        val hadOriginal = destination.exists()
        if (hadOriginal) Files.move(destination.toPath(), backup.toPath())
        try {
            Files.move(stage.toPath(), destination.toPath())
        } catch (e: Exception) {
            if (hadOriginal) Files.move(backup.toPath(), destination.toPath())
            throw e
        }
        if (hadOriginal) runCatching { deleteOwnedDirectory(backup) }
    }

    /** Never follow a symlink or Windows junction while cleaning an owned temporary tree. */
    fun deleteOwnedDirectory(root: File) {
        val path = root.absoluteFile.toPath().normalize()
        require(!Files.isSymbolicLink(path) && root.canonicalFile.toPath() == path)
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir.toRealPath() != dir.toAbsolutePath().normalize())
                        throw java.io.IOException("Linked directory preserved: $dir")
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    suspend fun installAdb(config: Settings, progress: (SetupProgress) -> Unit): String =
        withContext(Dispatchers.IO) {
            val platform = HostPlatform.current
            val version = config.setup.version("adb")
            val suffix =
                when (platform.os) {
                    HostOs.Windows -> "win"
                    HostOs.MacOS -> "darwin"
                    HostOs.Linux -> "linux"
                }
            val name = "platform-tools_r$version-$suffix.zip"
            val cache =
                File(config.setup.cache.ifBlank { File(config.tools, "downloads").absolutePath })
            val expected =
                if (version == ToolPolicy.target("adb"))
                    ToolPolicy.catalog.getAsJsonObject("adbSha256").get(platform.os.id).asString
                else null
            val archive =
                if (config.setup.adbArchive.isNotBlank())
                    File(config.setup.adbArchive).also {
                        require(it.isFile) { tr("File not found: {0}", it.path) }
                    }
                else
                    download(
                        "https://dl.google.com/android/repository/$name",
                        File(cache, "adb/$name"),
                        expected,
                        config.setup.offline,
                        progress,
                    )
            progress(SetupProgress(tr("Unpacking and validating ADB"), archive.absolutePath))
            val output = File(config.tools, "platform-tools")
            val context = currentCoroutineContext()
            staging(File(config.tools)) { stage ->
                unzip(archive, stage)
                val tools = File(stage, "platform-tools")
                val required =
                    listOf(platform.executable("adb"), "NOTICE.txt", "source.properties") +
                        if (platform.os == HostOs.Windows)
                            listOf("AdbWinApi.dll", "AdbWinUsbApi.dll")
                        else emptyList()
                required.forEach {
                    require(File(tools, it).isFile) { "Missing Platform Tools file: $it" }
                }
                val binary = File(tools, platform.executable("adb"))
                val header = binary.inputStream().use { it.readNBytes(20) }
                require(
                    header.size == 20 &&
                        when (platform.os) {
                            HostOs.Windows -> header[0] == 77.toByte() && header[1] == 90.toByte()
                            HostOs.Linux ->
                                header.take(4) ==
                                    listOf(127.toByte(), 69.toByte(), 76.toByte(), 70.toByte()) &&
                                    ((header[18].toInt() and 255) or
                                        ((header[19].toInt() and 255) shl 8)) ==
                                        if (platform.arch == "arm64") 183 else 62
                            HostOs.MacOS ->
                                header.take(4).map { it.toInt() and 255 } in
                                    listOf(
                                        listOf(202, 254, 186, 190),
                                        listOf(207, 250, 237, 254),
                                        listOf(202, 254, 186, 191),
                                    )
                        }
                ) {
                    tr(
                        "ADB binary does not match this OS/CPU; configure a native ADB path instead."
                    )
                }
                if (platform.os != HostOs.Windows)
                    listOf("adb", "fastboot").forEach {
                        File(tools, it).takeIf(File::isFile)?.setExecutable(true)
                    }
                val revision =
                    Regex("(?m)^Pkg.Revision\\s*=\\s*(\\S+)")
                        .find(File(tools, "source.properties").readText())
                        ?.groupValues
                        ?.get(1)
                require(revision == version) {
                    tr(
                        "Archive version is {0}; selected {1}. Choose that version or another archive.",
                        revision,
                        version,
                    )
                }
                context.ensureActive()
                replaceDirectory(tools, output)
            }
            File(output, platform.executable("adb")).absolutePath
        }
}
