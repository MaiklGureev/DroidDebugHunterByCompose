package hunter

import hunter.features.help.Markdown
import hunter.features.help.MarkdownLocation
import hunter.features.help.MarkdownTarget
import hunter.features.help.children
import hunter.features.help.isMarkdownFile
import hunter.features.help.resolveMarkdownLink
import java.io.File
import java.nio.file.Path
import kotlin.test.*
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.*
import org.junit.jupiter.api.io.TempDir

class MarkdownTest {
    @Test
    fun `headings paragraphs lists and quotes are parsed as formatted blocks`() {
        val document =
            Markdown.parse(
                "# Heading\n\nA **bold** and *italic* paragraph.\nWrapped line.\n\n- First\n- Second\n\n3. Third\n4. Fourth\n\n> Quote\n\n---"
            )
        assertTrue(document.blocks[0] is Heading)
        val paragraph = document.blocks.filterIsInstance<Paragraph>().single()
        assertTrue(paragraph.children().any { it is StrongEmphasis })
        assertTrue(paragraph.children().any { it is Emphasis })
        assertTrue(paragraph.children().any { it is SoftLineBreak })
        assertEquals(2, document.blocks.filterIsInstance<BulletList>().single().children().size)
        assertEquals(3, document.blocks.filterIsInstance<OrderedList>().single().markerStartNumber)
        assertTrue(document.blocks.any { it is BlockQuote })
        assertTrue(document.blocks.any { it is ThematicBreak })
    }

    @Test
    fun `GFM tables retain header alignment links and inline formatting`() {
        val document =
            Markdown.parse(
                "| Name | Description |\n| :--- | ---: |\n| **ADB** | [Guide](ADB_TOOLS.md) and ~~old~~ |"
            )
        val rows =
            document.blocks.filterIsInstance<TableBlock>().single().children().flatMap {
                it.children()
            }
        val header = rows[0].children().filterIsInstance<TableCell>()
        assertTrue(header.all { it.isHeader })
        assertEquals(TableCell.Alignment.RIGHT, header[1].alignment)
        val body = rows[1].children()
        assertTrue(body[0].children().single() is StrongEmphasis)
        assertTrue(body[1].children().any { it is Link && it.destination == "ADB_TOOLS.md" })
        assertTrue(body[1].children().any { it is Strikethrough })
    }

    @Test
    fun `fenced code and raw HTML remain literal content`() {
        val document =
            Markdown.parse(
                "```xml\n<config>&value</config>\n```\n\n<script>alert('test')</script>\n\n![Alt text](https://example.test/image.png)"
            )
        val code = document.blocks.filterIsInstance<FencedCodeBlock>().single()
        assertEquals("xml", code.info)
        assertEquals("<config>&value</config>\n", code.literal)
        assertTrue(document.blocks.any { it is HtmlBlock })
        val image = document.blocks.filterIsInstance<Paragraph>().single().children().single()
        assertTrue(image is Image)
        assertEquals("Alt text", Markdown.plainText(image))
    }

    @Test
    fun `Unicode heading anchors support emphasis code and duplicates`() {
        val document =
            Markdown.parse(
                "# Содержимое *запросов* и `ответов`\n\nText\n\n## Содержимое запросов и ответов"
            )
        assertEquals(0, document.anchors["содержимое-запросов-и-ответов"])
        assertEquals(2, document.anchors["содержимое-запросов-и-ответов-1"])
    }

    @Test
    fun `local links and anchors resolve inside the documentation root`(@TempDir root: Path) {
        val current = MarkdownLocation(root.resolve("USAGE.md").toFile(), root.toFile())
        val linked =
            resolveMarkdownLink(current, "Android%20setup.md#usb") as MarkdownTarget.Document
        assertEquals(root.resolve("Android setup.md").toFile().canonicalFile, linked.location.file)
        assertEquals("usb", linked.location.anchor)
        val anchor = resolveMarkdownLink(current, "#section") as MarkdownTarget.Document
        assertEquals(current.file.canonicalFile, anchor.location.file)
        assertTrue(resolveMarkdownLink(current, "LICENSE") is MarkdownTarget.Document)
        assertTrue(
            resolveMarkdownLink(current, "https://example.test/guide?q=1#part")
                is MarkdownTarget.Web
        )
        for (link in
            listOf(
                "../outside.md",
                "%2e%2e/outside.md",
                "/outside.md",
                "//example.test/remote.md",
                "file:///outside.md",
                "javascript:alert(1)",
                "data:text/html,test",
                "run.exe",
                "settings.properties",
            )) {
            assertNull(resolveMarkdownLink(current, link), link)
        }
    }

    @Test
    fun `document size and complexity are bounded`(@TempDir root: Path) {
        val file = root.resolve("large.md").toFile()
        file.writeBytes(ByteArray(Markdown.MAX_BYTES + 1))
        assertFailsWith<IllegalArgumentException> { Markdown.read(file) }
        assertFailsWith<IllegalArgumentException> {
            Markdown.parse("a".repeat(Markdown.MAX_BYTES + 1))
        }
        assertFailsWith<IllegalArgumentException> { Markdown.parse("word\n\n".repeat(26000)) }
        file.writeText("\uFEFF# Title", Charsets.UTF_8)
        assertEquals("# Title", Markdown.read(file))
        assertTrue(isMarkdownFile(File("Guide.MD")))
        assertFalse(isMarkdownFile(File("program.exe")))
    }

    @Test
    fun `all bundled guides parse and link to bundled files`(@TempDir root: Path) {
        val loader = javaClass.classLoader
        val files =
            loader.getResourceAsStream("runtime-manifest.txt")!!.bufferedReader().use {
                it.readLines()
            }
        val guides = files.filter { it.startsWith("runtime/help/") && it.endsWith(".md") }
        assertEquals(16, guides.size)
        val base = root.toFile().canonicalFile
        val help = File(base, "runtime/help")
        for (path in guides) {
            val source = loader.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
            val document = Markdown.parse(source)
            assertTrue(document.blocks.isNotEmpty(), path)
            assertTrue(document.blocks.first() is Heading, path)
            val location = MarkdownLocation(File(base, path), help)
            val pending = ArrayDeque(document.blocks)
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                pending.addAll(node.children())
                if (node is Link) {
                    val description = "$path -> ${node.destination}"
                    val target = assertNotNull(resolveMarkdownLink(location, node.destination), description)
                    if (target is MarkdownTarget.Document) {
                        assertContains(files, target.location.file.relativeTo(base).invariantSeparatorsPath, description)
                    }
                }
            }
        }
    }
}
