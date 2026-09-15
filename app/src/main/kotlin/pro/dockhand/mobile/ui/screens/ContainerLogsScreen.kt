package pro.dockhand.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pro.dockhand.mobile.api.Container
import pro.dockhand.mobile.app.AppViewModel

@Composable
fun ContainerLogsScreen(
    viewModel: AppViewModel,
    container: Container,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val store = remember(container.id) { ContainerLogsStore(coroutineScope) }
    var reconnectRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(
        container.id,
        state.selectedProfileId,
        state.selectedEnvironmentId,
        store.tail,
        store.follow,
        reconnectRevision
    ) {
        store.run(container.id, viewModel)
    }

    DisposableEffect(container.id) {
        onDispose { store.cancelCurrentRun() }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(store.formattedLogs, store.follow) {
        if (store.follow && store.formattedLogs.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    val lines = remember(store.formattedLogs) {
        if (store.formattedLogs.isEmpty()) emptyList() else store.formattedLogs.split('\n')
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
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Logs", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { reconnectRevision += 1 }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(if (store.follow) "Reconnect" else "Refresh")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Follow")
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = store.follow, onCheckedChange = { store.follow = it })
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Tail")
                    TAIL_OPTIONS.forEach { value ->
                        FilterChip(
                            selected = store.tail == value,
                            onClick = { store.tail = value },
                            label = { Text(value.toString()) }
                        )
                    }
                }
                Text(
                    text = store.streamStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Latest first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                store.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(12.dp)
        ) {
            when {
                store.isLoading && store.formattedLogs.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                store.formattedLogs.isEmpty() -> {
                    Text(
                        text = "No logs returned",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(lines) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TAIL_OPTIONS = listOf(50, 200, 500)
