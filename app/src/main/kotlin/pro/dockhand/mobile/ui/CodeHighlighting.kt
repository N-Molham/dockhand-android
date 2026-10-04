package pro.dockhand.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign

enum class CodeTokenType {
    KEY,
    STRING,
    NUMBER,
    KEYWORD,
    COMMENT,
    PUNCTUATION,
    ANCHOR
}

data class CodeSpan(
    val start: Int,
    val end: Int,
    val type: CodeTokenType
)

data class CodeHighlightColors(
    val key: Color,
    val string: Color,
    val number: Color,
    val keyword: Color,
    val comment: Color,
    val punctuation: Color,
    val anchor: Color
)

@Composable
fun rememberCodeHighlightColors(): CodeHighlightColors = CodeHighlightColors(
    key = MaterialTheme.colorScheme.primary,
    string = MaterialTheme.colorScheme.secondary,
    number = MaterialTheme.colorScheme.tertiary,
    keyword = MaterialTheme.colorScheme.tertiary,
    comment = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    punctuation = MaterialTheme.colorScheme.onSurfaceVariant,
    anchor = MaterialTheme.colorScheme.tertiary
)

object YamlLineHighlighter {

    fun spans(line: String): List<CodeSpan> {
        if (line.isBlank()) return emptyList()
        val indent = line.indexOfFirst { !it.isWhitespace() }
        if (line[indent] == '#') return listOf(CodeSpan(indent, line.length, CodeTokenType.COMMENT))

        val spans = mutableListOf<CodeSpan>()
        val commentStart = findCommentStart(line)
        val contentEnd = commentStart ?: line.length

        val keyMatch = KEY_REGEX.find(line)
        var valueStart = indent
        if (keyMatch != null && keyMatch.range.last < contentEnd) {
            keyMatch.groups[1]?.let { dash ->
                spans.add(CodeSpan(dash.range.first, dash.range.last + 1, CodeTokenType.PUNCTUATION))
            }
            val keyGroup = keyMatch.groups[2]
            if (keyGroup != null) {
                spans.add(CodeSpan(keyGroup.range.first, keyGroup.range.last + 1, CodeTokenType.KEY))
            }
            val colon = keyMatch.groups[3]
            if (colon != null) {
                spans.add(CodeSpan(colon.range.first, colon.range.last + 1, CodeTokenType.PUNCTUATION))
                valueStart = colon.range.last + 1
            }
        } else {
            val dashMatch = DASH_REGEX.find(line)
            if (dashMatch != null && dashMatch.range.first < contentEnd) {
                spans.add(CodeSpan(dashMatch.range.first, dashMatch.range.last + 1, CodeTokenType.PUNCTUATION))
                valueStart = dashMatch.range.last + 1
            }
        }

        if (valueStart < contentEnd) {
            val value = line.substring(valueStart, contentEnd)
            val firstNonSpace = value.indexOfFirst { !it.isWhitespace() }
            val lastNonSpace = value.indexOfLast { !it.isWhitespace() }
            if (firstNonSpace >= 0) {
                spans.add(
                    CodeSpan(
                        valueStart + firstNonSpace,
                        valueStart + lastNonSpace + 1,
                        CodeTokenType.STRING
                    )
                )
            }
            VALUE_REGEX.findAll(value).forEach { match ->
                val type = when {
                    match.groups[1] != null -> CodeTokenType.ANCHOR
                    match.groups[2] != null -> CodeTokenType.NUMBER
                    match.groups[3] != null -> CodeTokenType.KEYWORD
                    else -> CodeTokenType.STRING
                }
                spans.add(CodeSpan(valueStart + match.range.first, valueStart + match.range.last + 1, type))
            }
        }

        if (commentStart != null) {
            spans.add(CodeSpan(commentStart, line.length, CodeTokenType.COMMENT))
        }
        return spans
    }

    private val KEY_REGEX = Regex("""^(\s*(?:- )?)([^\s:#][^:#]*?)(:)(?:\s|$)""")
    private val DASH_REGEX = Regex("""^\s*-\s""")
    private val VALUE_REGEX = Regex(
        """"(?:[^"\\]|\\.)*"|'(?:[^']|'')*'|([&*][A-Za-z0-9_.\-]+)|(-?\d+(?:\.\d+)?)|(\btrue\b|\bfalse\b|\bnull\b|\byes\b|\bno\b|\bon\b|\boff\b|~)"""
    )
}

object EnvLineHighlighter {

