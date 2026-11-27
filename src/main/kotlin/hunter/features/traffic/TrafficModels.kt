package hunter.features.traffic

import com.google.gson.*
import hunter.core.HostOs
import hunter.core.HostPlatform
import hunter.core.array
import hunter.core.flag
import hunter.core.json
import hunter.core.obj
import hunter.core.parseObject
import hunter.core.text
import hunter.core.tr
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

data class Header(val name: String, val value: String)

fun JsonObject.headers(key: String = "headers") =
    array(key).map { it.asJsonObject }.map { Header(it.text("name"), it.text("value")) }

data class Body(
    val data: String = "",
    val encoding: String = "text",
    val complete: Boolean = true,
) {
    fun bytes(): ByteArray =
        if (encoding == "base64") Base64.getDecoder().decode(data)
        else data.toByteArray(Charsets.UTF_8)

    fun view(mode: String): String {
        val bytes = bytes()
        val text = bytes.toString(Charsets.UTF_8)
        return when (mode) {
            "HEX" ->
                bytes
                    .asList()
                    .chunked(16)
                    .mapIndexed { i, line ->
                        "%08X  ".format(i * 16) +
                            line
                                .joinToString(" ") { "%02X".format(it.toInt() and 255) }
                                .padEnd(47) +
                            "  " +
                            line
                                .map {
                                    if ((it.toInt() and 255) in 32..126) it.toInt().toChar()
                                    else '.'
                                }
                                .joinToString("")
                    }
                    .joinToString("\n")
            "Base64" -> Base64.getEncoder().encodeToString(bytes)
            "JSON",
            "Auto" -> runCatching { json.toJson(JsonParser.parseString(text)) }.getOrDefault(text)
            "HTML text",
            "HTML-текст" ->
                text
                    .replace(Regex("(?is)<(script|style)\\b.*?</\\1>"), "")
                    .replace(Regex("(?s)<[^>]+>"), " ")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
            else -> text
        }
    }
}

