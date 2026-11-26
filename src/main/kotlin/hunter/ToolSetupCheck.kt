package hunter

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*

/** Explicit online CI probe. Uses temporary files, never user settings or Android commands. */
fun checkToolSetup(): Map<String, Any> {
    val temporaryRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
    val directory = Files.createTempDirectory("ddhc-tool-setup-check-").toFile().canonicalFile
    System.setProperty("droiddebughunter.dataDir", directory.path)
    try {
        AppFiles.extract()
        return runBlocking {
            var reportedBytes = false
            val progress: (SetupProgress) -> Unit = {
                if (it.bytes > 0) reportedBytes = true
                println(
                    "${it.phase}: ${it.detail}" +
                        if (it.fraction == null) "" else " · ${(it.fraction!! * 100).toInt()}%"
                )
            }
            var config =
                Settings(tools = File(directory, "tools with spaces").path, autoRefresh = false)
            config = ToolProvisioner.prepare(config, "adb", progress, ::println)
            config = ToolProvisioner.prepare(config, "python", progress, ::println)
            val findings = ToolInspector.inspect(config)
            val python = findings.first { it.id == "python" }
            val adb = findings.first { it.id == "adb" }
            check(python.actual == ToolPolicy.target("python")) { python.summary }
            check(adb.actual == ToolPolicy.target("adb")) { adb.summary }
            check(File(config.pythonCommand).canonicalFile.toPath().startsWith(directory.toPath()))
            check(reportedBytes)
            mapOf(
                "javaHome" to System.getProperty("java.home"),
                "javaVersion" to System.getProperty("java.version"),
                "platform" to HostPlatform.current.key,
                "python" to python.actual!!,
                "adb" to adb.actual!!,
                "privatePython" to true,
                "downloadProgress" to reportedBytes,
            )
        }
    } finally {
        require(
            directory.parentFile == temporaryRoot &&
                directory.name.startsWith("ddhc-tool-setup-check-")
        )
        directory.deleteRecursively()
    }
}
