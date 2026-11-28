package hunter

import hunter.core.UiLanguage
import hunter.features.help.HelpTopic
import hunter.features.help.HelpViewModel
import java.io.File
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class HelpViewModelTest {
    @Test
    fun `manual Markdown supports local links anchors web links and back`(@TempDir directory: Path) = runTest {
        val root = directory.toFile()
        val first = root.document("notes/./first.markdown", "# First\n\n[Next](second.md#details)")
        val second = root.document("notes/second.md", "# Second\n\n## Details\n\n## End")
        val outside = root.document("outside.md", "# Outside")
        val opened = mutableListOf<URI>()
        val errors = mutableListOf<String>()
        val help = viewModel(File(root, "help"), showError = { errors += it }, openWeb = { opened += it })

        help.openMarkdown(first)
        runCurrent()
        assertEquals(setOf("first"), help.anchors())
        assertTrue(help.uiState.value.canGoBack)

        help.followLink("second.md#details")
        runCurrent()
        assertEquals(second.canonicalFile, help.uiState.value.location?.file)
        assertEquals("details", help.uiState.value.location?.anchor)
        val document = help.uiState.value.content?.getOrThrow()

        help.followLink("#end")
        runCurrent()
        assertEquals("end", help.uiState.value.location?.anchor)
        assertTrue(document === help.uiState.value.content?.getOrThrow())
        help.followLink("../${outside.name}")
        help.followLink("https://example.test/guide")
        runCurrent()
        assertEquals(1, errors.size)
        assertEquals(listOf(URI("https://example.test/guide")), opened)
        assertEquals(second.canonicalFile, help.uiState.value.location?.file)

        help.setLanguage(UiLanguage.Russian)
        assertEquals(second.canonicalFile, help.uiState.value.location?.file)
        help.back()
        assertEquals("details", help.uiState.value.location?.anchor)
        help.back()
        runCurrent()
        assertEquals(first.canonicalFile, help.uiState.value.location?.file)
        help.back()
        assertNull(help.uiState.value.location)
        assertFalse(help.uiState.value.canGoBack)
    }

    @Test
    fun `bundled pages and history follow the interface language`(@TempDir directory: Path) = runTest {
        val root = directory.toFile()
        root.document("android/ADB_TOOLS.en.md", "# ADB Guide\n\n[Usage](../guides/USAGE.en.md)")
        root.document("android/ADB_TOOLS.md", "# Инструменты ADB")
        root.document("guides/USAGE.en.md", "# Usage")
        root.document("guides/USAGE.md", "# Руководство")
        root.document("setup/DOWNLOADS.en.md", "# Downloads")
        root.document("setup/DOWNLOADS.md", "# Загрузки")
        val help = viewModel(root)
        help.selectTopic(HelpTopic.AdbTools)
        runCurrent()
        assertEquals(setOf("adb-guide"), help.anchors())

        help.followLink("../guides/USAGE.en.md")
        runCurrent()
        assertEquals(setOf("usage"), help.anchors())
        help.setLanguage(UiLanguage.Russian)
        runCurrent()
        assertEquals(setOf("руководство"), help.anchors())
        assertEquals(HelpTopic.AdbTools, help.uiState.value.topic)

        help.followLink("USAGE.en.md")
        runCurrent()
        assertEquals(setOf("usage"), help.anchors())
        help.back()
        runCurrent()
        assertEquals(setOf("руководство"), help.anchors())
        help.back()
        runCurrent()
        assertEquals(setOf("инструменты-adb"), help.anchors())

        help.selectTopic(HelpTopic.Downloads)
        runCurrent()
        assertEquals(setOf("загрузки"), help.anchors())
        assertFalse(help.uiState.value.canGoBack)
    }

    @Test
    fun `a bundled page opened before extraction reloads when its files are available`(@TempDir directory: Path) = runTest {
        val root = directory.toFile()
        val help = viewModel(root)
        help.selectTopic(HelpTopic.Logcat)
        runCurrent()
        assertTrue(assertNotNull(help.uiState.value.content).isFailure)

        root.document("android/LOGCAT.en.md", "# Logcat")
        help.onBundledFilesReady()
        runCurrent()
        assertEquals(setOf("logcat"), help.anchors())
    }

    private fun TestScope.viewModel(
        root: File,
        showError: (String) -> Unit = { error(it) },
        openWeb: (URI) -> Unit = { error("Unexpected browser link") },
    ): HelpViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return HelpViewModel(this, UiLanguage.English, root, showError, dispatcher, dispatcher, openWeb)
    }

    private fun File.document(path: String, text: String) = resolve(path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun HelpViewModel.anchors() = assertNotNull(uiState.value.content).getOrThrow().anchors.keys
}
