package pro.dockhand.mobile.api

sealed class DockhandServiceError(message: String? = null) : Exception(message) {
    object InvalidResponse : DockhandServiceError("Invalid response from Dockhand")
    data class LogsUnavailable(val reason: String?) : DockhandServiceError(reason)
    data class Message(val text: String) : DockhandServiceError(text)
    data class UnexpectedStatus(val code: Int) : DockhandServiceError("Dockhand returned status $code")
}

enum class DockhandConnectionStage {
    HEALTH,
    ENVIRONMENTS,
    SELECTED_ENVIRONMENT
}

class DockhandConnectionStageException(
    val stage: DockhandConnectionStage,
    val underlying: Throwable
) : Exception(underlying)
