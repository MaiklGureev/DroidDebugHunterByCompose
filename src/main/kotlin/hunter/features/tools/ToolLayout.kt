package hunter.features.tools

import hunter.core.HostOs
import hunter.core.HostPlatform
import hunter.core.tr
import hunter.features.settings.Settings
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ToolSource(
    val label: String,
    val url: String,
    val folder: String,
    val formatKey: String,
)

object ToolLayout {
    fun isArchive(source: ToolSource) =
        listOf(".zip", ".xz", ".apk", ".tar.gz").any { URI(source.url).path.endsWith(it) }

    suspend fun download(
        config: Settings,
        source: ToolSource,
        progress: (SetupProgress) -> Unit,
    ): File {
        require(config.tools.isNotBlank()) { tr("Choose a tool directory first.") }
        require(source in sources(config, "all") && isArchive(source))
        val name = URI(source.url).path.substringAfterLast('/')
        val subdirectory =
            when (source.folder) {
                "platform-tools" -> "adb"
                "frida-server",
                "frida-gadget" -> "frida"
                "android-tools" -> "wireguard"
                else -> "uv"
            }
        var expected: String? = null
        if (subdirectory == "frida" && !config.setup.offline) {
            progress(SetupProgress(tr("Checking official download metadata"), source.label))
            val version = config.setup.version("frida")
            val release =
                ToolDownloads.metadata(
                    "https://api.github.com/repos/frida/frida/releases/tags/$version"
                )
            check(release.get("tag_name").asString == version)
            val asset =
                release
                    .getAsJsonArray("assets")
                    .map { it.asJsonObject }
                    .firstOrNull { it.get("name").asString == name }
                    ?: error(tr("Download not available: {0}", name))
            require(asset.get("browser_download_url").asString == source.url)
            expected =
                asset.get("digest")?.takeUnless { it.isJsonNull }?.asString?.removePrefix("sha256:")
            check(expected != null && expected.matches(Regex("[a-fA-F0-9]{64}"))) {
                tr(
                    "No official SHA-256 available. Open the download link and verify the archive manually."
                )
            }
        } else if (
            subdirectory == "adb" && config.setup.version("adb") == ToolPolicy.target("adb")
        ) {
            expected =
                ToolPolicy.catalog
                    .getAsJsonObject("adbSha256")
                    .get(HostPlatform.current.os.id)
                    .asString
        } else if (subdirectory == "uv")
            expected =
                ToolPolicy.catalog
                    .getAsJsonObject("uvAssets")
                    .getAsJsonArray(HostPlatform.current.key)[1]
                    .asString
        val cache =
            File(config.setup.cache.ifBlank { File(config.tools, "downloads").absolutePath })
        return ToolDownloads.download(
            source.url,
            File(cache, "$subdirectory/$name"),
            expected,
            config.setup.offline,
            progress,
        )
    }

