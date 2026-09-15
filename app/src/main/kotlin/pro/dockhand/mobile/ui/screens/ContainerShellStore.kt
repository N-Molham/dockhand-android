package pro.dockhand.mobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import pro.dockhand.mobile.api.ContainerShellDetectionResult
import pro.dockhand.mobile.api.DockhandApi
import pro.dockhand.mobile.api.ShellProtocol
import pro.dockhand.mobile.api.ShellServerMessage
import pro.dockhand.mobile.api.TerminalFeedEvent
import pro.dockhand.mobile.api.dockhandUserFacingMessage
import pro.dockhand.mobile.api.isDockhandCancellation

interface ShellWebSocket {
    var listener: ShellWebSocketListener?
    fun send(text: String)
    fun cancel()
}

interface ShellWebSocketListener {
    fun onText(text: String)
    fun onFailure(error: Throwable)
    fun onClosed()
}

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

enum class ContainerShellStatus(val label: String) {
    IDLE("Idle"),
    DETECTING("Detecting shells"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    DISCONNECTED("Disconnected"),
    ENDED("Session ended"),
    ERROR("Error")
}

data class ContainerShellUserOption(val value: String, val label: String)

val containerShellUserPresets = listOf(
    ContainerShellUserOption("root", "root"),
    ContainerShellUserOption("nobody", "nobody"),
    ContainerShellUserOption("", "Container default")
)

class ContainerShellStore(
    private val api: DockhandApi,
    private val environmentID: Int,
    private val containerID: String,
    private val containerName: String,
    private val profileID: String?,
    private val webSocketFactory: (Request) -> ShellWebSocket,
    private val scope: CoroutineScope,
    private val keyValueStore: KeyValueStore,
    private val resizeDebounceMillis: Long = 300L
) {
    private val preferenceKey =
        "dockhand.shell.${profileID ?: "none"}.$environmentID.$containerID"

    var detectionResult by mutableStateOf<ContainerShellDetectionResult?>(null)
        private set
    var isDetectingShells by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selectedShell by mutableStateOf(keyValueStore.get("$preferenceKey.shell") ?: DEFAULT_SHELL)
        private set
    var selectedUser by mutableStateOf(keyValueStore.get("$preferenceKey.user") ?: DEFAULT_USER)
        private set
    var customUserInput by mutableStateOf("")
    var customUsers by mutableStateOf(loadCustomUsers())
        private set
    var status by mutableStateOf(ContainerShellStatus.IDLE)
        private set
    var isConnected by mutableStateOf(false)
        private set
    var feedEvents by mutableStateOf<List<TerminalFeedEvent>>(emptyList())
        private set

    private var socket: ShellWebSocket? = null
    private var resizeJob: Job? = null
    private var lastCols = DEFAULT_COLS
    private var lastRows = DEFAULT_ROWS

    private val listener = object : ShellWebSocketListener {
        override fun onText(text: String) {
            handlePayload(text)
        }

        override fun onFailure(error: Throwable) {
            handleSocketFailure(error)
        }

        override fun onClosed() {
            socket = null
            isConnected = false
            if (status == ContainerShellStatus.CONNECTED || status == ContainerShellStatus.CONNECTING) {
                status = ContainerShellStatus.DISCONNECTED
            }
        }
    }

    suspend fun detectShells() {
        isDetectingShells = true
        error = null
        if (status == ContainerShellStatus.IDLE) status = ContainerShellStatus.DETECTING
        try {
            val result = api.fetchContainerShells(containerID, environmentID)
            detectionResult = result
            result.bestShell(selectedShell)?.let { selectedShell = it }
            val detectionError = result.error
            if (!detectionError.isNullOrEmpty()) error = detectionError
            if (status == ContainerShellStatus.DETECTING) status = ContainerShellStatus.IDLE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure.isDockhandCancellation) {
                status = ContainerShellStatus.IDLE
            } else {
                error = failure.dockhandUserFacingMessage
                status = ContainerShellStatus.ERROR
            }
        } finally {
            isDetectingShells = false
        }
    }

    fun connect() {
        disconnect(ContainerShellStatus.CONNECTING)
        error = null
        status = ContainerShellStatus.CONNECTING
        appendSystemLine("Connecting to $containerName...")
        appendSystemLine(
            "Shell: $selectedShell, User: ${selectedUser.ifEmpty { "container default" }}"
        )
        persistSelection()

        val created = try {
            val request = api.makeContainerShellRequest(
                containerID,
                environmentID,
                selectedShell,
                selectedUser
            )
            webSocketFactory(request)
        } catch (failure: Throwable) {
            if (failure.isDockhandCancellation) {
                isConnected = false
                status = ContainerShellStatus.DISCONNECTED
                return
            }
            val message = failure.dockhandUserFacingMessage
            isConnected = false
            status = ContainerShellStatus.ERROR
            error = message
            appendErrorLine(message)
            return
        }

        socket = created
        created.listener = listener
        isConnected = true
        status = ContainerShellStatus.CONNECTED
        try {
            created.send(ShellProtocol.encodeResize(lastCols, lastRows))
        } catch (failure: Throwable) {
            handleSocketFailure(failure)
        }
    }

