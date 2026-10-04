package pro.dockhand.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import pro.dockhand.mobile.api.Container
import pro.dockhand.mobile.api.ContainerInspect
import pro.dockhand.mobile.api.dockhandUserFacingMessage
import pro.dockhand.mobile.api.isDockhandCancellation
import pro.dockhand.mobile.app.AppViewModel
import pro.dockhand.mobile.ui.ContainerAction
import pro.dockhand.mobile.ui.TraefikUrlResolver
import pro.dockhand.mobile.ui.canOpenShell
import pro.dockhand.mobile.ui.canPerform
import pro.dockhand.mobile.ui.dockhandByteCount
import pro.dockhand.mobile.ui.localizedDockhandStateLabel
import pro.dockhand.mobile.ui.normalizedDockhandState

@Composable
fun ContainerDetailScreen(
    viewModel: AppViewModel,
    container: Container,
    onBack: () -> Unit,
    onOpenShell: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var inspect by remember(container.id) { mutableStateOf<ContainerInspect?>(null) }
    var isLoading by remember(container.id) { mutableStateOf(false) }
    var error by remember(container.id) { mutableStateOf<String?>(null) }
    var reloadRevision by remember(container.id) { mutableIntStateOf(0) }
    var pendingAction by remember(container.id) { mutableStateOf<ContainerAction?>(null) }
    var actionInProgress by remember(container.id) { mutableStateOf(false) }

    LaunchedEffect(container.id, state.selectedProfileId, state.selectedEnvironmentId, reloadRevision) {
        val service = viewModel.service()
        val environmentID = viewModel.selectedEnvironment?.id
        if (service == null || environmentID == null) {
            inspect = null
            isLoading = false
            error = "Connect to a Dockhand server and environment first."
            return@LaunchedEffect
        }
        isLoading = true
        error = null
        try {
            inspect = service.fetchContainerInspect(container.id, environmentID)
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

    val inspectState = inspect?.state
    val currentState = inspectState?.status?.takeIf { it.isNotBlank() } ?: container.state
    val currentHealth = inspectState?.health?.status?.takeIf { !it.isNullOrBlank() } ?: container.health
    val effectiveContainer = remember(container, currentState, currentHealth) {
        container.copy(state = currentState, health = currentHealth)
    }

    fun runAction(action: ContainerAction) {
        coroutineScope.launch {
            val service = viewModel.service()
            val environmentID = viewModel.selectedEnvironment?.id
            if (service == null || environmentID == null) {
                error = "Connect to a Dockhand server and environment first."
                return@launch
            }
            actionInProgress = true
            try {
                when (action) {
                    ContainerAction.START -> service.startContainer(container.id, environmentID)
                    ContainerAction.STOP -> service.stopContainer(container.id, environmentID)
                    ContainerAction.RESTART -> service.restartContainer(container.id, environmentID)
                    ContainerAction.PAUSE -> service.pauseContainer(container.id, environmentID)
                    ContainerAction.UNPAUSE -> service.unpauseContainer(container.id, environmentID)
                }
                viewModel.requestDashboardRefresh()
                reloadRevision += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!failure.isDockhandCancellation) {
                    error = failure.dockhandUserFacingMessage
                }
            } finally {
                actionInProgress = false
            }
        }
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("${action.title} container?") },
            text = {
                Text(
                    "Are you sure you want to ${action.title.lowercase(Locale.US)} ${container.name}?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        runAction(action)
                    }
                ) {
                    Text(action.title)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("Cancel")
                }
            }
        )
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
            IconButton(onClick = { reloadRevision += 1 }, enabled = !isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        if (isLoading && inspect == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                error?.let { message ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
                item { OverviewSection(container = effectiveContainer, inspect = inspect) }
                item { StatsSection(container = container) }
                val loadedInspect = inspect
                if (loadedInspect != null) {
                    item { VolumesSection(inspect = loadedInspect) }
                    item { NetworksSection(inspect = loadedInspect, container = container) }
                    item {
                        LabelsSection(
                            inspect = loadedInspect,
                            container = container,
                            onOpenUrl = { uriHandler.openUri(it) }
                        )
                    }
                }
                item {
                    ActionsSection(
                        container = effectiveContainer,
                        busy = actionInProgress,
                        onAction = { action ->
                            if (action == ContainerAction.STOP || action == ContainerAction.RESTART) {
                                pendingAction = action
                            } else {
                                runAction(action)
                            }
                        },
                        onOpenShell = onOpenShell
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewSection(container: Container, inspect: ContainerInspect?) {
    DetailSection("Overview") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StateChip(container.state)
            container.health?.takeIf { it.isNotBlank() }?.let { HealthChip(it) }
        }
        Text(
            text = container.image,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        (inspect?.restartCount ?: container.restartCount)?.let { DetailRow("Restart count", it.toString()) }
        createdAtText(container, inspect)?.let { DetailRow("Created", it) }
        inspect?.state?.pid?.let { DetailRow("Processes", "PID $it") }
        commandText(container, inspect)?.let { DetailRow("Command", it, monospace = true) }
        inspect?.config?.workingDir?.takeIf { it.isNotBlank() }?.let { DetailRow("Working dir", it) }
        inspect?.config?.user?.takeIf { it.isNotBlank() }?.let { DetailRow("User", it) }
        inspect?.hostConfig?.restartPolicy?.name?.takeIf { it.isNotBlank() }?.let {
            DetailRow("Restart policy", it)
        }
    }
}

@Composable
private fun StatsSection(container: Container) {
    val hasStats = container.cpuPercent != null ||
        container.memoryUsed != null ||
        container.memoryLimit != null ||
        container.networkRx != null ||
        container.networkTx != null ||
        container.diskRead != null ||
        container.diskWrite != null ||
        container.pids != null

    if (!hasStats) {
        Text(
            text = "Live stats are not exposed by the Dockhand API.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        return
    }

    DetailSection("Live stats") {
        container.cpuPercent?.let { DetailRow("CPU", String.format(Locale.US, "%.1f%%", it)) }
        if (container.memoryUsed != null || container.memoryLimit != null) {
            val used = container.memoryUsed?.dockhandByteCount ?: "—"
            val limit = container.memoryLimit?.dockhandByteCount
            DetailRow("Memory", if (limit != null) "$used / $limit" else used)
        }
        if (container.networkRx != null || container.networkTx != null) {
            DetailRow(
                "Network",
                "RX ${container.networkRx?.dockhandByteCount ?: "—"} · TX ${container.networkTx?.dockhandByteCount ?: "—"}"
            )
        }
        if (container.diskRead != null || container.diskWrite != null) {
            DetailRow(
                "Disk I/O",
                "Read ${container.diskRead?.dockhandByteCount ?: "—"} · Write ${container.diskWrite?.dockhandByteCount ?: "—"}"
            )
        }
        container.pids?.let { DetailRow("Processes", it.toString()) }
    }
}

@Composable
private fun VolumesSection(inspect: ContainerInspect) {
    val volumes = remember(inspect) { volumeDetails(inspect) }
    DetailSection("Volumes") {
        if (volumes.isEmpty()) {
            Text(
                text = "No mounts",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            volumes.forEach { volume ->
                val mode = volume.mode.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
                Text(
                    text = "${volume.type} ${volume.source} → ${volume.destination}$mode",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun NetworksSection(inspect: ContainerInspect, container: Container) {
    val networks = remember(inspect) {
        inspect.networkSettings?.networks.orEmpty().entries.sortedBy { it.key }
    }
    val publishedPorts = inspect.networkSettings?.ports ?: inspect.ports

    DetailSection("Networks") {
        if (networks.isEmpty() && publishedPorts.isNullOrEmpty() && container.ports.isEmpty()) {
            Text(
                text = "No network information",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        networks.forEach { (name, network) ->
            Text(name, style = MaterialTheme.typography.titleSmall)
            network.ipAddress?.takeIf { it.isNotBlank() }?.let { DetailRow("IP", it) }
            network.gateway?.takeIf { it.isNotBlank() }?.let { DetailRow("Gateway", it) }
            network.macAddress?.takeIf { it.isNotBlank() }?.let { DetailRow("MAC", it) }
        }
        if (!publishedPorts.isNullOrEmpty()) {
            Text("Ports", style = MaterialTheme.typography.titleSmall)
            publishedPorts.entries.sortedBy { it.key }.forEach { (key, bindings) ->
                DetailRow(key, portBindingText(bindings))
            }
        } else if (container.ports.isNotEmpty()) {
            Text("Ports", style = MaterialTheme.typography.titleSmall)
            container.ports.forEach { port ->
                DetailRow(
                    "${port.privatePort}/${port.type}",
                    port.publicPort?.toString() ?: "Not published"
                )
            }
        }
    }
}

@Composable
private fun LabelsSection(
    inspect: ContainerInspect,
    container: Container,
    onOpenUrl: (String) -> Unit
) {
    val labels = inspect.config?.labels?.takeIf { it.isNotEmpty() } ?: container.labels
    if (labels.isEmpty()) return

    val urls = remember(labels) { TraefikUrlResolver.resolveUrls(labels) }
    val entries = remember(labels) { TraefikUrlResolver.labelEntries(labels) }

    DetailSection("Labels") {
        if (urls.isNotEmpty()) {
            Text("Links", style = MaterialTheme.typography.titleSmall)
            urls.forEach { url ->
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenUrl(url) }
                )
            }
        }
        entries.forEach { (key, value) ->
            val clickable = TraefikUrlResolver.isUrlLikeLabel(value) || value.trim() in urls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.45f)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (clickable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .weight(0.55f)
                        .then(
                            if (clickable) {
                                Modifier.clickable { onOpenUrl(value.trim()) }
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun ActionsSection(
    container: Container,
    busy: Boolean,
    onAction: (ContainerAction) -> Unit,
    onOpenShell: () -> Unit
) {
    val actions = remember(container.state) {
        ContainerAction.entries.filter { container.canPerform(it) }
    }
    DetailSection("Actions") {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            actions.forEach { action ->
                OutlinedButton(onClick = { onAction(action) }, enabled = !busy) {
                    Text(action.title)
                }
            }
            if (container.canOpenShell) {
                OutlinedButton(onClick = onOpenShell, enabled = !busy) {
                    Text("Shell")
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
private fun StateChip(state: String) {
    val running = state.normalizedDockhandState == "running"
    Surface(
        color = if (running) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = state.localizedDockhandStateLabel.uppercase(Locale.US),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (running) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun HealthChip(health: String) {
    val unhealthy = health.normalizedDockhandState == "unhealthy"
    Surface(
        color = if (unhealthy) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = health.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) },
            style = MaterialTheme.typography.labelMedium,
            color = if (unhealthy) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private data class VolumeDetail(
    val type: String,
    val source: String,
    val destination: String,
    val mode: String
)

private fun volumeDetails(inspect: ContainerInspect): List<VolumeDetail> {
    val details = mutableListOf<VolumeDetail>()
    inspect.mounts.forEach { mount ->
        val destination = mount.destination?.takeIf { it.isNotBlank() } ?: return@forEach
        details.add(
            VolumeDetail(
                type = mount.type?.takeIf { it.isNotBlank() } ?: "volume",
                source = mount.source?.takeIf { it.isNotBlank() } ?: "—",
                destination = destination,
                mode = when (mount.rw) {
                    true -> "rw"
                    false -> "ro"
                    null -> mount.mode?.takeIf { it.isNotBlank() }.orEmpty()
                }
            )
        )
    }
    inspect.hostConfig?.binds.orEmpty().forEach { bind ->
        parseBind(bind)?.let(details::add)
    }
    return details.distinctBy { it.source to it.destination }
}

private fun parseBind(bind: String): VolumeDetail? {
    val parts = bind.trim().split(':')
    val source: String
    val destination: String
    val mode: String?
    if (parts.size >= 3 && parts[0].length == 1) {
        source = "${parts[0]}:${parts[1]}"
        destination = parts[2]
        mode = parts.getOrNull(3)
    } else if (parts.size >= 2) {
        source = parts[0]
        destination = parts[1]
        mode = parts.getOrNull(2)
    } else {
        return null
    }
    if (destination.isBlank()) return null
    return VolumeDetail(
        type = "bind",
        source = source.ifBlank { "—" },
        destination = destination,
        mode = mode?.takeIf { it.isNotBlank() }.orEmpty()
    )
}

private fun portBindingText(bindings: List<ContainerInspect.PortBinding>?): String {
    if (bindings.isNullOrEmpty()) return "Not published"
    val values = bindings.mapNotNull { binding ->
        val hostPort = binding.hostPort?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val hostIp = binding.hostIp?.takeIf { it.isNotBlank() }
        if (hostIp != null) "$hostIp:$hostPort" else hostPort
    }
    return if (values.isEmpty()) "Not published" else values.joinToString(", ")
}

private fun commandText(container: Container, inspect: ContainerInspect?): String? {
    val parts = buildList {
        inspect?.config?.entrypoint?.let(::addAll)
        inspect?.config?.cmd?.let(::addAll)
    }
    if (parts.isNotEmpty()) return parts.joinToString(" ")
    return container.command?.takeIf { it.isNotBlank() }
}

private fun createdAtText(container: Container, inspect: ContainerInspect?): String? {
    inspect?.created?.takeIf { it.isNotBlank() }?.let { return it }
    if (container.created <= 0) return null
    return DateFormat.getDateTimeInstance().format(Date(container.created * 1000))
}
