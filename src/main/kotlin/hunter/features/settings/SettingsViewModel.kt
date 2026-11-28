package hunter.features.settings

import hunter.core.I18n
import hunter.core.UiLanguage
import hunter.core.logHandledFailure
import hunter.core.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SettingsViewModel internal constructor(
    initial: Settings,
    scope: CoroutineScope,
    private val onChanged: (Settings) -> Unit,
    private val showStatus: (String) -> Unit,
    private val showError: (String) -> Unit,
    private val persist: suspend (Settings) -> Unit = { settings ->
        withContext(Dispatchers.IO) { settings.save() }
    },
) {
    private val state = MutableStateFlow(initial)
    val uiState: StateFlow<Settings> = state.asStateFlow()
    private val pendingSave = Channel<Unit>(Channel.CONFLATED)
    private val saveMutex = Mutex()
    private var saved: Settings? = null

    init {
        I18n.use(initial.language)
        scope.launch {
            for (signal in pendingSave) {
                try {
                    flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logHandledFailure("settings", "Settings write failed", e)
                    showError(tr("Settings were not saved: {0}", e.message))
                }
            }
        }
    }

    fun setLanguage(language: UiLanguage) {
        update { it.copy(language = language) }
        showStatus(tr("Language changed"))
    }

    fun setDarkTheme(dark: Boolean) = update { it.copy(dark = dark, followSystemTheme = false) }

    fun setFollowSystemTheme(enabled: Boolean, effectiveDark: Boolean) =
        update { it.copy(followSystemTheme = enabled, dark = effectiveDark) }

    fun applyBehavior(autoRefresh: Boolean, maxLogs: Int, maxRequests: Int) {
        update { it.copy(autoRefresh = autoRefresh, maxLogs = maxLogs, maxRequests = maxRequests) }
        showStatus(tr("Settings applied"))
    }

    internal fun update(transform: (Settings) -> Settings) = replace(transform(state.value))

    internal fun replace(settings: Settings) {
        val previous = state.value
        if (settings == previous) return
        if (settings.language != previous.language) I18n.use(settings.language)
        state.value = settings
        onChanged(settings)
        pendingSave.trySend(Unit)
    }

    internal suspend fun flush() = saveMutex.withLock {
        while (true) {
            val current = state.value
            if (current == saved) break
            persist(current)
            saved = current
        }
    }
}
