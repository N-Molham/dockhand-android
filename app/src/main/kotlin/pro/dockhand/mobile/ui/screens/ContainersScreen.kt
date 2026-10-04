package pro.dockhand.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import pro.dockhand.mobile.api.Container
import pro.dockhand.mobile.api.DockhandApi
import pro.dockhand.mobile.api.dockhandUserFacingMessage
import pro.dockhand.mobile.api.isDockhandCancellation
import pro.dockhand.mobile.app.AppViewModel
import pro.dockhand.mobile.ui.ContainerAction
import pro.dockhand.mobile.ui.ContainerListFilter
import pro.dockhand.mobile.ui.canOpenShell
import pro.dockhand.mobile.ui.canPerform
import pro.dockhand.mobile.ui.localizedDockhandStateLabel
import pro.dockhand.mobile.ui.localizedDockerRuntimeText
import pro.dockhand.mobile.ui.networkSummary
import pro.dockhand.mobile.ui.normalizedDockhandState
import pro.dockhand.mobile.ui.primaryPortLabel
import pro.dockhand.mobile.ui.stateRank

@Composable
fun ContainersScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val store = remember { ContainersStore() }
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedContainer by remember { mutableStateOf<Container?>(null) }
    var shellContainer by remember { mutableStateOf<Container?>(null) }
    var selectedFilter by remember { mutableStateOf<ContainerListFilter>(ContainerListFilter.All) }
    var pendingAction by remember { mutableStateOf<PendingContainerAction?>(null) }

    BackHandler(enabled = shellContainer != null || selectedContainer != null) {
        if (shellContainer != null) {
            shellContainer = null
        } else {
            selectedContainer = null
        }
    }

    val openShell = shellContainer
    if (openShell != null) {
        ContainerShellScreen(
            viewModel = viewModel,
            container = openShell,
            onBack = { shellContainer = null },
            modifier = modifier
        )
        return
    }

    val openContainer = selectedContainer
    if (openContainer != null) {
        ContainerLogsScreen(
            viewModel = viewModel,
            container = openContainer,
            onBack = { selectedContainer = null },
            modifier = modifier
        )
        return
    }

    LaunchedEffect(state.selectedProfileId, state.selectedEnvironmentId, state.dashboardRefreshRevision) {
        store.load(viewModel)
    }

    LaunchedEffect(store.error) {
        val message = store.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
    }

    LaunchedEffect(store.actionMessage) {
        val message = store.actionMessage ?: return@LaunchedEffect
        store.actionMessage = null
        snackbarHostState.showSnackbar(message)
    }

    val visibleContainers = remember(store.containers, selectedFilter) {
        store.containers
            .filter { selectedFilter.matches(it) }
            .sortedWith(
                compareBy<Container> { it.stateRank }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CONTAINER_FILTERS.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.title) }
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = store.isLoading && store.containers.isNotEmpty(),
                onRefresh = { coroutineScope.launch { store.load(viewModel) } },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    store.isLoading && store.containers.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            store.error?.let { error ->
                                item {
                                    Text(
                                        text = error,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            if (visibleContainers.isEmpty()) {
                                item {
                                    Text(
                                        text = if (selectedFilter == ContainerListFilter.All) {
                                            "No containers"
                                        } else {
                                            "No containers in ${selectedFilter.title}"
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                items(visibleContainers, key = { it.id }) { item ->
                                    ContainerRow(
                                        container = item,
                                        actionsEnabled = store.activeActionID == null,
                                        showProgress = store.isRunningFor(item.id),
                                        onOpen = { selectedContainer = item },
                                        onOpenShell = { shellContainer = item },
                                        onAction = { action ->
                                            if (action == ContainerAction.STOP || action == ContainerAction.RESTART) {
                                                pendingAction = PendingContainerAction(item, action)
                                            } else {
                                                coroutineScope.launch { store.run(action, item, viewModel) }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingAction?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("${pending.action.title} container?") },
            text = {
                Text(
                    "Are you sure you want to ${pending.action.title.lowercase(Locale.US)} ${pending.container.name}?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = pending.action
                        val target = pending.container
                        pendingAction = null
                        coroutineScope.launch { store.run(action, target, viewModel) }
                    }
                ) {
                    Text(pending.action.title)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ContainerRow(
    container: Container,
    actionsEnabled: Boolean,
    showProgress: Boolean,
    onOpen: () -> Unit,
    onOpenShell: () -> Unit,
    onAction: (ContainerAction) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val actions = remember(container.state) {
        ContainerAction.entries.filter { container.canPerform(it) }
    }
    val normalizedState = container.state.normalizedDockhandState
    val isUnhealthy = (container.health ?: "").normalizedDockhandState == "unhealthy"
    val networkSummary = container.networkSummary

    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = container.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isUnhealthy) {
                        HealthBadge()
                    }
                    Text(
                        text = container.state.localizedDockhandStateLabel.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (normalizedState == "running") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Text(
                    text = container.image,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = container.status.localizedDockerRuntimeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (networkSummary.isEmpty()) {
                        container.primaryPortLabel
                    } else {
                        "${container.primaryPortLabel} · $networkSummary"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                if (showProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(12.dp)
                            .size(20.dp)
                    )
                } else {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Container actions")
                    }
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (container.canOpenShell) {
                        DropdownMenuItem(
                            text = { Text("Open shell") },
                            enabled = actionsEnabled,
                            onClick = {
                                menuExpanded = false
                                onOpenShell()
                            }
                        )
                    }
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.title) },
                            enabled = actionsEnabled,
                            onClick = {
                                menuExpanded = false
                                onAction(action)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthBadge() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = "Unhealthy",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private data class PendingContainerAction(
    val container: Container,
    val action: ContainerAction
)

private class ContainersStore {
    var containers by mutableStateOf<List<Container>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var actionMessage by mutableStateOf<String?>(null)
    var activeActionID by mutableStateOf<String?>(null)
        private set

    suspend fun load(viewModel: AppViewModel) {
        val service = viewModel.service()
        val environmentID = viewModel.selectedEnvironment?.id
        if (service == null || environmentID == null) {
            containers = emptyList()
            return
        }

        isLoading = true
        error = null
        try {
            containers = service.fetchContainers(environmentID)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!failure.isDockhandCancellation) {
                error = failure.dockhandUserFacingMessage
            }
        } finally {
            isLoading = false
        }
    }

    suspend fun run(action: ContainerAction, container: Container, viewModel: AppViewModel) {
        val service = viewModel.service() ?: return
        val environmentID = viewModel.selectedEnvironment?.id ?: return

        activeActionID = action.targetID(container.id)
        error = null
        try {
            perform(service, action, container.id, environmentID)
            actionMessage = "${container.name} ${action.completedLabel}"
            viewModel.requestDashboardRefresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!failure.isDockhandCancellation) {
                error = failure.dockhandUserFacingMessage
            }
        } finally {
            activeActionID = null
        }
    }

    fun isRunningFor(containerID: String): Boolean =
        activeActionID?.substringBefore(':') == containerID

    private suspend fun perform(
        service: DockhandApi,
        action: ContainerAction,
        containerID: String,
        environmentID: Int
    ) {
        when (action) {
            ContainerAction.START -> service.startContainer(containerID, environmentID)
            ContainerAction.STOP -> service.stopContainer(containerID, environmentID)
            ContainerAction.RESTART -> service.restartContainer(containerID, environmentID)
            ContainerAction.PAUSE -> service.pauseContainer(containerID, environmentID)
            ContainerAction.UNPAUSE -> service.unpauseContainer(containerID, environmentID)
        }
    }
}

private fun ContainerAction.targetID(containerID: String): String =
    "$containerID:${name.lowercase(Locale.US)}"

private val ContainerAction.completedLabel: String
    get() = when (this) {
        ContainerAction.START -> "started"
        ContainerAction.STOP -> "stopped"
        ContainerAction.RESTART -> "restarted"
        ContainerAction.PAUSE -> "paused"
        ContainerAction.UNPAUSE -> "resumed"
    }

private val CONTAINER_FILTERS = listOf(
    ContainerListFilter.All,
    ContainerListFilter.State("running"),
    ContainerListFilter.State("paused"),
    ContainerListFilter.State("exited"),
    ContainerListFilter.State("created"),
    ContainerListFilter.State("restarting"),
    ContainerListFilter.Stopped,
    ContainerListFilter.Unhealthy
)
