package com.workbuddy.watch.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.ui.theme.WatchPalette

/**
 * 轻量 Markdown 渲染：把助手回复里的语法标记转成可读的富文本。
 *
 * 腕上不做完整排版（表格、代码高亮都塞不下），只保留最能提升可读性的部分：
 * 标题去掉 `#` 并加粗、列表转成圆点、表格行拼成一条、行内代码等宽着色。
 * 不设 `maxLines`，长回复交给列表滚动，避免内容被截断。
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val styled = remember(text) { markdownToAnnotated(text) }
    Text(
        text = styled,
        style = MaterialTheme.typography.bodyMedium,
        color = WatchPalette.OnPill,
        modifier = modifier
    )
}

private val HEADING = Regex("""^#{1,6}\s+(.*)$""")
private val BULLET = Regex("""^[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\d+)[.)]\s+(.*)$""")
private val QUOTE = Regex("""^>\s?(.*)$""")
private val TABLE_SEPARATOR = Regex("""^\|?[\s:|-]+\|?$""")
private val FENCE = Regex("""^```.*$""")

/** 把 Markdown 源码转成带样式的文本；换行与段落顺序保持原样。 */
private fun markdownToAnnotated(src: String): AnnotatedString = buildAnnotatedString {
    // 表格的 |---|---| 分隔行没有信息量，先整行丢掉
    val lines = src.lines().filterNot { TABLE_SEPARATOR.matches(it.trim()) }
    var inCode = false

    lines.forEachIndexed { index, raw ->
        val line = raw.trimEnd()
        if (FENCE.matches(line.trim())) {
            inCode = !inCode
            return@forEachIndexed
        }
        if (index > 0) append('\n')

        when {
            inCode -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = WatchPalette.OnPillVariant
                )
            ) { append(line) }

            line.trimStart().startsWith("|") -> appendTableRow(line)

            HEADING.matches(line) -> withStyle(
                SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            ) { appendInline(HEADING.matchEntire(line)!!.groupValues[1]) }

            BULLET.matches(line) -> {
                append("· ")
                appendInline(BULLET.matchEntire(line)!!.groupValues[1])
            }

            NUMBERED.matches(line) -> {
                val m = NUMBERED.matchEntire(line)!!
                append("${m.groupValues[1]}. ")
                appendInline(m.groupValues[2])
            }

            QUOTE.matches(line) -> {
                append("｜ ")
                appendInline(QUOTE.matchEntire(line)!!.groupValues[1])
            }

            else -> appendInline(line)
        }
    }
}

/** 表格行：`| a | b |` → `a   b`，腕上没有列宽可言。 */
private fun AnnotatedString.Builder.appendTableRow(line: String) {
    val cells = line.trim().trim('|').split('|').map { it.trim() }
    cells.forEachIndexed { i, cell ->
        if (i > 0) append("  ")
        appendInline(cell)
    }
}

/** 处理行内的 `**粗体**`、`` `代码` `` 与 `[文字](链接)`。 */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append("**")
                    i += 2
                }
            }

            text.startsWith("__", i) -> {
                val end = text.indexOf("__", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append("__")
                    i += 2
                }
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = WatchPalette.Accent
                        )
                    ) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }

            text[i] == '[' -> {
                val close = text.indexOf(']', i + 1)
                val open = if (close > 0) text.indexOf('(', close + 1) else -1
                val end = if (open > 0) text.indexOf(')', open + 1) else -1
                if (close > i + 1 && open == close + 1 && end > open) {
                    withStyle(SpanStyle(color = WatchPalette.Accent)) {
                        append(text.substring(i + 1, close))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }

            else -> {
                append(text[i])
                i++
            }
        }
    }
}