data class Exchange(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: String = OffsetDateTime.now().toString(),
    val method: String = "GET",
    val url: String = "",
    val packageName: String = "",
    val appName: String = "",
    val uid: String = "",
    val appId: String = "",
    val source: String = "HAR",
    val serial: String = "",
    val version: String = "HTTP/1.1",
    val requestHeaders: List<Header> = emptyList(),
    val request: Body = Body(),
    val hasBody: Boolean = false,
    val responseHeaders: List<Header> = emptyList(),
    val response: Body = Body(complete = false),
    val status: Int? = null,
    val statusText: String = "",
    val duration: Double? = null,
    val error: String = "",
    val diagnostic: String = "",
    val raw: Boolean = false,
    val diagnosticBody: Body? = null,
) {
    val host
        get() = runCatching { URI(url).authority ?: "—" }.getOrDefault("—")

    val path
        get() =
            runCatching {
                    URI(url).let { (it.rawPath ?: "") + (it.rawQuery?.let { q -> "?$q" } ?: "") }
                }
                .getOrDefault(url)

    val time
        get() =
            runCatching {
                    OffsetDateTime.parse(startedAt)
                        .atZoneSameInstant(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                }
                .getOrDefault(startedAt)

    val statusLabel
        get() = if (error.isNotBlank()) "ERR" else status?.toString() ?: if (raw) "RAW" else "…"

    val instant by lazy {
        runCatching { OffsetDateTime.parse(startedAt).toInstant() }
            .getOrDefault(java.time.Instant.EPOCH)
    }

    fun matches(query: String, pkg: String, identity: String): Boolean {
        if (pkg.isNotBlank() && !"$packageName $appName".contains(pkg, true)) return false
        if (identity.isNotBlank() && !"$uid $appId".contains(identity, true)) return false
        if (query.isBlank()) return true
        return "$method $url $status $error $diagnostic $source $packageName $appName ${requestHeaders.joinToString()} ${responseHeaders.joinToString()}"
            .contains(query, true)
    }
}

enum class CurlShell(val title: String) {
    PowerShell("PowerShell 7.3+"),
    Posix("sh / bash / zsh");

    companion object {
        val native
            get() = if (HostPlatform.current.os == HostOs.Windows) PowerShell else Posix
    }
}

object Curl {
    fun quote(value: String): String {
        require('\u0000' !in value) { tr("Argument contains NUL") }
        return "'${value.replace("'", "''")}'"
    }

    fun quotePosix(value: String): String {
        require('\u0000' !in value) { tr("Argument contains NUL") }
        return "'${value.replace("'", "'\\''")}'"
    }

    fun create(e: Exchange, shell: CurlShell = CurlShell.native): String {
        require(
            !e.raw &&
                runCatching { URI(e.url).scheme in listOf("http", "https") }.getOrDefault(false)
        ) {
            tr("cURL is available only for HTTP(S) requests")
        }
        require(e.method.matches(Regex("[A-Za-z-]+"))) { tr("Invalid HTTP method") }
        require(e.request.complete) {
            tr("The request body was partially captured: exact replay is unavailable")
        }
        val quoteArg = if (shell == CurlShell.PowerShell) ::quote else ::quotePosix
        val args =
            mutableListOf(
                if (shell == CurlShell.PowerShell) "curl.exe" else "curl",
                "--globoff",
                "--request",
                quoteArg(e.method),
                "--url",
                quoteArg(e.url),
            )
        for (h in e.requestHeaders) {
            if (h.name.startsWith(":")) continue
            require(h.name.none { it in "\r\n\u0000:" } && h.value.none { it in "\r\n\u0000" }) {
                tr("Invalid header")
            }
            if (h.name.lowercase() in listOf("content-length", "transfer-encoding", "connection"))
                continue
            args += listOf("--header", quoteArg("${h.name}: ${h.value}"))
        }
        if (shell == CurlShell.Posix) {
            if (e.request.data.isNotEmpty()) {
                val octal =
                    e.request.bytes().joinToString("") { "\\0%03o".format(it.toInt() and 255) }
                return "(\nddh_file=\$(mktemp) || exit 1\ntrap 'rm -f \"\$ddh_file\"' 0\nprintf '%b' ${quotePosix(octal)} > \"\$ddh_file\"\n${args.joinToString(" ")} --data-binary \"@\$ddh_file\"\n)"
            }
            if (e.hasBody) args += listOf("--data-raw", "''")
            return args.joinToString(" ")
        }
        val prelude =
            "if (\$PSVersionTable.PSVersion -lt [Version]'7.3') { throw 'Use PowerShell 7.3+' }\n\$PSNativeCommandArgumentPassing = 'Standard'\n"
        if (e.request.data.isNotEmpty()) {
            val b64 = Base64.getEncoder().encodeToString(e.request.bytes())
            return prelude +
                "\$ddhFile = [IO.Path]::GetTempFileName()\ntry {\n  [IO.File]::WriteAllBytes(\$ddhFile, [Convert]::FromBase64String('$b64'))\n  ${args.joinToString(" ")} --data-binary ('@' + \$ddhFile)\n} finally { Remove-Item -LiteralPath \$ddhFile }"
        }
        if (e.hasBody) args += listOf("--data-raw", "''")
        return prelude + args.joinToString(" ")
    }
}

object Har {
    const val MAX_ENTRIES = 20000

    fun read(text: String): List<Exchange> {
        val root = parseObject(text)
        require(root.obj("log").get("entries")?.isJsonArray == true) {
            tr("Expected HAR with a log.entries array")
        }
        val entries = root.obj("log").array("entries")
        require(entries.size() <= MAX_ENTRIES) { tr("HAR limit: 20,000 requests") }
        return entries.map { element ->
            val e = element.asJsonObject
            require(
                e.get("request")?.isJsonObject == true && e.get("response")?.isJsonObject == true
            ) {
                tr("HAR entry has no request/response")
            }
            val req = e.obj("request")
            val res = e.obj("response")
            val post = req.obj("postData")
            val content = res.obj("content")
            fun withMime(headers: List<Header>, mime: String) =
                if (mime.isNotBlank() && headers.none { it.name.equals("Content-Type", true) })
                    headers + Header("Content-Type", mime)
                else headers
            val hasBody =
                req.has("postData") ||
                    (req.text("bodySize").toLongOrNull() ?: 0) > 0 ||
                    req.headers().any {
                        it.name.equals("Content-Length", true) && (it.value.toLongOrNull() ?: 0) > 0
                    }
            Exchange(
                startedAt = e.text("startedDateTime", OffsetDateTime.now().toString()),
                method = req.text("method"),
                url = req.text("url"),
                version = req.text("httpVersion", "HTTP/1.1"),
                requestHeaders = withMime(req.headers(), post.text("mimeType")),
                request =
                    Body(
                        post.text("text"),
                        post.text("encoding", "text"),
                        post.flag("_ddhComplete", !hasBody || post.has("text")),
                    ),
                hasBody = hasBody,
                responseHeaders = withMime(res.headers(), content.text("mimeType")),
                response =
                    Body(
                        content.text("text"),
                        content.text("encoding", "text"),
                        content.flag("_ddhComplete", content.has("text")),
                    ),
                status = res.text("status").toIntOrNull()?.takeIf { it > 0 },
                statusText = res.text("statusText"),
                duration = e.text("time").toDoubleOrNull(),
                packageName = e.text("_packageName"),
                appName = e.text("_appName", e.text("_applicationName")),
                uid = e.text("_uid"),
                appId = e.text("_appId"),
                error = e.text("_error"),
            )
        }
    }

    fun write(entries: List<Exchange>): String =
        json.toJson(
            mapOf(
                "log" to
                    mapOf(
                        "version" to "1.2",
                        "creator" to
                            mapOf("name" to "DroidDebugHunter Compose", "version" to "1.0"),
                        "entries" to
                            entries
                                .filter { !it.raw }
                                .map { e ->
                                    fun headers(h: List<Header>) =
                                        h.map { mapOf("name" to it.name, "value" to it.value) }
                                    fun mime(h: List<Header>) =
                                        h.firstOrNull { it.name.equals("Content-Type", true) }
                                            ?.value ?: ""
                                    val request =
                                        linkedMapOf<String, Any>(
                                            "method" to e.method,
                                            "url" to e.url,
                                            "httpVersion" to e.version,
                                            "headers" to headers(e.requestHeaders),
                                            "queryString" to emptyList<Any>(),
                                            "cookies" to emptyList<Any>(),
                                            "headersSize" to -1,
                                            "bodySize" to
                                                if (e.request.complete) e.request.bytes().size
                                                else -1,
                                        )
                                    if (
                                        e.hasBody ||
                                            e.request.data.isNotEmpty() ||
                                            !e.request.complete
                                    )
                                        request["postData"] =
                                            mapOf(
                                                "mimeType" to mime(e.requestHeaders),
                                                "text" to e.request.data,
                                                "encoding" to e.request.encoding,
                                                "_ddhComplete" to e.request.complete,
                                            )
                                    mapOf(
                                        "startedDateTime" to e.startedAt,
                                        "time" to (e.duration ?: 0),
                                        "request" to request,
                                        "response" to
                                            mapOf(
                                                "status" to (e.status ?: 0),
                                                "statusText" to e.statusText,
                                                "httpVersion" to e.version,
                                                "headers" to headers(e.responseHeaders),
                                                "cookies" to emptyList<Any>(),
                                                "redirectURL" to "",
                                                "headersSize" to -1,
                                                "bodySize" to -1,
                                                "content" to
                                                    mapOf(
                                                        "size" to e.response.bytes().size,
                                                        "mimeType" to mime(e.responseHeaders),
                                                        "text" to e.response.data,
                                                        "encoding" to e.response.encoding,
                                                        "_ddhComplete" to e.response.complete,
                                                    ),
                                            ),
                                        "cache" to emptyMap<String, Any>(),
                                        "timings" to
                                            mapOf(
                                                "send" to 0,
                                                "wait" to (e.duration ?: 0),
                                                "receive" to 0,
                                            ),
                                        "_packageName" to e.packageName,
                                        "_appName" to e.appName,
                                        "_uid" to e.uid,
                                        "_appId" to e.appId,
                                        "_error" to e.error,
                                    )
                                },
                    )
            )
        )
}
