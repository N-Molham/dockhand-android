package pro.dockhand.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

object AnsiText {

    private const val ESC = '\u001B'

    fun strip(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == ESC) {
                index = escapeAt(text, index).nextIndex
                continue
            }
            builder.append(character)
            index++
        }
        return builder.toString()
    }

    fun annotate(text: String): AnnotatedString = buildAnnotatedString {
        var style = SpanStyle()
        val run = StringBuilder()
        var index = 0

        fun flush() {
            if (run.isEmpty()) return
            pushStyle(style)
            append(run.toString())
            pop()
            run.setLength(0)
        }

        while (index < text.length) {
            val character = text[index]
            if (character == ESC) {
                val match = escapeAt(text, index)
                if (match.sgr != null) {
                    flush()
                    style = applySgr(style, match.sgr)
                }
                index = match.nextIndex
                continue
            }
            run.append(character)
            index++
        }
        flush()
    }

    private data class EscapeMatch(val nextIndex: Int, val sgr: List<Int>?)

    private fun escapeAt(text: String, escapeIndex: Int): EscapeMatch {
        val next = escapeIndex + 1
        if (next >= text.length) return EscapeMatch(text.length, null)
        return when (text[next]) {
            '[' -> csiAt(text, next + 1)
            ']' -> EscapeMatch(oscEnd(text, next + 1), null)
            'P', '^', '_', 'X' -> EscapeMatch(stringEnd(text, next + 1), null)
            else -> EscapeMatch(next + 1, null)
        }
    }

    private fun csiAt(text: String, start: Int): EscapeMatch {
        var index = start
        while (index < text.length) {
            val code = text[index].code
            if (code in 0x40..0x7E) {
                val sgr = if (text[index] == 'm') parseSgr(text.substring(start, index)) else null
                return EscapeMatch(index + 1, sgr)
            }
            if (code !in 0x20..0x3F) return EscapeMatch(index, null)
            index++
        }
        return EscapeMatch(text.length, null)
    }

    private fun oscEnd(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            val character = text[index]
            if (character == '\u0007') return index + 1
            if (character == ESC && index + 1 < text.length && text[index + 1] == '\\') return index + 2
            index++
        }
        return text.length
    }

    private fun stringEnd(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            if (text[index] == ESC && index + 1 < text.length && text[index + 1] == '\\') return index + 2
            index++
        }
        return text.length
    }

    private fun parseSgr(parameters: String): List<Int> {
        if (parameters.isEmpty()) return listOf(0)
        return parameters.split(';').map { it.trim().toIntOrNull() ?: 0 }
    }

    private fun applySgr(style: SpanStyle, parameters: List<Int>): SpanStyle {
        var updated = style
        var index = 0
        while (index < parameters.size) {
            when (val code = parameters[index]) {
                0 -> updated = SpanStyle()
                1 -> updated = updated.copy(fontWeight = FontWeight.Bold)
                3 -> updated = updated.copy(fontStyle = FontStyle.Italic)
                4 -> updated = updated.copy(textDecoration = TextDecoration.Underline)
                22 -> updated = updated.copy(fontWeight = null)
                23 -> updated = updated.copy(fontStyle = null)
                24 -> updated = updated.copy(textDecoration = null)
                in 30..37 -> updated = updated.copy(color = ANSI_PALETTE[code - 30])
                39 -> updated = updated.copy(color = Color.Unspecified)
                in 90..97 -> updated = updated.copy(color = ANSI_PALETTE[code - 90 + 8])
                38 -> {
                    val (color, consumed) = extendedColor(parameters, index)
                    if (color != Color.Unspecified) updated = updated.copy(color = color)
                    index += consumed
                    continue
                }
                48 -> {
                    val (color, consumed) = extendedColor(parameters, index)
                    if (color != Color.Unspecified) updated = updated.copy(background = color)
                    index += consumed
                    continue
                }
                in 40..47 -> updated = updated.copy(background = ANSI_PALETTE[code - 40])
                49 -> updated = updated.copy(background = Color.Unspecified)
                else -> Unit
            }
            index++
        }
        return updated
    }

    private fun extendedColor(parameters: List<Int>, index: Int): Pair<Color, Int> {
        if (index + 1 >= parameters.size) return Color.Unspecified to 1
        return when (parameters[index + 1]) {
            5 -> {
                if (index + 2 >= parameters.size) {
                    Color.Unspecified to 2
                } else {
                    color256(parameters[index + 2]) to 3
                }
            }
            2 -> {
                if (index + 4 >= parameters.size) {
                    Color.Unspecified to 2
                } else {
                    Color(
                        red = parameters[index + 2].coerceIn(0, 255) / 255f,
                        green = parameters[index + 3].coerceIn(0, 255) / 255f,
                        blue = parameters[index + 4].coerceIn(0, 255) / 255f
                    ) to 5
                }
            }
            else -> Color.Unspecified to 1
        }
    }

    private fun color256(index: Int): Color = when {
        index in ANSI_PALETTE.indices -> ANSI_PALETTE[index]
        index in 16..231 -> {
            val offset = index - 16
            Color(
                red = COLOR_CUBE[offset / 36] / 255f,
                green = COLOR_CUBE[(offset % 36) / 6] / 255f,
                blue = COLOR_CUBE[offset % 6] / 255f
            )
        }
        index in 232..255 -> {
            val level = (8 + (index - 232) * 10).coerceIn(0, 255)
            val value = level / 255f
            Color(red = value, green = value, blue = value)
        }
        else -> Color.Unspecified
    }

    private val ANSI_PALETTE = listOf(
        Color(0xFF1E1E1E),
        Color(0xFFCD3131),
        Color(0xFF0DBC79),
        Color(0xFFE5E510),
        Color(0xFF2472C8),
        Color(0xFFBC3FBC),
        Color(0xFF11A8CD),
        Color(0xFFE5E5E5),
        Color(0xFF666666),
        Color(0xFFF14C4C),
        Color(0xFF23D18B),
        Color(0xFFF5F543),
        Color(0xFF3B8EEA),
        Color(0xFFD670D6),
        Color(0xFF29B8DB),
        Color(0xFFFFFFFF)
    )

    private val COLOR_CUBE = floatArrayOf(0f, 95f, 135f, 175f, 215f, 255f)
}
