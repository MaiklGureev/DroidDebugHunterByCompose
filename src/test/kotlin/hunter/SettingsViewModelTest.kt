package hunter

import hunter.core.I18n
import hunter.core.UiLanguage
import hunter.features.settings.Settings
import hunter.features.settings.SettingsViewModel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun `language theme and behavior survive reloading the settings file`(@TempDir directory: Path) = runTest {
        val previousLanguage = I18n.language
        val file = directory.resolve("settings.properties").toFile()
        val initial = Settings(tools = directory.toString(), lastSerial = "usb-device", vpnPort = "42001")
        try {
            val viewModel = create(initial, backgroundScope) { it.save(file) }
            viewModel.setLanguage(UiLanguage.Russian)
            viewModel.setFollowSystemTheme(enabled = true, effectiveDark = false)
            assertEquals(UiLanguage.Russian, I18n.language)
            assertTrue(viewModel.uiState.value.followSystemTheme)
            runCurrent()
            assertTrue(Settings.load(file).followSystemTheme)
            assertEquals(UiLanguage.Russian, Settings.load(file).language)

            viewModel.setDarkTheme(true)
            viewModel.applyBehavior(autoRefresh = false, maxLogs = 50000, maxRequests = 20000)
            viewModel.flush()
            val restored = create(Settings.load(file), backgroundScope) { it.save(file) }
            val settings = restored.uiState.value
            assertEquals(viewModel.uiState.value, settings)
            assertEquals(UiLanguage.Russian, I18n.language)
            assertTrue(settings.dark)
            assertFalse(settings.followSystemTheme)
            assertFalse(settings.autoRefresh)
            assertEquals(50000, settings.maxLogs)
            assertEquals(20000, settings.maxRequests)
            assertEquals(initial.lastSerial, settings.lastSerial)
            assertEquals(initial.vpnPort, settings.vpnPort)
        } finally {
            I18n.use(previousLanguage)
        }
    }

    @Test
    fun `a slow save cannot leave an older snapshot on disk after flushing`() = runTest {
        val previousLanguage = I18n.language
        val saved = mutableListOf<Settings>()
        val firstSave = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        try {
            val initial = Settings(tools = "unused", dark = true, language = UiLanguage.English)
            val viewModel = create(initial, backgroundScope) { settings ->
                if (saved.isEmpty()) {
                    firstSave.complete(Unit)
                    releaseSave.await()
                }
                saved += settings
            }
            viewModel.setDarkTheme(false)
            firstSave.await()
            viewModel.setLanguage(UiLanguage.Russian)
            viewModel.setFollowSystemTheme(enabled = true, effectiveDark = true)
            val latest = viewModel.uiState.value

            releaseSave.complete(Unit)
            viewModel.flush()
            assertEquals(listOf(initial.copy(dark = false), latest), saved)
        } finally {
            I18n.use(previousLanguage)
        }
    }

    private fun create(
        initial: Settings,
        scope: CoroutineScope,
        persist: suspend (Settings) -> Unit,
    ) = SettingsViewModel(
        initial = initial,
        scope = scope,
        onChanged = {},
        showStatus = {},
        showError = { error(it) },
        persist = persist,
    )
}
