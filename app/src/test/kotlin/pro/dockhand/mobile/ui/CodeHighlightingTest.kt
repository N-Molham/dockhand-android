package pro.dockhand.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightingTest {

    private fun types(spans: List<CodeSpan>): List<CodeTokenType> = spans.map { it.type }

    private fun spanOfType(line: String, type: CodeTokenType): CodeSpan? =
        YamlLineHighlighter.spans(line).firstOrNull { it.type == type }

    @Test
    fun commentLineIsSingleCommentSpan() {
        val spans = YamlLineHighlighter.spans("# pinned version")
        assertEquals(listOf(CodeTokenType.COMMENT), types(spans))
        assertEquals(0, spans.first().start)
        assertEquals("# pinned version".length, spans.first().end)
    }

    @Test
    fun keyLineMarksKeyAndColon() {
        val spans = YamlLineHighlighter.spans("services:")
        assertTrue(spans.any { it.type == CodeTokenType.KEY && it.start == 0 && it.end == "services".length })
        assertTrue(spans.any { it.type == CodeTokenType.PUNCTUATION && it.start == "services".length })
    }

    @Test
    fun indentedKeyWithImageValue() {
        val line = "  image: nginx:latest"
        val key = spanOfType(line, CodeTokenType.KEY)
        assertEquals(2, key?.start)
        assertEquals(7, key?.end)
        val value = spanOfType(line, CodeTokenType.STRING)
        assertEquals("nginx:latest", line.substring(value!!.start, value.end))
    }

    @Test
    fun numberAndKeywordValuesAreClassified() {
        val ports = YamlLineHighlighter.spans("    - 8080:80")
        assertTrue(ports.any { it.type == CodeTokenType.PUNCTUATION })
        assertTrue(ports.any { it.type == CodeTokenType.NUMBER && "8080" == "    - 8080:80".substring(it.start, it.end) })

        val privileged = YamlLineHighlighter.spans("  privileged: true")
        assertTrue(privileged.any { it.type == CodeTokenType.KEYWORD })
    }

    @Test
    fun inlineCommentOnlyWhenWhitespacePrecedesHash() {
        val withComment = YamlLineHighlighter.spans("  image: nginx # pinned")
        assertTrue(withComment.any { it.type == CodeTokenType.COMMENT })

        val withoutComment = YamlLineHighlighter.spans("  image: nginx#fragment")
        assertTrue(withoutComment.none { it.type == CodeTokenType.COMMENT })
    }

    @Test
    fun quotedListValueAndAnchorDetected() {
        val quoted = YamlLineHighlighter.spans("    - '30330:8080'")
        assertTrue(quoted.any { it.type == CodeTokenType.STRING && it.start > 4 })

        val anchor = YamlLineHighlighter.spans("  x-logging: &default-logging")
        assertTrue(anchor.any { it.type == CodeTokenType.ANCHOR })
    }

    @Test
    fun envKeyValueAndComment() {
        val spans = EnvLineHighlighter.spans("TZ=Europe/Berlin # timezone")
        assertTrue(spans.any { it.type == CodeTokenType.KEY && it.start == 0 })
        assertTrue(spans.any { it.type == CodeTokenType.PUNCTUATION })
        val value = spans.first { it.type == CodeTokenType.STRING }
        assertEquals("Europe/Berlin", "TZ=Europe/Berlin # timezone".substring(value.start, value.end))
        assertEquals(CodeTokenType.COMMENT, spans.last().type)
    }

    @Test
    fun envCommentAndQuotedValue() {
        val comment = EnvLineHighlighter.spans("# secrets")
        assertEquals(listOf(CodeTokenType.COMMENT), types(comment))

        val quoted = EnvLineHighlighter.spans("""PASSWORD="s3cret value"""")
        assertTrue(quoted.any { it.type == CodeTokenType.STRING })
    }

    @Test
    fun cacheReusesLexedLinesAndHandlesUnknownLines() {
        val cache = CodeHighlightCache(YamlLineHighlighter::spans)
        cache.update("services:\n  image: nginx:latest")

        assertTrue(cache.spansFor("services:").isNotEmpty())
        assertTrue(cache.spansFor("  image: nginx:latest").isNotEmpty())
        assertEquals(emptyList<CodeSpan>(), cache.spansFor("  new-key: value"))

        cache.update("services:\n  image: nginx:latest\n  image: nginx:latest")
        assertTrue(cache.spansFor("  image: nginx:latest").isNotEmpty())
    }

    @Test
    fun transformationKeepsTextAndIdentityMapping() {
        val cache = CodeHighlightCache(YamlLineHighlighter::spans)
        val colors = CodeHighlightColors(
            key = androidx.compose.ui.graphics.Color.Black,
            string = androidx.compose.ui.graphics.Color.Black,
            number = androidx.compose.ui.graphics.Color.Black,
            keyword = androidx.compose.ui.graphics.Color.Black,
            comment = androidx.compose.ui.graphics.Color.Gray,
            punctuation = androidx.compose.ui.graphics.Color.Gray,
            anchor = androidx.compose.ui.graphics.Color.Gray
        )
        cache.update("services:\n  image: nginx:latest")

        val result = codeHighlightTransformation(colors, cache)
            .filter(androidx.compose.ui.text.AnnotatedString("services:\n  image: nginx:latest"))

        assertEquals("services:\n  image: nginx:latest", result.text.text)
        assertTrue(result.text.spanStyles.isNotEmpty())
        assertEquals(0, result.offsetMapping.originalToTransformed(0))
        assertEquals(10, result.offsetMapping.transformedToOriginal(10))
    }
}