    /** WireGuard is a standalone APK. Preparing it does not depend on Python or pip. */
    fun wireGuardApk(config: Settings): File? =
        File(config.tools, folder("wireguard")).listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith("com.wireguard.android-") && it.extension == "apk" }
            ?.sortedByDescending { it.name == "com.wireguard.android-${config.setup.version("wireguard")}.apk" }
            ?.firstOrNull {
                runCatching {
                    ZipFile(it).use { apk ->
                        apk.getEntry("AndroidManifest.xml") != null && apk.getEntry("classes.dex") != null
                    }
                }.getOrDefault(false)
            }

    suspend fun prepareWireGuard(config: Settings, progress: (SetupProgress) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val source = sources(config, "wireguard").single()
            val filename = URI(source.url).path.substringAfterLast('/')
            val target = File(File(config.tools, source.folder), filename)
            val cached = if (target.isFile) target else download(config, source, progress)
            progress(SetupProgress(tr("Checking APK"), filename))
            val context = coroutineContext
            ZipFile(cached).use { apk ->
                fun invalid(): Nothing = error(tr("Invalid WireGuard APK: {0}", cached.absolutePath))
                if (apk.getEntry("AndroidManifest.xml") == null || apk.getEntry("classes.dex") == null)
                    invalid()
                val buffer = ByteArray(64 * 1024)
                var totalBytes = 0L
                for (entry in apk.entries().asSequence().filterNot { it.isDirectory }) {
                    val crc = CRC32()
                    apk.getInputStream(entry).use { stream ->
                        while (true) {
                            context.ensureActive()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            if (totalBytes > 256L * 1024 * 1024) invalid()
                            crc.update(buffer, 0, count)
                        }
                    }
                    if (crc.value != entry.crc) invalid()
                }
            }
            if (cached != target) {
                ToolDownloads.staging(target.parentFile) { stage ->
                    val staged = File(stage, filename)
                    Files.copy(cached.toPath(), staged.toPath())
                    context.ensureActive()
                    Files.move(staged.toPath(), target.toPath())
                }
            }
        }

    val folders =
        listOf(
            "platform-tools",
            "frida-server",
            "frida-gadget",
            "frida-python",
            "proxy-python",
            "android-tools",
            ".venvs",
            "python-managed",
            "downloads/adb",
            "downloads/frida",
            "downloads/wireguard",
            "downloads/uv",
            "downloads/wheelhouse/capture",
            "downloads/wheelhouse/proxy",
        )

    /** Best effort only: a read-only tools directory must never prevent startup. */
    fun ensure(root: File): List<String> {
        if (root.path.isBlank()) return listOf(tr("Choose a tool directory first."))
        val warnings = mutableListOf<String>()
        for (folder in folders) {
            try {
                val directory = File(root, folder)
                check(directory.isDirectory || directory.mkdirs()) {
                    tr("Could not create directory: {0}", directory.absolutePath)
                }
            } catch (e: Exception) {
                warnings += e.message ?: folder
            }
        }
        return warnings
    }

    fun folder(component: String): String =
        when (component) {
            "adb" -> "platform-tools"
            "python" -> ".venvs"
            "capture" -> "frida-python"
            "server" -> "frida-server"
            "gadget" -> "frida-gadget"
            "proxy" -> "proxy-python"
            "wireguard" -> "android-tools"
            else -> ""
        }

    fun sources(config: Settings, component: String): List<ToolSource> =
        sources(config, listOf(component))

    fun sources(config: Settings, components: List<String>): List<ToolSource> {
        ToolPolicy.validate(config.setup.versions)
        val versions = config.setup
        val selected = ToolProvisioner.expand(components)
        val result = mutableListOf<ToolSource>()
        for (id in selected) {
            when (id) {
                "adb" -> {
                    val suffix =
                        when (HostPlatform.current.os) {
                            HostOs.Windows -> "win"
                            HostOs.MacOS -> "darwin"
                            HostOs.Linux -> "linux"
                        }
                    result +=
                        ToolSource(
                            "Platform Tools ${versions.version("adb")} · ${HostPlatform.current.label}",
                            "https://dl.google.com/android/repository/platform-tools_r${versions.version("adb")}-$suffix.zip",
                            "platform-tools",
                            "Extract the platform-tools folder from ZIP; retain all libraries and notices.",
                        )
                }
                "server",
                "gadget" -> {
                    val version = versions.version("frida")
                    for (abi in listOf("arm64", "arm", "x86_64", "x86")) {
                        val file =
                            "frida-$id-$version-android-$abi" + if (id == "gadget") ".so" else ""
                        result +=
                            ToolSource(
                                file + ".xz",
                                "https://github.com/frida/frida/releases/download/$version/$file.xz",
                                folder(id),
                                "Unpack .xz here. Keep the extracted filename; do not rename the ABI or version.",
                            )
                    }
                }
                "capture",
                "proxy" -> {
                    val packages =
                        if (id == "capture")
                            listOf("frida" to "frida", "frida-tools" to "fridaTools")
                        else listOf("mitmproxy" to "mitmproxy", "mitmproxy_rs" to "mitmproxyRs")
                    for ((pkg, key) in packages) result +=
                        ToolSource(
                            "$pkg ${versions.version(key)} · PyPI",
                            "https://pypi.org/project/$pkg/${versions.version(key)}/#files",
                            folder(id),
                            "Package download page: choose matching wheels. Do not unpack wheels here manually; install with pip --target. Offline wheels go in downloads/wheelhouse/capture or proxy with all dependencies.",
                        )
                }
                "python" -> {
                    result +=
                        ToolSource(
                            "Python ${versions.version("python")} · Python.org",
                            "https://www.python.org/downloads/release/python-${versions.version("python").replace(".", "")}/",
                            ".venvs",
                            "Choose a full interpreter or create a venv. Do not extract an embeddable ZIP into a venv. The portable method uses uv-managed Python.",
                        )
                    val asset =
                        ToolPolicy.catalog
                            .getAsJsonObject("uvAssets")
                            .getAsJsonArray(HostPlatform.current.key)[0]
                            .asString
                    result +=
                        ToolSource(
                            "uv ${ToolPolicy.target("uv")} · ${HostPlatform.current.label}",
                            "https://github.com/astral-sh/uv/releases/download/${ToolPolicy.target("uv")}/$asset",
                            "downloads/uv",
                            "Save the original archive in the uv cache, or extract uv and select its executable on Connection.",
                        )
                }
                "wireguard" -> {
                    val file = "com.wireguard.android-${versions.version("wireguard")}.apk"
                    result +=
                        ToolSource(
                            file,
                            "https://download.wireguard.com/android-client/$file",
                            "android-tools",
                            "Keep the APK intact. Installation and VPN consent on Android are separate steps.",
                        )
                }
            }
        }
        return result
    }

    fun structure(config: Settings): String {
        val setup = config.setup
        val adb = HostPlatform.current.executable("adb")
        return """
            ${File(config.tools).absolutePath}/
            ├── platform-tools/     $adb + libraries + NOTICE.txt + source.properties
            ├── frida-server/       frida-server-${setup.version("frida")}-android-<ABI> (ELF)
            ├── frida-gadget/       frida-gadget-${setup.version("frida")}-android-<ABI>.so
            ├── frida-python/       frida/ + frida_tools/ + dependencies + *.dist-info/
            ├── proxy-python/       mitmproxy/ + mitmproxy_rs/ + dependencies + *.dist-info/
            ├── android-tools/      com.wireguard.android-${setup.version("wireguard")}.apk
            ├── .venvs/             local Python environments
            ├── python-managed/     uv-managed Python
            └── downloads/
                ├── adb/            original ZIP
                ├── frida/          original server/Gadget .xz
                ├── wireguard/      original APK
                ├── uv/             original uv archive + uv cache
                └── wheelhouse/
                    ├── capture/    Frida wheels + all dependencies
                    └── proxy/      proxy wheels + all dependencies

            ABI: arm64, arm, x86_64, x86. Python wheels must match OS/CPU/Python.
        """
            .trimIndent()
    }
}
