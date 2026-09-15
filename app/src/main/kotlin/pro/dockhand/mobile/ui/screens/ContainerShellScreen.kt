package pro.dockhand.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import pro.dockhand.mobile.api.Container
import pro.dockhand.mobile.api.ContainerShellInfo
import pro.dockhand.mobile.app.AppViewModel
import pro.dockhand.mobile.app.DockhandConnectionScope
import pro.dockhand.mobile.ui.AnsiText

@Composable
fun ContainerShellScreen(
    viewModel: AppViewModel,
    container: Container,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val service = viewModel.service()
    val environmentID = state.environments.firstOrNull { it.id == state.selectedEnvironmentId }?.id
        ?: state.environments.firstOrNull()?.id
    val profileID = state.selectedProfileId

    if (service == null || environmentID == null) {
        ShellUnavailableContent(
            containerName = container.name,
            message = "Connect to a Dockhand server and environment first.",
            onBack = onBack,
            modifier = modifier
        )
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val keyValueStore = remember(container.id) { InMemoryKeyValueStore() }
    val store = remember(container.id, environmentID, profileID, service) {
        ContainerShellStore(
            api = service,
            environmentID = environmentID,
            containerID = container.id,
            containerName = container.name,
            profileID = profileID,
            webSocketFactory = shellWebSocketFactory,
            scope = coroutineScope,
            keyValueStore = keyValueStore
        )
    }
    val storeScope = remember(store, profileID, environmentID) {
        DockhandConnectionScope(profileID, environmentID)
    }
    val scopeIsCurrent = viewModel.connectionScope == storeScope

    LaunchedEffect(store) {
        store.detectShells()
    }

    DisposableEffect(store) {
        onDispose { store.disconnect() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = container.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ShellStatusChip(store)
        }

        if (!scopeIsCurrent) {
            Text(
                text = "Server or environment changed. Go back and reopen this shell for the active context.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        ShellControlsCard(store = store, scopeIsCurrent = scopeIsCurrent)
        TerminalPanel(store = store, modifier = Modifier.weight(1f))
        ShellInputBar(store = store)
    }
}

@Composable
private fun ShellStatusChip(store: ContainerShellStore) {
    val indicatorColor = if (store.isConnected) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(end = 10.dp)
    ) {
        if (store.isDetectingShells) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(indicatorColor, CircleShape)
        )
        Text(
            text = store.status.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (store.isConnected) indicatorColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ShellControlsCard(store: ContainerShellStore, scopeIsCurrent: Boolean) {
    val canConnect = scopeIsCurrent && !store.isConnected && (store.detectionResult?.hasAvailableShells != false)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (store.isConnected) {
                    Button(onClick = { store.disconnect() }) {
                        Text("Disconnect")
                    }
                } else {
                    Button(onClick = { store.connect() }, enabled = canConnect) {
                        Text("Connect")
                    }
                }
                TextButton(onClick = { store.reconnect() }, enabled = canConnect) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Reconnect")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { store.clear() },
                    enabled = store.feedEvents.isNotEmpty()
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Clear")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShellSelector(
                    store = store,
                    enabled = !store.isConnected && !store.isDetectingShells,
                    modifier = Modifier.weight(1f)
                )
                UserSelector(
                    store = store,
                    enabled = !store.isConnected,
                    modifier = Modifier.weight(1f)
                )
            }
            CustomUserRow(store)
            store.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ShellSelector(
    store: ContainerShellStore,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(store.detectionResult) {
        store.detectionResult?.allShells.orEmpty().ifEmpty { DEFAULT_SHELL_OPTIONS }
    }
    val selectedLabel = options.firstOrNull { it.path == store.selectedShell }?.label
        ?: store.selectedShell
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Shell: $selectedLabel",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { shell ->
                DropdownMenuItem(
                    text = {
                        Text(if (shell.available) shell.label else "${shell.label} unavailable")
                    },
                    enabled = shell.available,
                    onClick = {
                        expanded = false
                        store.selectShell(shell.path)
                    }
                )
            }
        }
    }
}

@Composable
private fun UserSelector(
    store: ContainerShellStore,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = store.selectedUser.ifEmpty { "Container default" }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "User: $selectedLabel",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            containerShellUserPresets.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        store.selectUser(option.value)
                    }
                )
            }
            store.customUsers.forEach { user ->
                DropdownMenuItem(
                    text = { Text(user) },
                    onClick = {
                        expanded = false
                        store.selectUser(user)
                    }
                )
            }
        }
    }
}

