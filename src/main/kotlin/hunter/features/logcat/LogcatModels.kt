package hunter.features.logcat

import com.google.gson.Gson
import hunter.core.tr
import java.util.UUID

data class LogEntry(
    val time: String,
    val pid: String,
    val tid: String,
    val uid: String,
    val level: String,
    val tag: String,
    val message: String,
    val raw: String,
    val serial: String,
    val buffer: String = "",
    val id: String = UUID.randomUUID().toString(),
)

object LogParser {
    private val pattern =
        Regex(
            "^((?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+(?:(\\S+)\\s+)?(\\d+)\\s+(\\d+)\\s+([VDIWEFAS])\\s+(.+?)\\s*:\\s?(.*)$"
        )

    fun parse(
        line: String,
        serial: String,
        buffer: String = "",
        forcedUid: String = "",
    ): LogEntry? {
        val m = pattern.matchEntire(line) ?: return null
        val uidText = m.groupValues[2].ifBlank { forcedUid }
        val uid = uidNumber(uidText)?.toString() ?: uidText
        return LogEntry(
            m.groupValues[1],
            m.groupValues[3],
            m.groupValues[4],
            uid,
            m.groupValues[5],
            m.groupValues[6],
            m.groupValues[7],
            line,
            serial,
            buffer,
        )
    }

    fun uidNumber(value: String): Int? {
        value.toIntOrNull()?.let {
            return it
        }
        mapOf("root" to 0, "system" to 1000, "radio" to 1001, "log" to 1007, "shell" to 2000)[value]
            ?.let {
                return it
            }
        val m = Regex("u(\\d+)_([ai])(\\d+)").matchEntire(value) ?: return null
        val number =
            (m.groupValues[1].toLongOrNull() ?: return null) * 100000 +
                (if (m.groupValues[2] == "a") 10000 else 99000) +
                (m.groupValues[3].toLongOrNull() ?: return null)
        return number.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    }
}

data class LogFilter(
    val query: String = "",
    val regex: Boolean = false,
    val tag: String = "",
    val excludeTag: String = "",
    val exact: Boolean = false,
    val level: String = "V",
    val pid: String = "",
    val tid: String = "",
    val uid: String = "",
) {
    fun predicate(): (LogEntry) -> Boolean {
        require(query.length <= 512) { tr("Search pattern exceeds 512 characters") }
        val re =
            if (regex && query.isNotEmpty())
                com.google.re2j.Pattern.compile(query, com.google.re2j.Pattern.CASE_INSENSITIVE)
            else null
        fun ids(input: String): Set<String> {
            val parts = input.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            require(parts.all { it.toLongOrNull()?.let { n -> n >= 0 } == true }) {
                tr("PID / TID / UID must be comma-separated numbers")
            }
            return parts.toSet()
        }
        val pids = ids(pid)
        val tids = ids(tid)
        val uids = ids(uid)
        fun tags(input: String) = input.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val include = tags(tag)
        val exclude = tags(excludeTag)
        fun matches(tag: String, item: String) =
            if (exact) tag.equals(item, true) else tag.contains(item, true)
        return { e ->
            "VDIWEFAS".indexOf(e.level) >= "VDIWEFAS".indexOf(level) &&
                (pids.isEmpty() || e.pid in pids) &&
                (tids.isEmpty() || e.tid in tids) &&
                (uids.isEmpty() || e.uid in uids) &&
                (include.isEmpty() || include.any { matches(e.tag, it) }) &&
                exclude.none { matches(e.tag, it) } &&
                (if (re != null) re.matcher(e.raw).find() else e.raw.contains(query, true))
        }
    }
}

object LogExport {
    private fun csv(value: String): String {
        val safe =
            if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value"
            else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    fun write(entries: List<LogEntry>, format: String): String =
        when (format) {
            "CSV" ->
                "time,pid,tid,uid,level,tag,message,device,buffer\n" +
                    entries.joinToString("\n") { e ->
                        listOf(
                                e.time,
                                e.pid,
                                e.tid,
                                e.uid,
                                e.level,
                                e.tag,
                                e.message,
                                e.serial,
                                e.buffer,
                            )
                            .joinToString(",", transform = ::csv)
                    }
            "JSONL" -> entries.joinToString("\n") { Gson().toJson(it) }
            else -> entries.joinToString("\n") { "[${it.serial}] ${it.raw}" }
        }
}
