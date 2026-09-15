package pro.dockhand.mobile.api

import kotlinx.serialization.Serializable

sealed interface ContainerLogEvent {
    data object Connected : ContainerLogEvent
    data class Log(val text: String) : ContainerLogEvent
    data class ServerError(val message: String) : ContainerLogEvent
    data object Ended : ContainerLogEvent
}

@Serializable
private data class StreamedLogPayload(val text: String)

@Serializable
private data class StreamedLogErrorPayload(
    val error: String? = null,
    val reason: String? = null
)

class ContainerLogSSEDecoder {
    private var buffer = ByteArray(0)
    private var parser = ContainerLogSSEParser()

    fun consume(data: ByteArray): List<ContainerLogEvent> {
        buffer += data
        val events = mutableListOf<ContainerLogEvent>()
        var newlineIndex = buffer.indexOf(NEWLINE)
        while (newlineIndex >= 0) {
            val line = decodeLine(buffer.copyOfRange(0, newlineIndex))
            buffer = buffer.copyOfRange(newlineIndex + 1, buffer.size)
            parser.consume(line)?.let(events::add)
            newlineIndex = buffer.indexOf(NEWLINE)
        }
        return events
    }

    fun consume(text: String): List<ContainerLogEvent> =
        consume(text.toByteArray(Charsets.UTF_8))

    fun finish(): List<ContainerLogEvent> {
        if (buffer.isEmpty()) return emptyList()
        val line = decodeLine(buffer)
        buffer = ByteArray(0)
        return listOfNotNull(parser.consume(line))
    }

    private fun decodeLine(bytes: ByteArray): String {
        val line = String(bytes, Charsets.UTF_8)
        return if (line.endsWith("\r")) line.dropLast(1) else line
    }

    private companion object {
        const val NEWLINE: Byte = 0x0A
    }
}

class ContainerLogSSEParser {
    private var currentEvent: String? = null
    private val payloadLines = mutableListOf<String>()

    fun consume(line: String): ContainerLogEvent? {
        val normalizedLine = line.trim('\n', '\r')

        if (normalizedLine.isEmpty()) {
            val payload = payloadLines.joinToString("\n")
            val event = currentEvent
            currentEvent = null
            payloadLines.clear()
            return when (event) {
                "connected" -> ContainerLogEvent.Connected
                "log" -> ContainerLogEvent.Log(
                    dockhandJson.decodeFromString<StreamedLogPayload>(payload).text
                )
                "error" -> {
                    val decoded = runCatching {
                        dockhandJson.decodeFromString<StreamedLogErrorPayload>(payload)
                    }.getOrNull()
                    ContainerLogEvent.ServerError(decoded?.error ?: decoded?.reason ?: payload)
                }
                "end" -> ContainerLogEvent.Ended
                else -> null
            }
        }

        if (normalizedLine.startsWith(":")) return null
        if (normalizedLine.startsWith("event:")) {
            currentEvent = normalizedLine.removePrefix("event:").trim()
        } else if (normalizedLine.startsWith("data:")) {
            payloadLines.add(normalizedLine.removePrefix("data:").trim())
        }
        return null
    }
}
