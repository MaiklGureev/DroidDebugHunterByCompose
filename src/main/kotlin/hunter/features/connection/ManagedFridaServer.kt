package hunter.features.connection

import hunter.core.tr
import hunter.features.android.Adb
import hunter.features.settings.Settings
import hunter.features.tools.ToolInspector
import hunter.features.tools.ToolPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class ManagedFridaServer(private val report: (String) -> Unit) {
    private data class Process(val config: Settings, val serial: String, val pid: String)

    private var owned: Process? = null
    val isOwned: Boolean get() = owned != null

    suspend fun install(config: Settings, serial: String, file: File?): String {
        check(!isOwned) { tr("Stop the frida-server started by this session first") }
        val adb = Adb(config)
        val arch = adb.architecture(serial)
        val clientVersion = ToolInspector.fridaVersion(config)
        if (clientVersion != ToolPolicy.target("frida"))
            report(ToolPolicy.notice("frida", clientVersion))
        val server = withContext(Dispatchers.IO) {
            val source = file ?: File(config.tools, "frida-server/frida-server-$clientVersion-android-$arch")
                .takeIf { it.isFile }
                ?: error(tr("No frida-server {0} for {1}", clientVersion, arch))
            Adb.validateElf(source, arch)
            source
        }
        val remote = MANAGED_FRIDA_SERVER
        adb.device(serial, "push", server.absolutePath, remote, timeout = 120000).requireSuccess()
        adb.shell(serial, "chmod 755 $remote")
        val version = adb.shell(serial, "$remote --version")
        check(version == clientVersion) {
            tr("Server version is {0}; {1} is required", version, clientVersion)
        }
        return tr("frida-server {0} ({1}) installed", version, arch)
    }

    suspend fun start(config: Settings, serial: String): String {
        check(!isOwned) { tr("This session has already started the server") }
        val adb = Adb(config)
        val clientVersion = ToolInspector.fridaVersion(config)
        val serverVersion = adb.shell(serial, "$MANAGED_FRIDA_SERVER --version")
        check(serverVersion == clientVersion) {
            tr("Server version is {0}; {1} is required", serverVersion, clientVersion)
        }
        if (clientVersion != ToolPolicy.target("frida"))
            report(ToolPolicy.notice("frida", clientVersion))
        val root = adb.shell(serial, "id").contains("uid=0")
        val command = "$MANAGED_FRIDA_SERVER >/data/local/tmp/ddhc-frida.log 2>&1 & echo \$!"
        val pid = adb.shell(serial, if (root) "sh -c ${Adb.quote(command)}" else "su -c ${Adb.quote(command)}")
            .lineSequence()
            .lastOrNull { it.matches(Regex("[0-9]+")) }
            ?: error(tr("Cannot obtain the frida-server PID"))
        owned = Process(config, serial, pid)
        delay(500)
        check(adb.shell(serial, "cat /proc/$pid/cmdline").contains("ddhc-frida-server")) {
            tr("frida-server exited. Check root and port 27042.")
        }
        return tr("frida-server active · PID {0}", pid)
    }

    suspend fun stopOwned() {
        val (config, serial, pid) = owned ?: return
        val adb = Adb(config)
        val cmdline = adb.device(serial, "shell", "cat /proc/$pid/cmdline", timeout = 5000)
        if (cmdline.code == 0 && cmdline.output.contains("ddhc-frida-server")) {
            val root = adb.shell(serial, "id").contains("uid=0")
            adb.shell(serial, if (root) "kill $pid" else "su -c ${Adb.quote("kill $pid")}")
        }
        owned = null
    }
}
