package hunter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.Locale

enum class UiLanguage(val code: String, val nativeName: String) {
    English("en", "English"),
    Russian("ru", "Русский");

    fun helpFile(filename: String): String =
        if (this == English && filename.endsWith(".md")) filename.removeSuffix(".md") + ".en.md"
        else filename

    companion object {
        fun default(locale: Locale = Locale.getDefault()): UiLanguage =
            if (locale.language == "ru") Russian else English

        fun fromCode(code: String, fallback: UiLanguage = default()): UiLanguage =
            entries.firstOrNull { it.code == code } ?: fallback
    }
}

/** Only application-owned text goes through this catalog; captured data and raw logs do not. */
object I18n {
    var language by mutableStateOf(UiLanguage.default())
        private set

    private val catalogs: Map<UiLanguage, Map<String, String>> =
        UiLanguage.entries.associateWith { language ->
            val stream =
                checkNotNull(I18n::class.java.getResourceAsStream("/i18n/${language.code}.json"))
            stream.reader(Charsets.UTF_8).use { reader ->
                Gson().fromJson(reader, JsonObject::class.java).entrySet().associate {
                    it.key to it.value.asString
                }
            }
        }

    private val placeholder = Regex("\\{([0-9]+)}")

    fun use(language: UiLanguage) {
        this.language = language
    }

    fun catalog(language: UiLanguage): Map<String, String> = catalogs.getValue(language)

    fun text(language: UiLanguage, key: String, vararg arguments: Any?): String {
        val template =
            catalogs.getValue(language)[key] ?: catalogs.getValue(UiLanguage.English)[key] ?: key
        return placeholder.replace(template) { match ->
            val index = match.groupValues[1].toInt()
            if (index < arguments.size) arguments[index].toString() else match.value
        }
    }
}

fun tr(key: String, vararg arguments: Any?): String = I18n.text(I18n.language, key, *arguments)
