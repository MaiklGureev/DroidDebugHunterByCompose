package hunter.features.connection

import hunter.core.logHandledFailure
import hunter.core.tr
import hunter.features.android.Adb
import hunter.features.traffic.CaptureCheck
import hunter.features.traffic.CaptureMode
import hunter.features.traffic.CaptureSession
import kotlinx.coroutines.CancellationException

internal suspend fun checkCaptureReadiness(
    context: CaptureTarget,
    onProgress: (List<CaptureCheck>) -> Unit,
): List<CaptureCheck> {
    val (config, target, app, selectedMode) = context
    val checks = mutableListOf<CaptureCheck>()
    suspend fun check(title: String, block: suspend () -> String) {
        try {
            checks += CaptureCheck(title, true, block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logHandledFailure("connection", title, e)
            checks += CaptureCheck(title, false, e.message ?: tr("Unavailable"))
        }
        onProgress(checks.toList())
    }
    val adb = Adb(config)
    check(tr("Device")) {
        require(target?.state == "device") { tr("Select an authorized device") }
        check(adb.device(target.serial, "get-state").requireSuccess() == "device")
        target.label
    }
    if (selectedMode.capturesSingleApp)
        check(tr("App")) {
            requireNotNull(app) { tr("Select an app") }
            requireNotNull(target)
            adb.validateApp(target.serial, app)
            tr("{0} · UID {1} · profile {2}", app.packageName, app.uid, app.user)
        }
    if (selectedMode == CaptureMode.Vpn) check(tr("VPN endpoint")) { CaptureSession.endpoint(config) }
    return checks.toList()
}
