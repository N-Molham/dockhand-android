package pro.dockhand.mobile.api

import java.util.UUID
import kotlinx.serialization.Serializable

data class TerminalFeedEvent(
    val text: String,
    val id: String = UUID.randomUUID().toString()
)

@Serializable
private data class ShellInputPayload(val type: String, val data: String)

@Serializable
private data class ShellResizePayload(val type: String, val cols: Int, val rows: Int)

@Serializable
private data class ShellServerWireMessage(
    val type: String,
    val data: String? = null,
    val message: String? = null
)

sealed interface ShellServerMessage {
    data class Output(val data: String) : ShellServerMessage
    data class Error(val message: String) : ShellServerMessage
    data object Exit : ShellServerMessage
}

object ShellProtocol {

    fun encodeInput(data: String): String =
        dockhandJson.encodeToString(ShellInputPayload(type = "input", data = data))

    fun encodeResize(cols: Int, rows: Int): String =
        dockhandJson.encodeToString(ShellResizePayload(type = "resize", cols = cols, rows = rows))

    fun decodeServerMessage(text: String): ShellServerMessage? {
        val message = runCatching {
            dockhandJson.decodeFromString<ShellServerWireMessage>(text)
        }.getOrNull() ?: return null
        return when (message.type) {
            "output" -> message.data?.let(ShellServerMessage::Output)
            "error" -> ShellServerMessage.Error(message.message ?: "Shell error")
            "exit" -> ShellServerMessage.Exit
            else -> null
        }
    }
}
