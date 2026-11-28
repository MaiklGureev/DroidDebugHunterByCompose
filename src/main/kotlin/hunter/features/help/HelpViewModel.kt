package hunter.features.help

import hunter.core.UiLanguage
import hunter.core.logHandledFailure
import hunter.core.tr
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HelpTopic(val titleKey: String, internal val path: String? = null) {
    QuickStart("Quick start"),
    Platforms("Windows / macOS / Linux", "setup/PLATFORMS.md"),
    AndroidSetup("Android: complete setup", "setup/ANDROID_SETUP_GUIDE.md"),
    AdbTools("ADB tools", "android/ADB_TOOLS.md"),
    Logcat("Logcat", "android/LOGCAT.md"),
    BodyViewer("Request and response content", "guides/BODY_VIEWER.md"),
    Downloads("Tools and downloads", "setup/DOWNLOADS.md"),
    ThirdParty("Third-party components", "legal/THIRD_PARTY_NOTICES.md"),
}

data class HelpUiState(
    val topic: HelpTopic = HelpTopic.QuickStart,
    val canGoBack: Boolean = false,
    val location: MarkdownLocation? = null,
    val content: Result<MarkdownDocument>? = null,
)

class HelpViewModel internal constructor(
    private val scope: CoroutineScope,
    initialLanguage: UiLanguage,
    val helpRoot: File,
    private val showError: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val openWeb: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
) {
    private sealed interface Entry {
        data object Topic : Entry
        data class Document(val location: MarkdownLocation, val bundled: Boolean) : Entry
    }

    private val state = MutableStateFlow(HelpUiState())
    val uiState: StateFlow<HelpUiState> = state.asStateFlow()
    private var entry: Entry = Entry.Topic
    private val history = ArrayDeque<Entry>()
    private var language = initialLanguage
    private var readJob: Job? = null
    private var navigation = 0L

    fun selectTopic(topic: HelpTopic) {
        history.clear()
        entry = Entry.Topic
        showPage(topic)
    }

    fun openMarkdown(file: File) {
        if (!isMarkdownFile(file)) {
            showError(tr("Select a Markdown file (.md or .markdown)"))
            return
        }
        val origin = ++navigation
        scope.launch {
            try {
                val location = withContext(ioDispatcher) {
                    val absolute = file.absoluteFile
                    MarkdownLocation(absolute.canonicalFile, absolute.parentFile.canonicalFile)
                }
                if (origin == navigation) openDocument(Entry.Document(location, bundled = false), reload = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logHandledFailure("help", "Could not open Markdown", e)
                if (origin == navigation) showError(e.message ?: tr("Guide unavailable"))
            }
        }
    }

    fun back() {
        entry = history.removeLastOrNull() ?: return
        showPage()
    }

    fun followLink(destination: String) {
        val current = currentDocument(state.value.topic) ?: return
        val origin = navigation
        scope.launch {
            try {
                val target = withContext(ioDispatcher) { resolveMarkdownLink(current.location, destination) }
                if (origin != navigation) return@launch
                when (target) {
                    is MarkdownTarget.Document -> openDocument(Entry.Document(target.location, current.bundled))
                    is MarkdownTarget.Web -> withContext(ioDispatcher) { openWeb(target.uri) }
                    null -> showError(tr("This link is unavailable in the Markdown viewer: {0}", destination))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logHandledFailure("help", "Could not open document link", e)
                if (origin == navigation) showError(e.message ?: tr("Guide unavailable"))
            }
        }
    }

    internal fun setLanguage(value: UiLanguage) {
        if (language == value) return
        language = value
        entry = localized(entry)
        for (index in history.indices) history[index] = localized(history[index])
        showPage()
    }

    internal fun onBundledFilesReady() {
        if (currentDocument(state.value.topic)?.bundled == true) showPage(reload = true)
    }

    private fun localized(entry: Entry): Entry {
        if (entry !is Entry.Document || !entry.bundled) return entry
        val location = entry.location
        val path = location.file.relativeTo(location.root).invariantSeparatorsPath
        val base = if (path.endsWith(".en.md")) path.removeSuffix(".en.md") + ".md" else path
        if (base !in translatedPaths) return entry
        return entry.copy(location = location.copy(file = File(location.root, language.helpFile(base))))
    }

    private fun currentDocument(topic: HelpTopic): Entry.Document? = when (val current = entry) {
        Entry.Topic -> topic.path?.let {
            Entry.Document(MarkdownLocation(File(helpRoot, language.helpFile(it)), helpRoot), bundled = true)
        }
        is Entry.Document -> current
    }

    private fun openDocument(document: Entry.Document, reload: Boolean = false) {
        history.addLast(entry)
        entry = document
        showPage(reload = reload)
    }

    private fun showPage(topic: HelpTopic = state.value.topic, reload: Boolean = false) {
        navigation++
        val location = currentDocument(topic)?.location
        val previous = state.value
        val sameFile = !reload && previous.location?.file == location?.file
        state.value = HelpUiState(
            topic = topic,
            canGoBack = history.isNotEmpty(),
            location = location,
            content = if (sameFile) previous.content else null,
        )
        if (sameFile) return
        readJob?.cancel()
        readJob = location?.let { document ->
            scope.launch {
                val result = try {
                    val source = withContext(ioDispatcher) {
                        val file = document.file.canonicalFile
                        require(file.toPath().startsWith(document.root.canonicalFile.toPath())) {
                            tr("This link is unavailable in the Markdown viewer: {0}", document.file.name)
                        }
                        Markdown.read(file)
                    }
                    Result.success(withContext(parseDispatcher) { Markdown.parse(source) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logHandledFailure("help", "Could not read or parse Markdown", e)
                    Result.failure(e)
                }
                state.update { if (it.location?.file == document.file) it.copy(content = result) else it }
            }
        }
    }

    private companion object {
        val translatedPaths = HelpTopic.entries.mapNotNull { it.path }.toSet() + "guides/USAGE.md"
    }
}
