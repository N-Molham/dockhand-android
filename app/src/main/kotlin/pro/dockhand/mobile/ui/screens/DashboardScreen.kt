package pro.dockhand.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pro.dockhand.mobile.api.ContainerEventSnapshot
import pro.dockhand.mobile.api.ContainerUpdateCheckOperation
import pro.dockhand.mobile.api.ContainerUpdateCheckResult
import pro.dockhand.mobile.api.DashboardEnvironmentSnapshot
import pro.dockhand.mobile.api.DashboardHostSnapshot
import pro.dockhand.mobile.api.DockhandApi
import pro.dockhand.mobile.api.DockhandServiceError
import pro.dockhand.mobile.api.Environment
import pro.dockhand.mobile.api.NetworkSnapshot
import pro.dockhand.mobile.api.PendingContainerUpdate
import pro.dockhand.mobile.api.StackSummary
import pro.dockhand.mobile.api.VolumeSnapshot
import pro.dockhand.mobile.api.dockhandUserFacingMessage
import pro.dockhand.mobile.app.AppViewModel
import pro.dockhand.mobile.ui.dockhandByteCount
import pro.dockhand.mobile.ui.hostSummary
import pro.dockhand.mobile.ui.localizedConnectionTypeLabel
import pro.dockhand.mobile.ui.localizedCoresCountText

private const val UPDATE_CHECK_TIMEOUT_MS = 30_000L
private const val UPDATE_CHECK_POLL_MS = 500L

private class DashboardScreenStore {
    var snapshot by mutableStateOf<DashboardEnvironmentSnapshot?>(null)
    var host by mutableStateOf<DashboardHostSnapshot?>(null)
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var lastUpdatedAt by mutableStateOf<Date?>(null)
    var pendingUpdates by mutableStateOf<List<PendingContainerUpdate>>(emptyList())
    var isCheckingUpdates by mutableStateOf(false)
    var isUpdatingContainers by mutableStateOf(false)
    var updateCheckProgress by mutableStateOf<Float?>(null)
    var operationMessage by mutableStateOf<String?>(null)
    var volumes by mutableStateOf<List<VolumeSnapshot>>(emptyList())
    var networks by mutableStateOf<List<NetworkSnapshot>>(emptyList())
    var events by mutableStateOf<List<ContainerEventSnapshot>>(emptyList())
    var eventTotal by mutableIntStateOf(0)
    var stacks by mutableStateOf<List<StackSummary>>(emptyList())

    private var loadedEnvironmentId: Int? = null

    suspend fun load(viewModel: AppViewModel, environmentId: Int?) {
        val service = viewModel.service()
        if (service == null || environmentId == null) {
            reset()
            return
        }
        if (loadedEnvironmentId != environmentId) {
            reset()
            loadedEnvironmentId = environmentId
        }
        val showLoading = snapshot == null
        if (showLoading) isLoading = true
        error = null
        try {
            snapshot = service.fetchDashboardStats(environmentId)
            lastUpdatedAt = Date()
            host = try {
                service.fetchDashboardHost(environmentId)
            } catch (hostError: Throwable) {
                if (hostError is CancellationException) throw hostError
                null
            }
            loadUpdates(service, environmentId)
        } catch (loadError: Throwable) {
            if (loadError is CancellationException) throw loadError
            error = loadError.dockhandUserFacingMessage
            if (snapshot == null) {
                host = null
                lastUpdatedAt = null
            }
        } finally {
            isLoading = false
        }
    }

