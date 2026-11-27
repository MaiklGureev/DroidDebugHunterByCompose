package hunter.features.help.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hunter.core.tr
import hunter.features.help.Markdown
import hunter.features.help.MarkdownDocument
import hunter.features.help.children
import hunter.ui.Accent
import hunter.ui.Action
import hunter.ui.Line
import hunter.ui.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image as MdImage
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak

@Composable
fun MarkdownViewer(
    source: String,
    modifier: Modifier = Modifier,
    anchor: String? = null,
    onLink: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val parsed by
        produceState<Result<MarkdownDocument>?>(null, source) {
            value = null
            value = withContext(Dispatchers.Default) { runCatching { Markdown.parse(source) } }
        }
    val document = parsed?.getOrNull()
    val scroll = rememberLazyListState()
    LaunchedEffect(document, anchor) {
        if (document != null) scroll.scrollToItem(document.anchors[anchor] ?: 0)
    }
    Surface(modifier, color = MaterialTheme.colors.surface, shape = RoundedCornerShape(10.dp)) {
        when {
            parsed == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            document == null ->
                Text(
                    parsed?.exceptionOrNull()?.message ?: tr("Guide unavailable"),
                    Modifier.padding(20.dp),
                    color = MaterialTheme.colors.error,
                )
            else ->
                Box(Modifier.fillMaxSize()) {
                    SelectionContainer {
                        LazyColumn(
                            state = scroll,
                            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                            contentPadding = PaddingValues(22.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            itemsIndexed(document.blocks) { _, block ->
                                MarkdownBlock(block, onLink, onCopy)
                            }
                        }
                    }
                    VerticalScrollbar(
                        rememberScrollbarAdapter(scroll),
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
        }
    }
}

@Composable
private fun MarkdownBlock(node: Node, onLink: (String) -> Unit, onCopy: (String) -> Unit) {
    when (node) {
        is Heading -> MarkdownText(node, onLink, heading = node.level)
        is Paragraph -> MarkdownText(node, onLink)
        is FencedCodeBlock -> MarkdownCode(node.literal, node.info, onCopy)
        is IndentedCodeBlock -> MarkdownCode(node.literal, "", onCopy)
        is HtmlBlock -> MarkdownCode(node.literal, "", onCopy)
        is ThematicBreak -> Divider(color = Line)
        is BulletList,
        is OrderedList ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.children().forEachIndexed { index, item ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (node is OrderedList) "${(node.markerStartNumber ?: 1) + index}."
                            else "•",
                            Modifier.widthIn(min = 20.dp),
                            lineHeight = 23.sp,
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item.children().forEach { MarkdownBlock(it, onLink, onCopy) }
                        }
                    }
                }
            }
        is BlockQuote ->
            Column(
                Modifier.fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            Accent.copy(alpha = .5f),
                            Offset.Zero,
                            Offset(0f, size.height),
                            3.dp.toPx(),
                        )
                    }
                    .padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                node.children().forEach { MarkdownBlock(it, onLink, onCopy) }
            }
        is TableBlock -> MarkdownTable(node, onLink)
        else -> node.children().forEach { MarkdownBlock(it, onLink, onCopy) }
    }
}

@Composable
private fun MarkdownText(
    node: Node,
    onLink: (String) -> Unit,
    heading: Int = 0,
    modifier: Modifier = Modifier,
    alignment: TextAlign = TextAlign.Start,
) {
    val codeBackground = MaterialTheme.colors.onSurface.copy(alpha = .08f)
    val linkColor = MaterialTheme.colors.primary
    val annotated =
        remember(node, codeBackground, linkColor, onLink) {
            buildAnnotatedString {
                fun appendNode(current: Node) {
                    when (current) {
                        is MdText -> append(current.literal)
                        is SoftLineBreak -> append(" ")
                        is HardLineBreak -> append("\n")
                        is Code ->
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = codeBackground,
                                )
                            ) {
                                append(current.literal)
                            }
                        is StrongEmphasis ->
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                current.children().forEach(::appendNode)
                            }
                        is Emphasis ->
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                current.children().forEach(::appendNode)
                            }
                        is Strikethrough ->
                            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                                current.children().forEach(::appendNode)
                            }
                        is Link -> {
                            val mark =
                                pushLink(
                                    LinkAnnotation.Clickable(
                                        current.destination,
                                        TextLinkStyles(
                                            SpanStyle(
                                                color = linkColor,
                                                textDecoration = TextDecoration.Underline,
                                            )
                                        ),
                                    ) {
                                        onLink(current.destination)
                                    }
                                )
                            current.children().forEach(::appendNode)
                            pop(mark)
                        }
                        is MdImage -> current.children().forEach(::appendNode)
                        is HtmlInline -> append(current.literal)
                        else -> current.children().forEach(::appendNode)
                    }
                }
                node.children().forEach(::appendNode)
            }
        }
    val size =
        when (heading) {
            1 -> 28.sp
            2 -> 22.sp
            3 -> 18.sp
            4,
            5,
            6 -> 16.sp
            else -> 14.sp
        }
    Text(
        annotated,
        modifier.fillMaxWidth(),
        fontSize = size,
        lineHeight = if (heading == 0) 23.sp else size * 1.4f,
        fontWeight = if (heading == 0) FontWeight.Normal else FontWeight.SemiBold,
        textAlign = alignment,
    )
}

@Composable
private fun MarkdownCode(code: String, language: String, onCopy: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colors.background,
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, Line),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(language, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
                Action(tr("Copy code")) { onCopy(code) }
            }
            Text(
                code.removeSuffix("\n"),
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 20.sp,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock, onLink: (String) -> Unit) {
    val rows = table.children().flatMap { it.children() }
    val columns = rows.maxOfOrNull { it.children().size } ?: return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxOf(maxWidth, 160.dp * columns)
        Column(Modifier.horizontalScroll(rememberScrollState()).width(width)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.children().filterIsInstance<TableCell>().forEachIndexed { index, cell ->
                        val weight =
                            if (columns == 2) {
                                if (index == 0) .3f else .7f
                            } else 1f
                        Surface(
                            Modifier.weight(weight).fillMaxHeight(),
                            color =
                                if (cell.isHeader) MaterialTheme.colors.primary.copy(alpha = .10f)
                                else MaterialTheme.colors.surface,
                            border = BorderStroke(.5.dp, Line),
                        ) {
                            MarkdownText(
                                cell,
                                onLink,
                                heading = if (cell.isHeader) 6 else 0,
                                modifier = Modifier.padding(10.dp),
                                alignment =
                                    when (cell.alignment) {
                                        TableCell.Alignment.CENTER -> TextAlign.Center
                                        TableCell.Alignment.RIGHT -> TextAlign.End
                                        else -> TextAlign.Start
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
