package hunter

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import java.nio.file.Path
import java.util.Locale
import java.util.Properties
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class LanguageTest {
    @Test
    fun `catalogs contain matching translations and argument placeholders`() {
        val english = I18n.catalog(UiLanguage.English)
        val russian = I18n.catalog(UiLanguage.Russian)
        val placeholders = Regex("\\{[0-9]+}")
        assertTrue(english.isNotEmpty())
        assertEquals(english.keys, russian.keys)
        for ((key, value) in english) {
            assertTrue(value.isNotBlank(), key)
            assertTrue(russian.getValue(key).isNotBlank(), key)
            assertEquals(
                placeholders.findAll(value).map { it.value }.toSet(),
                placeholders.findAll(russian.getValue(key)).map { it.value }.toSet(),
                key,
            )
        }
    }

    @Test
    fun `language changes invalidate rendered navigation and enum labels`() {
        val previous = I18n.language
        val observer = SnapshotStateObserver { it() }
        try {
            I18n.use(UiLanguage.English)
            Snapshot.sendApplyNotifications()
            var invalidated = false
            observer.start()
            observer.observeReads(Unit, { invalidated = true }) {
                assertEquals("Settings", Page.Settings.label)
                assertEquals("Launch", AdbAction.Start.title)
                assertEquals("HTTP(S) proxy", CaptureMode.Proxy.title)
            }
            I18n.use(UiLanguage.Russian)
            Snapshot.sendApplyNotifications()
            assertTrue(invalidated)
            assertEquals("Настройки", Page.Settings.label)
            assertEquals("Запустить", AdbAction.Start.title)
            assertEquals("HTTP(S)-прокси", CaptureMode.Proxy.title)
        } finally {
            observer.stop()
            observer.clear()
            I18n.use(previous)
        }
    }

    @Test
    fun `formatting leaves interpolated user data untouched`() {
        val raw = "Русский / English {1} \$value\n{0}"
        assertEquals(
            "File not found: $raw",
            I18n.text(UiLanguage.English, "File not found: {0}", raw),
        )
        assertEquals(
            "Файл не найден: $raw",
            I18n.text(UiLanguage.Russian, "File not found: {0}", raw),
        )
        assertEquals("Future key: $raw", I18n.text(UiLanguage.Russian, "Future key: {0}", raw))
        assertEquals(raw, Body(raw).view("Text"))
        assertEquals(" hi ", Body("<p>hi</p>").view("HTML text"))
        assertEquals(" hi ", Body("<p>hi</p>").view("HTML-текст"))
    }

    @Test
    fun `language is restored from settings across platform profiles`(@TempDir directory: Path) {
        val file = directory.resolve("settings.properties").toFile()
        val windows = HostPlatform(HostOs.Windows, "x64")
        val mac = HostPlatform(HostOs.MacOS, "arm64")
        val settings = Settings(tools = directory.toString(), adb = "custom-adb", dark = false)
        for (language in UiLanguage.entries) {
            settings.copy(language = language).save(file, windows)
            assertEquals(language, Settings.load(file, windows).language)
            assertEquals(language, Settings.load(file, mac).language)
            assertEquals("custom-adb", Settings.load(file, windows).adb)
            assertFalse(Settings.load(file, windows).dark)
        }
    }

    @Test
    fun `legacy and unknown language settings use the system language`(@TempDir directory: Path) {
        val file = directory.resolve("settings.properties").toFile()
        val properties =
            Properties().apply {
                setProperty("tools", directory.toString())
                setProperty("dark", "false")
            }
        for (code in listOf(null, "unsupported")) {
            if (code == null) properties.remove("language")
            else properties.setProperty("language", code)
            file.outputStream().use { properties.store(it, null) }
            assertEquals(UiLanguage.default(), Settings.load(file).language)
            assertFalse(Settings.load(file).dark)
        }
        assertEquals(UiLanguage.Russian, UiLanguage.default(Locale.forLanguageTag("ru-RU")))
        assertEquals(UiLanguage.English, UiLanguage.default(Locale.US))
        assertEquals(UiLanguage.English, UiLanguage.default(Locale.FRENCH))
    }

    @Test
    fun `theme follows the system on first run and keeps a saved override`(@TempDir directory: Path) {
        val file = directory.resolve("settings.properties").toFile()
        assertTrue(Settings.load(file).followSystemTheme)
        file.writeText("dark=false\n")
        val legacy = Settings.load(file)
        assertFalse(legacy.followSystemTheme)
        assertFalse(legacy.dark)
        legacy.copy(followSystemTheme = true).save(file)
        assertTrue(Settings.load(file).followSystemTheme)
    }

    @Test
    fun `all help topics have resources for each interface language`() {
        val topics =
            listOf(
                "USAGE.md",
                "PLATFORMS.md",
                "ANDROID_SETUP_GUIDE.md",
                "ADB_TOOLS.md",
                "LOGCAT.md",
                "BODY_VIEWER.md",
                "DOWNLOADS.md",
                "THIRD_PARTY_NOTICES.md",
            )
        for (language in UiLanguage.entries) for (topic in topics) {
            val path = "runtime/help/${language.helpFile(topic)}"
            assertNotNull(javaClass.classLoader.getResource(path), path)
        }
        assertEquals("LOGCAT.en.md", UiLanguage.English.helpFile("LOGCAT.md"))
        assertEquals("LOGCAT.md", UiLanguage.Russian.helpFile("LOGCAT.md"))
    }
}
