package hunter.app

import hunter.core.I18n
import hunter.core.UiLanguage
import hunter.features.android.Device
import hunter.features.help.HelpTopic
import hunter.features.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Exercises lazily loaded coroutine classes using isolated data and no Android connection. */
fun checkApplication(): Map<String, Any> {
    val temporaryRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
    val directory = Files.createTempDirectory("ddhc-app-check-").toFile().canonicalFile
    System.setProperty("droiddebughunter.dataDir", directory.path)
    try {
        Settings(
                tools = directory.path,
                adb = File(directory, "no-adb-for-application-check").absolutePath,
                autoRefresh = false,
            )
            .save()
        val initialSettings = Settings.load()
        runBlocking(Dispatchers.Main) {
            val model = AppState(initialSettings)
            try {
                withTimeout(15000) {
                    while (!model.initialized || model.connection.uiState.value.refreshing) {
                        check(model.error.isEmpty() || model.initialized) { model.error }
                        delay(10)
                    }
                    val device = Device("application-check", "offline", "Application check")
                    model.connection.selectDevice(device)
                    while (model.busy.isNotEmpty()) delay(10)
                    check(model.connection.uiState.value.selectedDevice == device) {
                        "Device selection failed: ${model.error}"
                    }
                    model.help.selectTopic(HelpTopic.AdbTools)
                    repeat(4) {
                        model.settings.setLanguage(UiLanguage.Russian)
                        check(Page.Settings.label == "Настройки")
                        check(model.help.uiState.value.location?.file?.name == "ADB_TOOLS.md")
                        model.settings.setFollowSystemTheme(enabled = true, effectiveDark = true)
                        check(model.settings.uiState.value.followSystemTheme)
                        model.settings.setLanguage(UiLanguage.English)
                        check(Page.Settings.label == "Settings")
                        check(model.help.uiState.value.location?.file?.name == "ADB_TOOLS.en.md")
                        model.settings.setDarkTheme(false)
                        check(!model.settings.uiState.value.dark && !model.settings.uiState.value.followSystemTheme)
                    }
                    val markdown = model.help.uiState.first { it.content != null }.content!!.getOrThrow()
                    check(markdown.blocks.any { it is org.commonmark.ext.gfm.tables.TableBlock }) {
                        "Packaged Markdown table parser is unavailable"
                    }
                    val exportedLog = File(directory, "exported-application.log")
                    model.exportApplicationLog(exportedLog)
                    while (model.busy.isNotEmpty()) delay(10)
                    val exportedText = withContext(Dispatchers.IO) { exportedLog.readText() }
                    val buildVersion = withContext(Dispatchers.IO) { StartupDiagnostics.version }
                    check(buildVersion != "unknown" &&
                        exportedText.contains("[INFO] [lifecycle] Started · DroidDebugHunterByCompose $buildVersion") &&
                        listOf("OS:", "Java:", "Launch:", "ADB executable:", "Python executable:").all(exportedText::contains)
                    ) {
                        "The application log did not include startup events"
                    }
                    model.confirmClearApplicationLog()
                    val clearLog = checkNotNull(model.confirmation).run
                    model.confirmation = null
                    clearLog()
                    while (model.busy.isNotEmpty()) delay(10)
                    val logBytes = withContext(Dispatchers.IO) { model.applicationLog.file.length() }
                    check(model.journal.isEmpty() && logBytes == 0L) {
                        "The application log was not cleared"
                    }
                    model.shutdown()
                    check(!model.scope.isActive) {
                        "Application scope is still active after shutdown"
                    }
                }
            } finally {
                withContext(NonCancellable) { model.scope.coroutineContext[Job]?.cancelAndJoin() }
            }
        }
        val restoredSettings = Settings.load()
        runBlocking(Dispatchers.Main) {
            val restored = AppState(restoredSettings)
            try {
                val settings = restored.settings.uiState.value
                check(settings.language == UiLanguage.English) { "The latest interface language was not persisted" }
                check(!settings.dark && !settings.followSystemTheme) { "The latest theme was not persisted" }
                check(I18n.language == UiLanguage.English && Page.Settings.label == "Settings")
                withTimeout(15000) {
                    while (!restored.initialized || restored.connection.uiState.value.refreshing) delay(10)
                    restored.shutdown()
                }
            } finally {
                withContext(NonCancellable) {
                    restored.scope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        }
        return mapOf(
            "deviceSelection" to true,
            "shutdown" to true,
            "languagePersistence" to true,
            "themePersistence" to true,
            "helpLanguage" to true,
            "applicationLog" to true,
            "applicationSource" to
                AppState::class.java.protectionDomain.codeSource.location.toString(),
        )
    } finally {
        check(directory.parentFile == temporaryRoot && directory.name.startsWith("ddhc-app-check-"))
        check(directory.deleteRecursively()) { "Cannot remove application check data: $directory" }
    }
}