@Composable
private fun CustomUserRow(store: ContainerShellStore) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = store.customUserInput,
            onValueChange = { store.customUserInput = it },
            modifier = Modifier.weight(1f),
            enabled = !store.isConnected,
            singleLine = true,
            placeholder = { Text("Add custom user") },
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None
            )
        )
        TextButton(
            onClick = { store.commitCustomUser() },
            enabled = !store.isConnected && store.customUserInput.isNotBlank()
        ) {
            Text("Use")
        }
    }
}

@Composable
private fun TerminalPanel(store: ContainerShellStore, modifier: Modifier = Modifier) {
    val rawText = remember(store.feedEvents) {
        store.feedEvents.joinToString(separator = "") { it.text }
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }
    val lines = remember(rawText) {
        val split = rawText.split('\n')
        if (split.isNotEmpty() && split.last().isEmpty()) split.dropLast(1) else split
    }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val characterWidthPx = with(density) { TERMINAL_FONT_SIZE.toPx() * 0.6f }
        val lineHeightPx = with(density) { (TERMINAL_FONT_SIZE * 1.25f).toPx() }
        val cols = (constraints.maxWidth / characterWidthPx).toInt().coerceIn(20, 400)
        val rows = (constraints.maxHeight / lineHeightPx).toInt().coerceIn(5, 400)

        LaunchedEffect(cols, rows) {
            store.requestResize(cols, rows)
        }

        if (lines.isEmpty()) {
            Text(
                text = "No output yet.",
                color = TERMINAL_MUTED,
                fontFamily = FontFamily.Monospace,
                fontSize = TERMINAL_FONT_SIZE,
                modifier = Modifier.padding(10.dp)
            )
        } else {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                ) {
                    items(lines) { line ->
                        Text(
                            text = remember(line) { AnsiText.annotate(line) },
                            color = TERMINAL_FOREGROUND,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TERMINAL_FONT_SIZE,
                            lineHeight = TERMINAL_FONT_SIZE * 1.25f
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellInputBar(store: ContainerShellStore) {
    var input by remember { mutableStateOf("") }

    fun submit() {
        if (!store.isConnected || input.isEmpty()) return
        store.sendCommand(input)
        input = ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = store.isConnected,
                singleLine = true,
                placeholder = { Text("Command") },
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { submit() })
            )
            FilledIconButton(
                onClick = { submit() },
                enabled = store.isConnected && input.isNotEmpty()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QUICK_KEYS.forEach { (label, sequence) ->
                AssistChip(
                    onClick = { store.sendInput(sequence) },
                    enabled = store.isConnected,
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
private fun ShellUnavailableContent(
    containerName: String,
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = containerName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }
}

private class OkHttpShellWebSocket(
    private val client: OkHttpClient,
    private val request: Request
) : ShellWebSocket {
    private val lock = Any()
    private var webSocket: WebSocket? = null

    override var listener: ShellWebSocketListener? = null
        set(value) {
            synchronized(lock) {
                field = value
                if (value != null && webSocket == null) {
                    webSocket = client.newWebSocket(request, okHttpListener)
                }
            }
        }

    private val okHttpListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            listener?.onText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            listener?.onText(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            listener?.onClosed()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            listener?.onFailure(t)
        }
    }

    override fun send(text: String) {
        synchronized(lock) {
            webSocket?.send(text)
        }
    }

    override fun cancel() {
        synchronized(lock) {
            webSocket?.cancel()
            webSocket = null
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}

private val shellOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}

private val shellWebSocketFactory: (Request) -> ShellWebSocket = { request ->
    OkHttpShellWebSocket(shellOkHttpClient, request)
}

private val TERMINAL_FONT_SIZE = 13.sp
private val TERMINAL_FOREGROUND = Color(0xFFE6E6E6)
private val TERMINAL_MUTED = Color(0xFF9E9E9E)

private val DEFAULT_SHELL_OPTIONS = listOf(
    ContainerShellInfo("/bin/sh", "Shell (sh)", true),
    ContainerShellInfo("/bin/bash", "Bash", true),
    ContainerShellInfo("/bin/zsh", "Zsh", true),
    ContainerShellInfo("/bin/ash", "Ash", true)
)

private val QUICK_KEYS = listOf(
    "Ctrl+C" to "\u0003",
    "Tab" to "\t",
    "Enter" to "\r",
    "Up" to "\u001B[A",
    "Down" to "\u001B[B",
    "Left" to "\u001B[D",
    "Right" to "\u001B[C",
    "Ctrl+D" to "\u0004"
)
