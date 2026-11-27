package hunter.features.help

import hunter.core.tr
import java.io.File
import java.net.URI
import java.util.Locale
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser

internal fun Node.children(): List<Node> = buildList {
    var node = firstChild
    while (node != null) {
        add(node)
        node = node.next
    }
}

data class MarkdownDocument(val blocks: List<Node>, val anchors: Map<String, Int>)

object Markdown {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val parser =
        Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .maxOpenBlockParsers(64)
            .maxInlineNesting(64)
            .build()

    fun read(file: File): String {
        val bytes = file.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { tr("Markdown file exceeds 2 MB") }
        return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    fun parse(source: String): MarkdownDocument {
        require(source.length <= MAX_BYTES) { tr("Markdown file exceeds 2 MB") }
        val root = parser.parse(source.removePrefix("\uFEFF"))
        val pending = ArrayDeque<Pair<Node, Int>>()
        pending.add(root to 0)
        var count = 0
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            require(++count <= 50000 && depth <= 96) { tr("Markdown document is too complex") }
            node.children().forEach { pending.add(it to depth + 1) }
        }
        val blocks = root.children()
        val anchors = linkedMapOf<String, Int>()
        blocks.forEachIndexed { index, node ->
            if (node is Heading) {
                val slug =
                    plainText(node)
                        .lowercase(Locale.ROOT)
                        .replace(Regex("[^\\p{L}\\p{N}_\\-\\s]"), "")
                        .replace(Regex("\\s"), "-")
                var id = slug
                var suffix = 1
                while (id in anchors) id = "$slug-${suffix++}"
                anchors[id] = index
            }
        }
        return MarkdownDocument(blocks, anchors)
    }

    fun plainText(node: Node): String =
        when (node) {
            is Text -> node.literal
            is Code -> node.literal
            else -> node.children().joinToString("") { plainText(it) }
        }
}

data class MarkdownLocation(val file: File, val root: File, val anchor: String? = null)

sealed interface MarkdownTarget {
    data class Web(val uri: URI) : MarkdownTarget

    data class Document(val location: MarkdownLocation) : MarkdownTarget
}

fun isMarkdownFile(file: File): Boolean =
    file.extension.lowercase(Locale.ROOT) in setOf("md", "markdown") ||
        file.name in setOf("LICENSE", "NOTICE", "LICENSE.txt", "NOTICE.txt")

/** Local links stay in the chosen document directory; no scripts, executables or remote images. */
fun resolveMarkdownLink(location: MarkdownLocation, destination: String): MarkdownTarget? =
    runCatching {
            val uri = URI(destination)
            if (uri.isAbsolute) {
                if (uri.scheme.lowercase(Locale.ROOT) in setOf("http", "https") && uri.host != null)
                    MarkdownTarget.Web(uri)
                else null
            } else if (uri.authority != null || uri.query != null) null
            else {
                val resolved = location.file.toURI().resolve(uri)
                val file = File(URI("file", null, resolved.path, null, null)).canonicalFile
                val root = location.root.canonicalFile
                if (file.toPath().startsWith(root.toPath()) && isMarkdownFile(file))
                    MarkdownTarget.Document(MarkdownLocation(file, root, uri.fragment))
                else null
            }
        }
        .getOrNull()
