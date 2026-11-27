package hunter.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AppFiles {
    val data =
        (System.getProperty("droiddebughunter.dataDir")?.let(::File)
                ?: HostPlatform.current.dataDirectory(
                    File(System.getProperty("user.home")),
                    System.getenv(),
                ))
            .apply { mkdirs() }
    val runtime = File(data, "runtime")
    val capture = File(data, "capture").apply { mkdirs() }
    val ca
        get() = File(capture, "proxy/mitmproxy-ca-cert.cer")

    fun extract() {
        val loader = AppFiles::class.java.classLoader
        val manifest =
            loader.getResourceAsStream("runtime-manifest.txt")
                ?: error(tr("runtime-manifest.txt not found"))
        manifest.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val name = raw.trim().removePrefix("\uFEFF")
                if (name.isNotEmpty()) {
                    val target = File(data, name)
                    require(target.canonicalPath.startsWith(data.canonicalPath + File.separator))
                    val bytes =
                        loader.getResourceAsStream(name)?.use { it.readBytes() }
                            ?: error(tr("Missing resource {0}", name))
                    if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                        target.parentFile.mkdirs()
                        atomicWrite(target, bytes)
                    }
                }
            }
        }
    }

    fun toolsDefault(): String {
        val candidates =
            listOfNotNull(
                System.getProperty("compose.application.resources.dir")?.let { File(it, "tools") },
                File("desktop-resources/${HostPlatform.current.key}/tools"),
                File("desktop-resources/${HostPlatform.current.os.id}/tools"),
                File(data, "tools/${HostPlatform.current.key}"),
            )
        return (candidates.firstOrNull {
                File(it, "platform-tools/${HostPlatform.current.executable("adb")}").isFile
            } ?: candidates.last())
            .absolutePath
    }

    fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".ddh-", ".tmp")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(
                    temp,
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
