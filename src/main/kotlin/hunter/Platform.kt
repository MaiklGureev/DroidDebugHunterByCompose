package hunter

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import java.io.File

val KeyEvent.isPlatformShortcutPressed: Boolean
    get() = isCtrlPressed || (HostPlatform.current.os == HostOs.MacOS && isMetaPressed)

enum class HostOs(val id: String, val title: String) {
    Windows("windows", "Windows"),
    MacOS("macos", "macOS"),
    Linux("linux", "Linux");

    companion object {
        fun detect(name: String): HostOs =
            when {
                name.contains("mac", true) || name.contains("darwin", true) -> MacOS
                name.startsWith("Windows", true) -> Windows
                name.contains("linux", true) -> Linux
                else -> error("Неподдерживаемая ОС: $name")
            }
    }
}

data class HostPlatform(val os: HostOs, val arch: String) {
    val key
        get() = "${os.id}-$arch"

    val label
        get() = "${os.title} · $arch"

    val pythonName
        get() = if (os == HostOs.Windows) "python" else "python3"

    val executableSuffix
        get() = if (os == HostOs.Windows) ".exe" else ""

    fun executable(name: String) = name + executableSuffix

    fun dataDirectory(home: File, environment: Map<String, String>): File =
        when (os) {
            HostOs.Windows ->
                File(
                    environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)
                        ?: File(home, "AppData/Local").path,
                    "DroidDebugHunterCompose",
                )
            HostOs.MacOS -> File(home, "Library/Application Support/DroidDebugHunterCompose")
            HostOs.Linux ->
                File(
                    environment["XDG_DATA_HOME"]?.takeIf { File(it).isAbsolute }
                        ?: File(home, ".local/share").path,
                    "DroidDebugHunterCompose",
                )
        }

    companion object {
        fun detect(os: String, arch: String) =
            HostPlatform(
                HostOs.detect(os),
                when (arch.lowercase()) {
                    "amd64",
                    "x86_64",
                    "x64" -> "x64"
                    "aarch64",
                    "arm64" -> "arm64"
                    else -> error("Неподдерживаемая архитектура: $arch")
                },
            )

        val current = detect(System.getProperty("os.name"), System.getProperty("os.arch"))
    }
}

/** Paths are individual executable names, never a shell command with embedded arguments. */
object ToolResolver {
    fun adb(
        configured: String,
        root: File,
        platform: HostPlatform = HostPlatform.current,
        environment: Map<String, String> = System.getenv(),
        home: File = File(System.getProperty("user.home")),
    ): String {
        if (configured.isNotBlank()) return configured.trim()
        val local = File(root, "platform-tools/${platform.executable("adb")}")
        if (local.isFile) return local.absolutePath
        val sdkRoots =
            listOfNotNull(environment["ANDROID_SDK_ROOT"], environment["ANDROID_HOME"])
                .map(::File) +
                when (platform.os) {
                    HostOs.Windows ->
                        listOf(
                            File(
                                environment["LOCALAPPDATA"] ?: File(home, "AppData/Local").path,
                                "Android/Sdk",
                            )
                        )
                    HostOs.MacOS -> listOf(File(home, "Library/Android/sdk"))
                    HostOs.Linux -> listOf(File(home, "Android/Sdk"))
                }
        return sdkRoots
            .map { File(it, "platform-tools/${platform.executable("adb")}") }
            .firstOrNull(File::isFile)
            ?.absolutePath ?: onPath(platform.executable("adb"), platform, environment) ?: "adb"
    }

    fun localPython(root: File, platform: HostPlatform = HostPlatform.current): File? {
        val paths =
            if (platform.os == HostOs.Windows)
                listOf("python/python.exe", "python/Scripts/python.exe", ".venv/Scripts/python.exe")
            else
                listOf(
                    "python/bin/python3",
                    "python/bin/python",
                    ".venv/bin/python3",
                    ".venv/bin/python",
                    "python/python3",
                )
        return paths.map { File(root, it) }.firstOrNull(File::isFile)
    }

    fun python(
        configured: String,
        root: File,
        platform: HostPlatform = HostPlatform.current,
        environment: Map<String, String> = System.getenv(),
    ): String {
        if (configured.isNotBlank()) return configured.trim()
        return localPython(root, platform)?.absolutePath
            ?: onPath(platform.executable(platform.pythonName), platform, environment)
            ?: platform.pythonName
    }

    private fun onPath(
        name: String,
        platform: HostPlatform,
        environment: Map<String, String>,
    ): String? {
        val delimiter = if (platform.os == HostOs.Windows) ';' else ':'
        val entries = (environment["PATH"] ?: "").split(delimiter).filter(String::isNotBlank)
        val extra =
            if (platform.os == HostOs.MacOS)
                listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
            else emptyList()
        return (entries + extra)
            .map { File(it.trim('"'), name) }
            .firstOrNull(File::isFile)
            ?.absolutePath
    }
}
