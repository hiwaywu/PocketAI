package com.pocketai.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** A small Markdown renderer for chat replies: headings, lists, code, quotes, tables, links. */
sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Item(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val ORDERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim())
        para.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
            }
            t.isEmpty() -> flush()
            HEADING.matches(t) -> {
                flush()
                val m = HEADING.find(t)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trimEnd('#', ' '))
            }
            RULE.matches(t) -> { flush(); out += MdBlock.Rule }
            t.startsWith(">") -> {
                flush()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    q.append(lines[i].trim().removePrefix(">").trim()).append('\n')
                    i++
                }
                out += MdBlock.Quote(q.toString().trim())
                continue
            }
            t.startsWith("|") -> {
                flush()
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val r = lines[i].trim()
                    if (!TABLE_SEP.matches(r)) rows += r.trim('|').split("|").map { it.trim() }
                    i++
                }
                if (rows.isNotEmpty()) out += MdBlock.Table(rows)
                continue
            }
            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                out += MdBlock.Item("•", m.groupValues[2], m.groupValues[1].length / 2)
            }
            ORDERED.matches(line) -> {
                flush()
                val m = ORDERED.find(line)!!
                out += MdBlock.Item(m.groupValues[2] + ".", m.groupValues[3], m.groupValues[1].length / 2)
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(t)
            }
        }
        i++
    }
    flush()
    return out
}

private val INLINE = Regex(
    """(`[^`\n]+`)""" +                                              // 1 code
        """|(\*\*(.+?)\*\*)""" +                                     // 2,3 bold
        """|(__(.+?)__)""" +                                         // 4,5 bold
        """|(~~(.+?)~~)""" +                                         // 6,7 strike
        """|(\[([^\]\n]+)]\(([^)\s]+)\))""" +                        // 8,9,10 link
        """|(https?://[^\s<>()\[\]]*[^\s<>()\[\].,;:!?'"*_])""" +   // 11 bare url
        """|((?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?!\*))""" +     // 12,13 italic
        """|((?<![\w_])_(?![\s_])(.+?)(?<![\s_])_(?![\w_]))"""       // 14,15 italic
)

fun inlineMarkdown(text: String, c: ColorScheme, onLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString { appendInline(this, text, c, onLink) }

private fun appendInline(b: AnnotatedString.Builder, text: String, c: ColorScheme, onLink: (String) -> Unit) {
    var last = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > last) b.append(text.substring(last, m.range.first))
        val g = m.groups
        fun link(label: String, url: String) {
            val annotation = LinkAnnotation.Clickable(
                tag = url,
                styles = TextLinkStyles(SpanStyle(color = c.primary, textDecoration = TextDecoration.Underline)),
                linkInteractionListener = { onLink(url) },
            )
            b.withLink(annotation) { append(label) }
        }
        when {
            g[1] != null -> b.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.surfaceVariant, fontSize = 0.9.em)) {
                append(g[1]!!.value.trim('`'))
            }
            g[2] != null -> b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(this, g[3]!!.value, c, onLink) }
            g[4] != null -> b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(this, g[5]!!.value, c, onLink) }
            g[6] != null -> b.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(this, g[7]!!.value, c, onLink) }
            g[8] != null -> link(g[9]!!.value, g[10]!!.value)
            g[11] != null -> link(g[11]!!.value, g[11]!!.value)
            g[12] != null -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(this, g[13]!!.value, c, onLink) }
            g[14] != null -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(this, g[15]!!.value, c, onLink) }
        }
        last = m.range.last + 1
    }
    if (last < text.length) b.append(text.substring(last))
}

@Composable
fun MarkdownText(text: String, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val c = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Para -> Text(inlineMarkdown(block.text, c, onLink), style = type.bodyLarge)
                is MdBlock.Heading -> Text(
                    inlineMarkdown(block.text, c, onLink),
                    style = when (block.level) { 1 -> type.titleLarge; 2 -> type.titleMedium; else -> type.titleSmall },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.Item -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.marker, style = type.bodyLarge, modifier = Modifier.widthIn(min = 22.dp))
                    Text(inlineMarkdown(block.text, c, onLink), style = type.bodyLarge)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.outline))
                    Text(
                        inlineMarkdown(block.text, c, onLink),
                        style = type.bodyLarge,
                        color = c.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is MdBlock.Table -> TableBlock(block.rows, c, onLink)
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    block.lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code", modifier = Modifier.size(18.dp))
                }
            }
            Text(
                block.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>, c: ColorScheme, onLink: (String) -> Unit) {
    val cols = rows.maxOf { it.size }
    val style = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Each column is as wide as its longest cell on one line (measured at the phone's real font size, so a
    // time like "11:35" never breaks in two), up to 260dp; longer cells wrap.
    val widths = remember(rows, style, density) {
        (0 until cols).map { ci ->
            val px = rows.mapIndexed { ri, row ->
                val cell = row.getOrNull(ci).orEmpty()
                if (cell.isEmpty()) 0 else measurer.measure(
                    inlineMarkdown(cell, c) {},
                    style = if (ri == 0) style.copy(fontWeight = FontWeight.Bold) else style,
                    softWrap = false,
                    maxLines = 1,
                ).size.width
            }.maxOrNull() ?: 0
            with(density) { (px.toDp() + 18.dp).coerceIn(40.dp, 260.dp) }
        }
    }
    val total = widths.fold(0.dp) { acc, w -> acc + w }
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.border(1.dp, c.outlineVariant, RoundedCornerShape(6.dp))) {
            rows.forEachIndexed { ri, row ->
                Row(Modifier.background(if (ri == 0) c.surfaceVariant else Color.Transparent)) {
                    for (ci in 0 until cols) {
                        Text(
                            inlineMarkdown(row.getOrElse(ci) { "" }, c, onLink),
                            style = style,
                            fontWeight = if (ri == 0) FontWeight.Bold else null,
                            modifier = Modifier.width(widths[ci]).padding(8.dp),
                        )
                    }
                }
                if (ri < rows.lastIndex) HorizontalDivider(Modifier.width(total), color = c.outlineVariant)
            }
        }
    }
}
