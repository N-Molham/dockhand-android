package pro.dockhand.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTextTest {

    @Test
    fun stripRemovesSgrColorCodes() {
        assertEquals("aredb", AnsiText.strip("a\u001B[31mred\u001B[0mb"))
    }

    @Test
    fun stripRemovesCsiCursorCommands() {
        assertEquals("hello", AnsiText.strip("\u001B[2J\u001B[Hhello"))
    }

    @Test
    fun stripKeepsPlainText() {
        assertEquals("plain text 123", AnsiText.strip("plain text 123"))
    }

    @Test
    fun stripDropsIncompleteEscapeAtEnd() {
        assertEquals("abc", AnsiText.strip("abc\u001B["))
        assertEquals("abc", AnsiText.strip("abc\u001B[3"))
        assertEquals("abc", AnsiText.strip("abc\u001B"))
    }

    @Test
    fun stripRemovesOscTitleSequence() {
        assertEquals("body", AnsiText.strip("\u001B]0;title\u0007body"))
    }

    @Test
    fun stripDropsIncompleteOscSequence() {
        assertEquals("abc", AnsiText.strip("abc\u001B]0;title"))
    }

    @Test
    fun stripKeepsNewlinesAndTabs() {
        assertEquals("one\ntwo\tthree", AnsiText.strip("one\n\u001B[32mtwo\u001B[0m\tthree"))
    }

    @Test
    fun annotateKeepsTextAndAddsColorSpan() {
        val annotated = AnsiText.annotate("\u001B[31mred\u001B[0mplain")

        assertEquals("redplain", annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
        assertEquals(Color(0xFFCD3131), annotated.spanStyles.first().item.color)
    }

    @Test
    fun annotateAppliesBoldAndUnderline() {
        val annotated = AnsiText.annotate("\u001B[1;4mvery\u001B[0m")

        assertEquals("very", annotated.text)
        val first = annotated.spanStyles.first().item
        assertEquals(FontWeight.Bold, first.fontWeight)
        assertEquals(TextDecoration.Underline, first.textDecoration)
    }

    @Test
    fun annotateHandlesExtendedColorCodes() {
        val annotated = AnsiText.annotate("\u001B[38;5;196mx\u001B[38;2;1;2;3my\u001B[0m")

        assertEquals("xy", annotated.text)
        val spans = annotated.spanStyles
        assertTrue(spans.isNotEmpty())
        assertEquals(Color(1f, 0f, 0f), spans[0].item.color)
        assertEquals(Color(1f / 255f, 2f / 255f, 3f / 255f), spans[1].item.color)
    }

    @Test
    fun annotateDropsIncompleteSequence() {
        assertEquals("ab", AnsiText.annotate("ab\u001B[38;5;").text)
    }
}
