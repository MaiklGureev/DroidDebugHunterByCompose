package hunter.features.connection

import hunter.core.AppFiles
import hunter.core.ApplicationLog
import hunter.core.logHandledFailure
import hunter.core.tr
import hunter.features.android.Adb
import hunter.features.android.AndroidApp
import hunter.features.settings.Settings
import hunter.features.tools.ToolLayout
import hunter.features.traffic.CaptureLock
import hunter.features.traffic.CaptureSession
import hunter.features.traffic.WIREGUARD_PROFILE
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

internal object ConnectionAssets {
    private const val WIREGUARD_PACKAGE = "com.wireguard.android"

    fun profileFile(config: Settings, app: AndroidApp): File =
        File(CaptureSession.profile(app, config), WIREGUARD_PROFILE)

    suspend fun wireGuardInstalled(config: Settings, serial: String, user: Int): Boolean =
        Adb(config).shell(serial, "pm list packages --user $user $WIREGUARD_PACKAGE")
            .lineSequence().any { it.trim() == "package:$WIREGUARD_PACKAGE" }

    suspend fun installWireGuard(config: Settings, serial: String, app: AndroidApp) {
        val adb = Adb(config)
        adb.validateApp(serial, app)
        if (wireGuardInstalled(config, serial, app.user)) return
        val apk = withContext(Dispatchers.IO) { ToolLayout.wireGuardApk(config) }
            ?: error(tr("No local WireGuard APK"))
        val result = adb.device(serial, "install", "--user", app.user.toString(), apk.absolutePath, timeout = 120000)
        check(result.requireSuccess().lineSequence().any { it.trim() == "Success" }) { result.text }
    }

    suspend fun prepare(config: Settings, app: AndroidApp?, vpn: Boolean): String {
        CaptureSession.prepareAssets(config, app)
        return tr("CA{0} prepared", if (vpn) tr(" and VPN profile") else "")
    }

    suspend fun sendCa(config: Settings, serial: String) {
        val ca = withContext(Dispatchers.IO) {
            AppFiles.ca.also { check(it.isFile) { tr("Prepare the CA first") } }
        }
        val adb = Adb(config)
        adb.device(serial, "push", ca.absolutePath, "/sdcard/Download/DroidDebugHunter-CA.cer").requireSuccess()
        val activities = listOf(
            "android.settings.ADVANCED_SECURITY_SETTINGS",
            "android.settings.SECURITY_SETTINGS",
            "android.settings.SETTINGS",
        )
        for (action in activities) {
            val result = adb.device(serial, "shell", "am start -a $action")
            if (result.code == 0 && !result.text.contains("Error:", true)) return
        }
        currentCoroutineContext()[ApplicationLog]?.warning(
            "adb", "CA transferred, but Android security settings could not be opened",
        )
    }

    suspend fun sendVpn(config: Settings, serial: String, app: AndroidApp) {
        val adb = Adb(config)
        adb.validateApp(serial, app)
        check(wireGuardInstalled(config, serial, app.user)) { tr("Install WireGuard on Android first") }
        val profile = withContext(Dispatchers.IO) {
            profileFile(config, app).also { check(it.isFile) { tr("Prepare the VPN profile first") } }
        }
        adb.device(serial, "push", profile.absolutePath, "/sdcard/Download/$WIREGUARD_PROFILE").requireSuccess()
        try {
            val launcher = adb.launcherActivity(serial, app.user, WIREGUARD_PACKAGE)
            adb.shell(serial, "am start --user ${app.user} -n ${Adb.quote(launcher)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logHandledFailure("wireguard", "Profile transferred, but WireGuard could not be opened", e)
        }
    }

    suspend fun backup() = withContext(Dispatchers.IO) {
        CaptureLock().use {
            val backup = File(AppFiles.data, "capture-backup-${System.currentTimeMillis()}").apply { mkdirs() }
            for (name in listOf("proxy", "vpn")) {
                val source = File(AppFiles.capture, name)
                if (source.exists()) Files.move(source.toPath(), File(backup, name).toPath())
            }
        }
    }
}
