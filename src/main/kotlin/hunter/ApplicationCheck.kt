package hunter

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*

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
        runBlocking(Dispatchers.Main) {
            val model = AppState()
            try {
                withTimeout(15000) {
                    while (!model.initialized || model.refreshing) {
                        check(model.error.isEmpty() || model.initialized) { model.error }
                        delay(10)
                    }
                    val device = Device("application-check", "offline", "Application check")
                    model.selectDevice(device)
                    while (model.busy.isNotEmpty()) delay(10)
                    check(model.selectedDevice == device) {
                        "Device selection failed: ${model.error}"
                    }
                    val markdown =
                        Markdown.parse(
                            Markdown.read(File(AppFiles.runtime, "help/android/ADB_TOOLS.en.md"))
                        )
                    check(markdown.blocks.any { it is org.commonmark.ext.gfm.tables.TableBlock }) {
                        "Packaged Markdown table parser is unavailable"
                    }
                    repeat(4) {
                        model.changeLanguage(UiLanguage.Russian)
                        check(Page.Settings.label == "Настройки")
                        model.changeLanguage(UiLanguage.English)
                        check(Page.Settings.label == "Settings")
                    }
                    model.shutdown()
                    check(Settings.load().language == UiLanguage.English) {
                        "The latest interface language was not persisted"
                    }
                    check(!model.scope.isActive) {
                        "Application scope is still active after shutdown"
                    }
                }
            } finally {
                withContext(NonCancellable) { model.scope.coroutineContext[Job]?.cancelAndJoin() }
            }
        }
        runBlocking(Dispatchers.Main) {
            val restored = AppState()
            try {
                check(restored.settings.language == UiLanguage.English)
                check(I18n.language == UiLanguage.English && Page.Settings.label == "Settings")
                withTimeout(15000) {
                    while (!restored.initialized || restored.refreshing) delay(10)
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
            "applicationSource" to
                AppState::class.java.protectionDomain.codeSource.location.toString(),
        )
    } finally {
        check(directory.parentFile == temporaryRoot && directory.name.startsWith("ddhc-app-check-"))
        check(directory.deleteRecursively()) { "Cannot remove application check data: $directory" }
    }
}
