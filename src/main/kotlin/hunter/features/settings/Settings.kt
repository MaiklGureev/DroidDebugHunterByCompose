package hunter.features.settings

import hunter.core.AppFiles
import hunter.core.HostOs
import hunter.core.HostPlatform
import hunter.core.ToolResolver
import hunter.core.UiLanguage
import hunter.features.tools.SetupMethod
import hunter.features.tools.ToolPolicy
import hunter.features.tools.ToolSetupOptions
import java.io.File
import java.util.Properties

data class Settings(
    val tools: String = AppFiles.toolsDefault(),
    val adb: String = "",
    val python: String = "",
    val dark: Boolean = true,
    val autoRefresh: Boolean = true,
    val maxLogs: Int = 20000,
    val maxRequests: Int = 10000,
    val lastSerial: String = "",
    val lastPackage: String = "",
    val vpnHost: String = "10.0.3.2",
    val vpnPort: String = "51820",
    val spawn: Boolean = true,
    val gadget: Boolean = false,
    val language: UiLanguage = UiLanguage.default(),
    val setup: ToolSetupOptions = ToolSetupOptions(),
    val followSystemTheme: Boolean = false,
) {
    val adbCommand
        get() = ToolResolver.adb(adb, File(tools))

    val pythonCommand
        get() = ToolResolver.python(python, File(tools))

    fun environment(component: String = "all") =
        mapOf(
            "PYTHONIOENCODING" to "utf-8",
            "PYTHONUNBUFFERED" to "1",
            "NO_COLOR" to "1",
            "PYTHONPATH" to
                (when (component) {
                        "capture" -> listOf("frida-python")
                        "proxy" -> listOf("proxy-python")
                        else -> listOf("frida-python", "proxy-python")
                    })
                    .map { File(tools, it) }
                    .filter(File::isDirectory)
                    .joinToString(File.pathSeparator) { it.absolutePath },
        )

    fun save(
        file: File = File(AppFiles.data, "settings.properties"),
        platform: HostPlatform = HostPlatform.current,
    ) =
        synchronized(Companion) {
            val p = Properties()
            if (file.isFile) file.inputStream().use { p.load(it) }
            p.setProperty("tools.${platform.key}.root", tools)
            p.setProperty("tools.${platform.key}.adb", adb)
            p.setProperty("tools.${platform.key}.python", python)
            val setupKey = "tools.${platform.key}.setup."
            mapOf(
                    "method" to setup.method.name,
                    "offline" to setup.offline.toString(),
                    "cache" to setup.cache,
                    "adbArchive" to setup.adbArchive,
                    "uv" to setup.uv,
                )
                .forEach { (k, v) -> p.setProperty(setupKey + k, v) }
            ToolPolicy.ids.forEach { p.setProperty(setupKey + "version." + it, setup.version(it)) }
            mapOf(
                    "dark" to dark,
                    "followSystemTheme" to followSystemTheme,
                    "autoRefresh" to autoRefresh,
                    "maxLogs" to maxLogs,
                    "maxRequests" to maxRequests,
                    "lastSerial" to lastSerial,
                    "lastPackage" to lastPackage,
                    "vpnHost" to vpnHost,
                    "vpnPort" to vpnPort,
                    "spawn" to spawn,
                    "gadget" to gadget,
                    "language" to language.code,
                )
                .forEach { (k, v) -> p.setProperty(k, v.toString()) }
            val output = java.io.ByteArrayOutputStream()
            p.store(output, "DroidDebugHunter Compose")
            AppFiles.atomicWrite(file, output.toByteArray())
        }

    companion object {
        fun load(
            file: File = File(AppFiles.data, "settings.properties"),
            platform: HostPlatform = HostPlatform.current,
        ): Settings {
            val p = Properties()
            if (file.isFile) file.inputStream().use { p.load(it) }
            fun s(k: String, d: String = "") = p.getProperty(k, d)
            val legacy =
                platform.os == HostOs.Windows && !p.containsKey("tools.${platform.key}.root")
            val storedRoot = s("tools.${platform.key}.root", if (legacy) s("tools") else "")
            val oldPython =
                if (legacy) s("python").takeUnless { it in listOf("python", "python.exe") } ?: ""
                else ""
            return Settings(
                tools = storedRoot.takeIf { it.isNotBlank() } ?: AppFiles.toolsDefault(),
                adb = s("tools.${platform.key}.adb", if (legacy) s("adb") else ""),
                python = s("tools.${platform.key}.python", oldPython),
                dark = s("dark", "true").toBoolean(),
                followSystemTheme = p.getProperty("followSystemTheme")?.toBooleanStrictOrNull()
                    ?: !p.containsKey("dark"),
                autoRefresh = s("autoRefresh", "true").toBoolean(),
                maxLogs = s("maxLogs").toIntOrNull()?.coerceIn(1000, 100000) ?: 20000,
                maxRequests = s("maxRequests").toIntOrNull()?.coerceIn(100, 20000) ?: 10000,
                lastSerial = s("lastSerial"),
                lastPackage = s("lastPackage"),
                vpnHost = s("vpnHost", "10.0.3.2"),
                vpnPort = s("vpnPort", "51820"),
                spawn = s("spawn", "true").toBoolean(),
                gadget = s("gadget").toBoolean(),
                language = UiLanguage.fromCode(s("language")),
                setup =
                    ToolSetupOptions(
                        method =
                            SetupMethod.entries.firstOrNull {
                                it.name == s("tools.${platform.key}.setup.method")
                            } ?: SetupMethod.Managed,
                        offline = s("tools.${platform.key}.setup.offline").toBoolean(),
                        cache = s("tools.${platform.key}.setup.cache"),
                        adbArchive = s("tools.${platform.key}.setup.adbArchive"),
                        uv = s("tools.${platform.key}.setup.uv"),
                        versions =
                            ToolPolicy.ids.associateWith {
                                s("tools.${platform.key}.setup.version.$it", ToolPolicy.target(it))
                            },
                    ),
            )
        }
    }
}