    private suspend fun loadUpdates(service: DockhandApi, environmentId: Int) {
        try {
            coroutineScope {
                val updates = async { service.fetchPendingContainerUpdates(environmentId) }
                val loadedStacks = async { service.fetchStacks(environmentId) }
                pendingUpdates = updates.await()
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.containerName })
                stacks = loadedStacks.await()
            }
        } catch (updatesError: Throwable) {
            if (updatesError is CancellationException) throw updatesError
            error = updatesError.dockhandUserFacingMessage
        }
    }

    suspend fun checkForUpdates(viewModel: AppViewModel, environmentId: Int?) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        isCheckingUpdates = true
        updateCheckProgress = null
        operationMessage = null
        error = null
        try {
            val result = when (val operation = service.startContainerUpdateCheck(environmentId)) {
                is ContainerUpdateCheckOperation.Job -> watchUpdateCheckJob(service, operation.id)
                is ContainerUpdateCheckOperation.Completed -> operation.result
            }
            updateCheckProgress = 1f
            operationMessage = "${result.updatesFound} updates found after checking ${result.total} containers"
            loadUpdates(service, environmentId)
        } catch (checkError: Throwable) {
            if (checkError is CancellationException) throw checkError
            error = checkError.dockhandUserFacingMessage
        } finally {
            isCheckingUpdates = false
        }
    }

    private suspend fun watchUpdateCheckJob(service: DockhandApi, jobId: String): ContainerUpdateCheckResult {
        val deadline = System.currentTimeMillis() + UPDATE_CHECK_TIMEOUT_MS
        var cursor = 0
        while (true) {
            val jobSnapshot = service.fetchContainerUpdateCheckJob(jobId)
            if (cursor < jobSnapshot.lines.size) {
                jobSnapshot.lines.drop(cursor).forEach { line ->
                    val checked = line.data.checked
                    val total = line.data.total
                    if (checked != null && total != null && total > 0) {
                        updateCheckProgress = (checked.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    }
                }
                cursor = jobSnapshot.lines.size
            }
            if (jobSnapshot.status != "running") {
                if (jobSnapshot.status == "error") {
                    throw DockhandServiceError.Message("Update check failed")
                }
                return jobSnapshot.result ?: throw DockhandServiceError.Message("Update check failed")
            }
            if (System.currentTimeMillis() >= deadline) {
                throw DockhandServiceError.Message("Update check timed out")
            }
            delay(UPDATE_CHECK_POLL_MS)
        }
    }

    suspend fun updateContainers(
        updates: List<PendingContainerUpdate>,
        viewModel: AppViewModel,
        environmentId: Int?
    ) {
        val service = viewModel.service() ?: return
        if (environmentId == null || updates.isEmpty()) return
        isUpdatingContainers = true
        operationMessage = null
        error = null
        try {
            val response = service.updateContainers(updates.map { it.containerID }, environmentId)
            if (response.summary.failed > 0) {
                val details = response.results
                    .filter { !it.success }
                    .joinToString("\n") { "${it.containerName}: ${it.error ?: "Update failed"}" }
                throw DockhandServiceError.Message(details)
            }
            operationMessage = "${response.summary.success} containers updated"
            loadUpdates(service, environmentId)
        } catch (updateError: Throwable) {
            if (updateError is CancellationException) throw updateError
            error = updateError.dockhandUserFacingMessage
        } finally {
            isUpdatingContainers = false
        }
    }

    suspend fun loadVolumes(viewModel: AppViewModel, environmentId: Int?) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        try {
            volumes = service.fetchVolumes(environmentId)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        } catch (volumesError: Throwable) {
            if (volumesError is CancellationException) throw volumesError
            error = volumesError.dockhandUserFacingMessage
        }
    }

    suspend fun loadNetworks(viewModel: AppViewModel, environmentId: Int?) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        try {
            networks = service.fetchNetworks(environmentId)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        } catch (networksError: Throwable) {
            if (networksError is CancellationException) throw networksError
            error = networksError.dockhandUserFacingMessage
        }
    }

    suspend fun loadEvents(viewModel: AppViewModel, environmentId: Int?) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        try {
            val activity = service.fetchContainerActivity(environmentId)
            events = activity.events
            eventTotal = activity.total
        } catch (eventsError: Throwable) {
            if (eventsError is CancellationException) throw eventsError
            error = eventsError.dockhandUserFacingMessage
        }
    }

    fun stackNamesFor(containerID: String): List<String> = stacks
        .filter { stack -> stack.containerDetails.any { it.id == containerID } }
        .map { it.name }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

    private fun reset() {
        snapshot = null
        host = null
        lastUpdatedAt = null
        error = null
        pendingUpdates = emptyList()
        stacks = emptyList()
        volumes = emptyList()
        networks = emptyList()
        events = emptyList()
        eventTotal = 0
        operationMessage = null
        updateCheckProgress = null
    }
}