    fun spans(line: String): List<CodeSpan> {
        if (line.isBlank()) return emptyList()
        val indent = line.indexOfFirst { !it.isWhitespace() }
        if (line[indent] == '#') return listOf(CodeSpan(indent, line.length, CodeTokenType.COMMENT))

        val spans = mutableListOf<CodeSpan>()
        val commentStart = findCommentStart(line)
        val contentEnd = commentStart ?: line.length

        val keyMatch = KEY_REGEX.find(line)
        var valueStart = indent
        if (keyMatch != null && keyMatch.range.last < contentEnd) {
            spans.add(CodeSpan(keyMatch.groups[1]!!.range.first, keyMatch.groups[1]!!.range.last + 1, CodeTokenType.KEY))
            spans.add(CodeSpan(keyMatch.groups[2]!!.range.first, keyMatch.groups[2]!!.range.last + 1, CodeTokenType.PUNCTUATION))
            valueStart = keyMatch.groups[2]!!.range.last + 1
        }

        if (valueStart < contentEnd) {
            val value = line.substring(valueStart, contentEnd)
            val firstNonSpace = value.indexOfFirst { !it.isWhitespace() }
            val lastNonSpace = value.indexOfLast { !it.isWhitespace() }
            if (firstNonSpace >= 0) {
                spans.add(
                    CodeSpan(
                        valueStart + firstNonSpace,
                        valueStart + lastNonSpace + 1,
                        CodeTokenType.STRING
                    )
                )
            }
            VALUE_REGEX.findAll(value).forEach { match ->
                spans.add(
                    CodeSpan(
                        valueStart + match.range.first,
                        valueStart + match.range.last + 1,
                        CodeTokenType.STRING
                    )
                )
            }
        }

        if (commentStart != null) {
            spans.add(CodeSpan(commentStart, line.length, CodeTokenType.COMMENT))
        }
        return spans
    }

    private val KEY_REGEX = Regex("""^(\s*[A-Za-z_][A-Za-z0-9_.]*)(=)""")
    private val VALUE_REGEX = Regex(""""(?:[^"\\]|\\.)*"|'(?:[^']|'')*'""")
}

private fun findCommentStart(line: String): Int? {
    val indent = line.indexOfFirst { !it.isWhitespace() }
    var inSingle = false
    var inDouble = false
    var index = if (indent < 0) 0 else indent
    while (index < line.length) {
        val character = line[index]
        when {
            character == '\'' && !inDouble -> inSingle = !inSingle
            character == '"' && !inSingle -> inDouble = !inDouble
            character == '#' && !inSingle && !inDouble &&
                (index == 0 || line[index - 1].isWhitespace()) -> return index
        }
        index++
    }
    return null
}

class CodeHighlightCache(private val lexer: (String) -> List<CodeSpan>) {

    @Volatile
    private var spansByLine: Map<String, List<CodeSpan>> = emptyMap()

    fun update(text: String) {
        if (spansByLine.size > MAX_CACHED_LINES) {
            spansByLine = emptyMap()
        }
        val next = HashMap<String, List<CodeSpan>>(spansByLine)
        text.lineSequence().forEach { line ->
            if (!next.containsKey(line)) {
                next[line] = lexer(line)
            }
        }
        spansByLine = next
    }

    fun spansFor(line: String): List<CodeSpan> = spansByLine[line] ?: emptyList()

    private companion object {
        const val MAX_CACHED_LINES = 20_000
    }
}

fun codeHighlightTransformation(
    colors: CodeHighlightColors,
    cache: CodeHighlightCache
): VisualTransformation = VisualTransformation { text ->
    val raw = text.text
    if (raw.length > MAX_HIGHLIGHT_CHARS) {
        TransformedText(text, OffsetMapping.Identity)
    } else {
        val builder = AnnotatedString.Builder(raw)
        var lineStart = 0
        while (lineStart <= raw.length) {
            val newlineIndex = raw.indexOf('\n', lineStart)
            val lineEnd = if (newlineIndex == -1) raw.length else newlineIndex
            val line = raw.substring(lineStart, lineEnd)
            cache.spansFor(line).forEach { span ->
                builder.addStyle(
                    styleFor(colors, span.type),
                    lineStart + span.start,
                    lineStart + span.end
                )
            }
            if (newlineIndex == -1) break
            lineStart = newlineIndex + 1
        }
        TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
fun LineNumberGutter(
    lineCount: Int,
    textStyle: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val digits = lineCount.toString().length
    Column(modifier = modifier) {
        repeat(lineCount) { index ->
            Text(
                text = (index + 1).toString().padStart(digits),
                style = textStyle,
                color = color,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun styleFor(colors: CodeHighlightColors, type: CodeTokenType): SpanStyle = when (type) {
    CodeTokenType.KEY -> SpanStyle(color = colors.key, fontWeight = FontWeight.SemiBold)
    CodeTokenType.STRING -> SpanStyle(color = colors.string)
    CodeTokenType.NUMBER -> SpanStyle(color = colors.number)
    CodeTokenType.KEYWORD -> SpanStyle(color = colors.keyword)
    CodeTokenType.COMMENT -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
    CodeTokenType.PUNCTUATION -> SpanStyle(color = colors.punctuation)
    CodeTokenType.ANCHOR -> SpanStyle(color = colors.anchor, fontWeight = FontWeight.Medium)
}

private const val MAX_HIGHLIGHT_CHARS = 200_000