    fun reconnect() {
        appendSystemLine("Reconnecting...")
        connect()
    }

    fun disconnect(status: ContainerShellStatus = ContainerShellStatus.DISCONNECTED) {
        resizeJob?.cancel()
        resizeJob = null
        val active = socket
        socket = null
        active?.listener = null
        active?.cancel()
        isConnected = false
        this.status = status
    }

    fun sendInput(text: String) {
        if (!isConnected || text.isEmpty()) return
        val active = socket ?: return
        try {
            active.send(ShellProtocol.encodeInput(text))
        } catch (failure: Throwable) {
            handleSocketFailure(failure)
        }
    }

    fun sendCommand(text: String) {
        if (text.isEmpty()) return
        sendInput("$text\n")
    }

    fun requestResize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(resizeDebounceMillis)
            sendResize(cols, rows)
        }
    }

    fun sendResize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastCols = cols
        lastRows = rows
        if (!isConnected) return
        val active = socket ?: return
        try {
            active.send(ShellProtocol.encodeResize(cols, rows))
        } catch (failure: Throwable) {
            handleSocketFailure(failure)
        }
    }

    fun selectShell(shell: String) {
        selectedShell = shell
        persistSelection()
    }

    fun selectUser(user: String) {
        selectedUser = user
        persistSelection()
    }

    fun commitCustomUser() {
        val trimmed = customUserInput.trim()
        if (trimmed.isEmpty()) return
        selectedUser = trimmed
        customUserInput = ""
        persistSelection()
        if (containerShellUserPresets.none { it.value == trimmed } && trimmed !in customUsers) {
            customUsers = (customUsers + trimmed).sorted()
            persistCustomUsers()
        }
    }

    fun removeCustomUser(user: String) {
        customUsers = customUsers.filterNot { it == user }
        persistCustomUsers()
        if (selectedUser == user) {
            selectedUser = DEFAULT_USER
            persistSelection()
        }
    }

    fun clear() {
        feedEvents = emptyList()
    }

    private fun handlePayload(payload: String) {
        when (val decoded = ShellProtocol.decodeServerMessage(payload)) {
            is ShellServerMessage.Output -> appendToFeed(decoded.data)
            is ShellServerMessage.Error -> {
                error = decoded.message
                status = ContainerShellStatus.ERROR
                appendErrorLine(decoded.message)
            }
            ShellServerMessage.Exit -> {
                disconnect(ContainerShellStatus.ENDED)
                appendSystemLine("Session ended.")
            }
            null -> appendToFeed(payload)
        }
    }

    private fun handleSocketFailure(failure: Throwable) {
        if (failure.isDockhandCancellation) return
        val message = failure.dockhandUserFacingMessage
        isConnected = false
        status = ContainerShellStatus.ERROR
        error = message
        appendErrorLine(message)
    }

    private fun persistSelection() {
        keyValueStore.put("$preferenceKey.shell", selectedShell)
        keyValueStore.put("$preferenceKey.user", selectedUser)
    }

    private fun persistCustomUsers() {
        keyValueStore.put(CUSTOM_USERS_KEY, customUsers.joinToString("\n"))
    }

    private fun loadCustomUsers(): List<String> =
        keyValueStore.get(CUSTOM_USERS_KEY)
            .orEmpty()
            .split('\n')
            .filter { it.isNotEmpty() }

    private fun appendToFeed(text: String) {
        if (text.isEmpty()) return
        val updated = feedEvents + TerminalFeedEvent(text = text)
        feedEvents = if (updated.size > MAX_FEED_EVENTS) {
            updated.takeLast(MAX_FEED_EVENTS)
        } else {
            updated
        }
    }

    private fun appendSystemLine(line: String) {
        appendToFeed("\u001B[90m$line\u001B[0m\r\n")
    }

    private fun appendErrorLine(line: String) {
        appendToFeed("\u001B[31mError: $line\u001B[0m\r\n")
    }

    private companion object {
        const val DEFAULT_SHELL = "/bin/sh"
        const val DEFAULT_USER = "root"
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val MAX_FEED_EVENTS = 2000
        const val CUSTOM_USERS_KEY = "dockhand.shell.customUsers"
    }
}
