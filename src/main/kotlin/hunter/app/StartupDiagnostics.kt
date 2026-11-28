package hunter.app

import hunter.core.AppFiles
import hunter.core.HostPlatform
import hunter.features.settings.Settings

internal object StartupDiagnostics {
    val version: String
        get() = StartupDiagnostics::class.java.getResourceAsStream("/application-version.txt")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText().trim() }
            ?.takeIf { it.isNotEmpty() } ?: "unknown"

    fun summary(settings: Settings): String = buildString {
        val source = StartupDiagnostics::class.java.protectionDomain.codeSource?.location
        val launcher = System.getProperty("jpackage.app-path")
        val resources = System.getProperty("compose.application.resources.dir")
        val runtime = Runtime.getRuntime()
        appendLine("Started · DroidDebugHunterByCompose $version")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} · ${HostPlatform.current.key}")
        appendLine("Java: ${System.getProperty("java.runtime.version")} · ${System.getProperty("java.vendor")} · ${System.getProperty("java.vm.name")}")
        appendLine("Runtime: ${runtime.availableProcessors()} processors · max heap ${runtime.maxMemory() / (1024 * 1024)} MiB · ${System.getProperty("file.encoding")}")
        appendLine("Launch: ${if (launcher.isNullOrBlank()) "JVM" else "jpackage"} · source: ${source ?: "unknown"}")
        if (!launcher.isNullOrBlank()) appendLine("Launcher: $launcher")
        if (!resources.isNullOrBlank()) appendLine("Compose resources: $resources")
        appendLine("Data directory: ${AppFiles.data.absolutePath}")
        appendLine("Tools directory: ${settings.tools}")
        appendLine("ADB executable: ${settings.adbCommand} · selected download: ${settings.setup.version("adb")}")
        appendLine("Python executable: ${settings.pythonCommand} · selected download: ${settings.setup.version("python")}")
        append("Settings: language=${settings.language.code}, autoRefresh=${settings.autoRefresh}, offlineTools=${settings.setup.offline}")
    }
}