@Composable
fun DashboardScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val appState by viewModel.state.collectAsState()
    val environment = appState.environments.firstOrNull { it.id == appState.selectedEnvironmentId }
        ?: appState.environments.firstOrNull()
    val store = remember { DashboardScreenStore() }
    val coroutineScope = rememberCoroutineScope()
    val environmentId = environment?.id

    var pendingConfirmation by remember { mutableStateOf<List<PendingContainerUpdate>>(emptyList()) }
    var volumesExpanded by remember { mutableStateOf(false) }
    var networksExpanded by remember { mutableStateOf(false) }
    var eventsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(appState.selectedProfileId, environmentId, appState.dashboardRefreshRevision) {
        store.load(viewModel, environmentId)
    }

    if (environment == null) {
        EmptyState(
            title = if (appState.serverProfiles.isEmpty()) "No server configured" else "No environment selected",
            message = if (appState.serverProfiles.isEmpty()) {
                "Add a Dockhand server to start switching environments."
            } else {
                "Configure Dockhand in Settings or refresh the environment list."
            },
            modifier = modifier
        )
        return
    }

    val snapshot = store.snapshot

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        store.error?.let { message -> ErrorCard(message) }

        if (snapshot != null) {
            EnvironmentHeroCard(environment, snapshot, store.host)
            HealthBanner(snapshot)
            ResourcesCard(snapshot)
            ContainersCard(snapshot)
            HostCard(store.host)
            InventoryCard(snapshot, store.host)
            PendingUpdatesCard(
                store = store,
                onCheck = { coroutineScope.launch { store.checkForUpdates(viewModel, environmentId) } },
                onUpdate = { pendingConfirmation = it }
            )
            ExpandableSection(
                title = "Volumes",
                subtitle = "${snapshot.volumes.total} total",
                expanded = volumesExpanded,
                onToggle = {
                    volumesExpanded = !volumesExpanded
                    if (volumesExpanded) coroutineScope.launch { store.loadVolumes(viewModel, environmentId) }
                }
            ) {
                if (store.volumes.isEmpty()) {
                    SecondaryText("No volumes")
                } else {
                    store.volumes.forEach { volume ->
                        VolumeRow(volume, store::stackNamesFor)
                        HorizontalDivider()
                    }
                }
            }
            ExpandableSection(
                title = "Networks",
                subtitle = "${snapshot.networks.total} total",
                expanded = networksExpanded,
                onToggle = {
                    networksExpanded = !networksExpanded
                    if (networksExpanded) coroutineScope.launch { store.loadNetworks(viewModel, environmentId) }
                }
            ) {
                if (store.networks.isEmpty()) {
                    SecondaryText("No networks")
                } else {
                    store.networks.forEach { network ->
                        NetworkRow(network, store)
                        HorizontalDivider()
                    }
                }
            }
            ExpandableSection(
                title = "Events",
                subtitle = "${snapshot.events.today} today · ${snapshot.events.total} total",
                expanded = eventsExpanded,
                onToggle = {
                    eventsExpanded = !eventsExpanded
                    if (eventsExpanded) coroutineScope.launch { store.loadEvents(viewModel, environmentId) }
                }
            ) {
                if (store.events.isEmpty()) {
                    SecondaryText("No activity")
                } else {
                    store.events.forEach { event ->
                        EventRow(event, store.stackNamesFor(event.containerID))
                        HorizontalDivider()
                    }
                }
            }
            store.lastUpdatedAt?.let { updatedAt ->
                SecondaryText("Updated ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(updatedAt)}")
            }
        } else if (store.isLoading) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    SecondaryText("Loading environment stats")
                }
            }
        } else {
            EnvironmentSummaryCard(environment)
        }
    }

    if (pendingConfirmation.isNotEmpty()) {
        val count = pendingConfirmation.size
        AlertDialog(
            onDismissRequest = { pendingConfirmation = emptyList() },
            title = { Text("Update containers?") },
            text = { Text("Dockhand will pull the latest images and recreate the selected containers while preserving their configuration.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = pendingConfirmation
                        pendingConfirmation = emptyList()
                        coroutineScope.launch { store.updateContainers(selected, viewModel, environmentId) }
                    }
                ) {
                    Text("Update $count containers")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = emptyList() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EnvironmentHeroCard(
    environment: Environment,
    snapshot: DashboardEnvironmentSnapshot,
    host: DashboardHostSnapshot?
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = environment.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (snapshot.online) "Online" else "Offline",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (snapshot.online) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Text(
                    text = environment.hostSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HeroMetric("Connection", environment.connectionType.localizedConnectionTypeLabel, Modifier.weight(1f))
                HeroMetric("Docker", host?.docker?.serverVersion ?: "Unknown", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HeroMetric("CPU", (host?.host?.cpus ?: 0).localizedCoresCountText, Modifier.weight(1f))
                HeroMetric(
                    "Memory",
                    (host?.host?.memory ?: snapshot.metrics.memoryTotal).dockhandByteCount,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HeroMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HealthBanner(snapshot: DashboardEnvironmentSnapshot) {
    val unhealthy = snapshot.containers.unhealthy
    val healthy = unhealthy == 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (healthy) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (healthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null
            )
            Text(
                text = if (healthy) "All containers healthy" else "$unhealthy unhealthy containers",
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@Composable
private fun ResourcesCard(snapshot: DashboardEnvironmentSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Resources", style = MaterialTheme.typography.titleMedium)
            UsageRow(
                title = "CPU",
                value = String.format(Locale.US, "%.1f%%", snapshot.metrics.cpuPercent),
                detail = null,
                progress = (snapshot.metrics.cpuPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
            )
            UsageRow(
                title = "Memory",
                value = String.format(Locale.US, "%.1f%%", snapshot.metrics.memoryPercent),
                detail = "${snapshot.metrics.memoryUsed.dockhandByteCount} / ${snapshot.metrics.memoryTotal.dockhandByteCount}",
                progress = (snapshot.metrics.memoryPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
            )
        }
    }
}

@Composable
private fun UsageRow(title: String, value: String, detail: String?, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(value, style = MaterialTheme.typography.titleSmall)
                if (detail != null) {
                    SecondaryText(detail)
                }
            }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ContainersCard(snapshot: DashboardEnvironmentSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Containers", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("Running", snapshot.containers.running.toString(), Modifier.weight(1f))
                StatTile("Stopped", snapshot.containers.stopped.toString(), Modifier.weight(1f))
                StatTile("Paused", snapshot.containers.paused.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("Restarting", snapshot.containers.restarting.toString(), Modifier.weight(1f))
                StatTile("Alerts", snapshot.containers.unhealthy.toString(), Modifier.weight(1f))
                StatTile("Updates", snapshot.containers.pendingUpdates.toString(), Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryText("Total containers", Modifier.weight(1f))
                Text("${snapshot.containers.total}", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun HostCard(host: DashboardHostSnapshot?) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Host", style = MaterialTheme.typography.titleMedium)
            if (host == null) {
                SecondaryText("Host information unavailable")
            } else {
                HostInfoRow("Host name", host.host.name)
                HostInfoRow("Storage driver", host.host.storageDriver)
                HostInfoRow("OS", "${host.docker.os} · ${host.docker.arch}")
                HostInfoRow("Kernel", host.docker.kernelVersion)
                HostInfoRow("Docker version", host.docker.version)
                HostInfoRow("API version", host.docker.apiVersion)
                HostInfoRow("Connection", host.docker.connectionType.localizedConnectionTypeLabel)
                host.docker.socketPath?.takeIf { it.isNotEmpty() }?.let { HostInfoRow("Socket", it) }
                host.dockhand?.version?.let { HostInfoRow("Dockhand", it) }
                host.dockhand?.runtime?.let { HostInfoRow("Runtime", it) }
                host.dockhand?.database?.let { HostInfoRow("Database", it) }
            }
        }
    }
}

@Composable
private fun HostInfoRow(title: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        SecondaryText(title, Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InventoryCard(snapshot: DashboardEnvironmentSnapshot, host: DashboardHostSnapshot?) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Inventory", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                InventoryTile(
                    "Images",
                    snapshot.images.total.toString(),
                    snapshot.images.totalSize.dockhandByteCount,
                    Modifier.weight(1f)
                )
                InventoryTile(
                    "Stacks",
                    snapshot.stacks.total.toString(),
                    "${snapshot.stacks.running} running · ${snapshot.stacks.stopped} stopped",
                    Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                InventoryTile(
                    "Volumes",
                    snapshot.volumes.total.toString(),
                    if (snapshot.volumes.totalSize > 0) snapshot.volumes.totalSize.dockhandByteCount else "No size data",
                    Modifier.weight(1f)
                )
                InventoryTile(
                    "Networks",
                    snapshot.networks.total.toString(),
                    host?.host?.storageDriver ?: "Ready",
                    Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                InventoryTile(
                    "Events",
                    snapshot.events.today.toString(),
                    "${snapshot.events.total} total",
                    Modifier.weight(1f)
                )
                InventoryTile(
                    "Build cache",
                    snapshot.buildCacheSize.dockhandByteCount,
                    "Containers ${snapshot.containersSize.dockhandByteCount}",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun InventoryTile(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
    }
}

@Composable
private fun PendingUpdatesCard(
    store: DashboardScreenStore,
    onCheck: () -> Unit,
    onUpdate: (List<PendingContainerUpdate>) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Updates",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (store.pendingUpdates.isNotEmpty()) {
                    SecondaryText("${store.pendingUpdates.size} pending")
                }
            }
            val busy = store.isCheckingUpdates || store.isUpdatingContainers
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCheck, enabled = !busy) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Check for updates")
                }
                if (store.pendingUpdates.isNotEmpty()) {
                    OutlinedButton(onClick = { onUpdate(store.pendingUpdates) }, enabled = !busy) {
                        Text("Update all")
                    }
                }
            }
            if (store.isCheckingUpdates) {
                val progress = store.updateCheckProgress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (store.isUpdatingContainers) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    SecondaryText("Updating containers")
                }
            }
            store.operationMessage?.let { message -> SecondaryText(message) }
            if (store.pendingUpdates.isEmpty() && !store.isCheckingUpdates) {
                SecondaryText("No pending updates")
            }
            store.pendingUpdates.forEach { update ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(update.containerName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = update.currentImage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val stackNames = store.stackNamesFor(update.containerID)
                    SecondaryText(
                        if (stackNames.isEmpty()) "Standalone container" else stackNames.joinToString(", ")
                    )
                    TextButton(
                        onClick = { onUpdate(listOf(update)) },
                        enabled = !busy
                    ) {
                        Text("Update")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        SecondaryText(subtitle)
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
private fun VolumeRow(volume: VolumeSnapshot, stackNamesFor: (String) -> List<String>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(volume.name, style = MaterialTheme.typography.titleSmall)
        SecondaryText("${volume.driver} · ${volume.scope}")
        if (volume.usedBy.isEmpty()) {
            SecondaryText("Not attached to a container")
        } else {
            volume.usedBy.forEach { usage ->
                val stackNames = stackNamesFor(usage.containerID)
                SecondaryText(
                    if (stackNames.isEmpty()) usage.containerName else "${usage.containerName} · ${stackNames.joinToString(", ")}"
                )
            }
        }
    }
}

@Composable
private fun NetworkRow(network: NetworkSnapshot, store: DashboardScreenStore) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(network.name, style = MaterialTheme.typography.titleSmall)
        SecondaryText(
            if (network.isInternal) "${network.driver} · ${network.scope} · Internal" else "${network.driver} · ${network.scope}"
        )
        if (network.subnets.isNotEmpty()) {
            SecondaryText(network.subnets.joinToString(", "))
        }
        if (network.containers.isEmpty()) {
            SecondaryText("No connected containers")
        } else {
            network.containers.forEach { usage ->
                val stackNames = store.stackNamesFor(usage.containerID)
                SecondaryText(
                    buildString {
                        append(usage.containerName)
                        if (usage.ipv4Address.isNotEmpty()) append(" · ${usage.ipv4Address}")
                        if (stackNames.isNotEmpty()) append(" · ${stackNames.joinToString(", ")}")
                    }
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: ContainerEventSnapshot, stackNames: List<String>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = eventActionText(event.action),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            SecondaryText(eventTimestampText(event.timestamp))
        }
        SecondaryText(event.containerName ?: "Unknown container")
        event.image?.takeIf { it.isNotEmpty() }?.let { image ->
            Text(
                text = image,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (stackNames.isNotEmpty()) {
            SecondaryText(stackNames.joinToString(", "))
        }
    }
}

@Composable
private fun EnvironmentSummaryCard(environment: Environment) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(environment.name, style = MaterialTheme.typography.titleLarge)
            SecondaryText(environment.hostSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HeroMetric("Protocol", environment.protocolName.uppercase(Locale.US), Modifier.weight(1f))
                HeroMetric("Port", environment.port.toString(), Modifier.weight(1f))
                HeroMetric("Type", environment.connectionType.localizedConnectionTypeLabel, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

private fun eventActionText(action: String): String = action
    .replace('_', ' ')
    .split(' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.US) } }

private fun eventTimestampText(raw: String): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date.from(Instant.parse(raw)))
}.getOrDefault(raw)
