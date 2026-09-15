package pro.dockhand.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pro.dockhand.mobile.api.DockhandApi
import pro.dockhand.mobile.api.DockhandServiceError
import pro.dockhand.mobile.api.ImagePullProgressEvent
import pro.dockhand.mobile.api.ImagePullStartResult
import pro.dockhand.mobile.api.ImageScanDocument
import pro.dockhand.mobile.api.ImageSummary
import pro.dockhand.mobile.api.dockhandUserFacingMessage
import pro.dockhand.mobile.app.AppViewModel
import pro.dockhand.mobile.ui.allDigests
import pro.dockhand.mobile.ui.allTags
import pro.dockhand.mobile.ui.createdAtText
import pro.dockhand.mobile.ui.displayName
import pro.dockhand.mobile.ui.dockhandByteCount
import pro.dockhand.mobile.ui.isUnused
import pro.dockhand.mobile.ui.labelPairs
import pro.dockhand.mobile.ui.localizedContainersCountText
import pro.dockhand.mobile.ui.shortId

private const val PULL_TIMEOUT_MS = 1_800_000L
private const val PULL_POLL_MS = 500L
private const val SCAN_POLL_MS = 1_000L
private const val SCAN_MAX_ATTEMPTS = 60
private const val LIST_ACTION_SCOPE = "list"

private enum class ImagePullStatus { IDLE, PULLING, COMPLETE, FAILED, CANCELLED }

private data class ImagePullLayer(
    val id: String,
    val status: String,
    val progress: String?,
    val current: Long?,
    val total: Long?,
    val order: Int,
    val isComplete: Boolean
) {
    val percentage: Float?
        get() {
            val currentValue = current ?: return null
            val totalValue = total ?: return null
            if (totalValue <= 0L) return null
            return (currentValue.toFloat() / totalValue.toFloat()).coerceIn(0f, 1f)
        }

    val isAlreadyPresent: Boolean get() = status.equals("Already exists", ignoreCase = true)
}

private data class ImagePullTarget(val suggestedName: String, val actionScope: String)

private class ImagesScreenStore {
    var images by mutableStateOf<List<ImageSummary>>(emptyList())
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var actionMessage by mutableStateOf<String?>(null)
    var activeActionID by mutableStateOf<String?>(null)
    var pullStatus by mutableStateOf(ImagePullStatus.IDLE)
    var pullLayers by mutableStateOf<List<ImagePullLayer>>(emptyList())
    var pullOutput by mutableStateOf<List<String>>(emptyList())
    var pullStatusMessage by mutableStateOf<String?>(null)
    var pullError by mutableStateOf<String?>(null)

    private var actionMessageTarget by mutableStateOf<String?>(null)

    val completedPullLayerCount: Int get() = pullLayers.count { it.isComplete }

    val pullProgress: Float
        get() = if (pullLayers.isEmpty()) 0f else completedPullLayerCount.toFloat() / pullLayers.size.toFloat()

    val pullDownloadedBytes: Long get() = pullLayers.sumOf { it.current ?: 0L }

    val pullTotalBytes: Long get() = pullLayers.sumOf { it.total ?: 0L }

    val isPulledImageUpToDate: Boolean
        get() = pullLayers.isNotEmpty() && pullLayers.all { it.isAlreadyPresent }

    suspend fun load(viewModel: AppViewModel, environmentId: Int?) {
        val service = viewModel.service() ?: return
        if (environmentId == null) {
            images = emptyList()
            return
        }
        isLoading = true
        error = null
        try {
            images = service.fetchImages(environmentId)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        } catch (loadError: Throwable) {
            if (loadError is CancellationException) throw loadError
            error = loadError.dockhandUserFacingMessage
        } finally {
            isLoading = false
        }
    }

    suspend fun pullImage(
        imageName: String,
        tag: String?,
        actionScope: String,
        viewModel: AppViewModel,
        environmentId: Int?
    ) {
        val service = viewModel.service() ?: return
        if (environmentId == null || imageName.isBlank()) return
        resetPullProgress()
        activeActionID = "pull"
        pullStatus = ImagePullStatus.PULLING
        actionMessage = null
        actionMessageTarget = null
        error = null
        try {
            pullOutput = pullOutput + "Starting pull for $imageName"
            val normalizedTag = tag?.trim()?.takeIf { it.isNotEmpty() }
            when (val started = service.startImagePull(imageName, environmentId, normalizedTag)) {
                is ImagePullStartResult.Completed -> applyPullEvent(started.event)
                is ImagePullStartResult.Job -> watchImagePullJob(service, started.id)
            }
            pullStatus = ImagePullStatus.COMPLETE
            val completionMessage = if (isPulledImageUpToDate) "Image is already up to date" else "Pull completed"
            pullOutput = pullOutput + completionMessage
            actionMessage = completionMessage
            actionMessageTarget = actionScope
            load(viewModel, environmentId)
        } catch (pullFailure: Throwable) {
            if (pullFailure is CancellationException) {
                pullStatus = ImagePullStatus.CANCELLED
                pullOutput = pullOutput + "Progress monitoring cancelled"
                throw pullFailure
            }
            pullStatus = ImagePullStatus.FAILED
            pullError = pullFailure.dockhandUserFacingMessage
            pullOutput = pullOutput + "Error: ${pullError.orEmpty()}"
        } finally {
            activeActionID = null
        }
    }

    fun resetPullProgress() {
        pullStatus = ImagePullStatus.IDLE
        pullLayers = emptyList()
        pullOutput = emptyList()
        pullStatusMessage = null
        pullError = null
    }

    private suspend fun watchImagePullJob(service: DockhandApi, jobId: String) {
        val deadline = System.currentTimeMillis() + PULL_TIMEOUT_MS
        var cursor = 0
        while (true) {
            val jobSnapshot = service.fetchImagePullJob(jobId)
            if (cursor < jobSnapshot.lines.size) {
                jobSnapshot.lines.drop(cursor)
                    .filter { it.event != "result" }
                    .forEach { line -> applyPullEvent(line.data) }
                cursor = jobSnapshot.lines.size
            }
            if (jobSnapshot.status != "running") {
                if (jobSnapshot.status == "error") {
                    throw DockhandServiceError.Message(jobSnapshot.result?.error ?: "Image pull failed")
                }
                val resultError = jobSnapshot.result?.error
                if (!resultError.isNullOrEmpty()) throw DockhandServiceError.Message(resultError)
                return
            }
            if (System.currentTimeMillis() >= deadline) {
                throw DockhandServiceError.Message("Image pull timed out")
            }
            delay(PULL_POLL_MS)
        }
    }

    private fun applyPullEvent(event: ImagePullProgressEvent) {
        if (event.status == "error") {
            pullError = event.error ?: "Image pull failed"
            return
        }
        if (event.status == "complete") return

        val layerID = event.id
        val isLayer = layerID != null && Regex("^[a-fA-F0-9]{12}$").matches(layerID)
        if (isLayer) {
            val normalizedStatus = event.status ?: "Processing"
            val statusLower = normalizedStatus.lowercase()
            val isComplete = statusLower == "pull complete" || statusLower == "already exists"
            val existingIndex = pullLayers.indexOfFirst { it.id == layerID }
            if (existingIndex >= 0) {
                val existing = pullLayers[existingIndex]
                val wasComplete = existing.isComplete
                val updated = existing.copy(
                    status = normalizedStatus,
                    progress = event.progress ?: existing.progress,
                    current = event.progressDetail?.current ?: existing.current,
                    total = event.progressDetail?.total ?: existing.total,
                    isComplete = wasComplete || isComplete
                )
                pullLayers = pullLayers.toMutableList().also { it[existingIndex] = updated }
                if (isComplete && !wasComplete) {
                    pullOutput = pullOutput + "$layerID: $normalizedStatus"
                }
            } else {
                pullLayers = pullLayers + ImagePullLayer(
                    id = layerID,
                    status = normalizedStatus,
                    progress = event.progress,
                    current = event.progressDetail?.current,
                    total = event.progressDetail?.total,
                    order = pullLayers.size,
                    isComplete = isComplete
                )
                if (isComplete) {
                    pullOutput = pullOutput + "$layerID: $normalizedStatus"
                }
            }
            return
        }

        val status = event.status
        if (!status.isNullOrEmpty()) {
            val message = if (event.id != null) "${event.id}: $status" else status
            pullStatusMessage = message
            pullOutput = pullOutput + message
        }
    }

    suspend fun pruneImages(danglingOnly: Boolean, viewModel: AppViewModel, environmentId: Int?) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        activeActionID = if (danglingOnly) "prune" else "prune-unused"
        actionMessage = null
        actionMessageTarget = null
        error = null
        try {
            service.pruneImages(environmentId, danglingOnly)
            actionMessage = if (danglingOnly) "Dangling images pruned" else "Unused images pruned"
            actionMessageTarget = LIST_ACTION_SCOPE
            load(viewModel, environmentId)
        } catch (pruneError: Throwable) {
            if (pruneError is CancellationException) throw pruneError
            error = pruneError.dockhandUserFacingMessage
        } finally {
            activeActionID = null
        }
    }

    suspend fun tagImage(
        image: ImageSummary,
        repo: String,
        tag: String,
        viewModel: AppViewModel,
        environmentId: Int?
    ) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        activeActionID = "tag:${image.id}"
        actionMessage = null
        actionMessageTarget = null
        error = null
        try {
            service.tagImage(image.id, environmentId, repo, tag)
            actionMessage = "$repo:$tag created"
            actionMessageTarget = image.id
            load(viewModel, environmentId)
        } catch (tagError: Throwable) {
            if (tagError is CancellationException) throw tagError
            error = tagError.dockhandUserFacingMessage
        } finally {
            activeActionID = null
        }
    }

    suspend fun deleteImage(reference: String, viewModel: AppViewModel, environmentId: Int?) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        activeActionID = "delete:$reference"
        actionMessage = null
        actionMessageTarget = null
        error = null
        try {
            service.deleteImage(reference, environmentId)
            actionMessage = "$reference deleted"
            actionMessageTarget = reference
            load(viewModel, environmentId)
        } catch (deleteError: Throwable) {
            if (deleteError is CancellationException) throw deleteError
            error = deleteError.dockhandUserFacingMessage
        } finally {
            activeActionID = null
        }
    }

    suspend fun deleteImageTag(tag: String, imageID: String, viewModel: AppViewModel, environmentId: Int?) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        activeActionID = "delete-tag:$tag"
        actionMessage = null
        actionMessageTarget = null
        error = null
        try {
            service.deleteImageTag(tag, environmentId)
            actionMessage = "$tag removed"
            actionMessageTarget = imageID
            load(viewModel, environmentId)
        } catch (deleteError: Throwable) {
            if (deleteError is CancellationException) throw deleteError
            error = deleteError.dockhandUserFacingMessage
        } finally {
            activeActionID = null
        }
    }

    suspend fun scanImage(
        image: ImageSummary,
        viewModel: AppViewModel,
        environmentId: Int?
    ): ImageScanDocument {
        val service = viewModel.service() ?: throw DockhandServiceError.InvalidResponse
        if (environmentId == null) throw DockhandServiceError.InvalidResponse
        activeActionID = "scan:${image.id}"
        actionMessage = null
        actionMessageTarget = null
        error = null
        try {
            var document = service.scanImage(image.displayName, environmentId)
            var attempts = 0
            while (
                document.stage.lowercase() in setOf("staged", "queued", "pending", "running") &&
                attempts < SCAN_MAX_ATTEMPTS
            ) {
                delay(SCAN_POLL_MS)
                document = service.scanImage(image.displayName, environmentId)
                attempts += 1
            }
            return document
        } finally {
            activeActionID = null
        }
    }

    fun imageOrNull(id: String): ImageSummary? = images.firstOrNull { it.id == id }

    fun listActionMessage(): String? =
        if (actionMessageTarget == LIST_ACTION_SCOPE) actionMessage else null

    fun actionMessageFor(imageID: String): String? =
        if (actionMessageTarget == imageID) actionMessage else null

    fun isRunning(action: String, reference: String): Boolean = activeActionID == "$action:$reference"
}

@Composable
fun ImagesScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val appState by viewModel.state.collectAsState()
    val environment = appState.environments.firstOrNull { it.id == appState.selectedEnvironmentId }
        ?: appState.environments.firstOrNull()
    val store = remember { ImagesScreenStore() }
    val environmentId = environment?.id
    var selectedImageId by remember { mutableStateOf<String?>(null) }
    var pullTarget by remember { mutableStateOf<ImagePullTarget?>(null) }
    var pruneDanglingOnly by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(appState.selectedProfileId, environmentId, appState.dashboardRefreshRevision) {
        store.load(viewModel, environmentId)
    }

    if (environment == null) {
        ImageEmptyState(
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

    val selectedImage = selectedImageId?.let { store.imageOrNull(it) }
    if (selectedImage != null) {
        ImageDetail(
            viewModel = viewModel,
            store = store,
            image = selectedImage,
            environmentId = environmentId,
            onBack = { selectedImageId = null },
            onPullRequested = { target -> pullTarget = target }
        )
    } else {
        ImageList(
            viewModel = viewModel,
            store = store,
            environmentId = environmentId,
            onSelect = { selectedImageId = it },
            onPull = { pullTarget = ImagePullTarget("", LIST_ACTION_SCOPE) },
            onPrune = { pruneDanglingOnly = it },
            modifier = modifier
        )
    }

    pullTarget?.let { target ->
        PullImageDialog(
            viewModel = viewModel,
            store = store,
            environmentId = environmentId,
            target = target,
            onDismiss = { pullTarget = null }
        )
    }

    pruneDanglingOnly?.let { danglingOnly ->
        val coroutineScope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { pruneDanglingOnly = null },
            title = { Text(if (danglingOnly) "Prune dangling images" else "Prune unused images") },
            text = {
                Text(
                    if (danglingOnly) {
                        "This removes untagged intermediate layers in the selected Dockhand environment."
                    } else {
                        "This removes every image not used by any container in the selected Dockhand environment."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pruneDanglingOnly = null
                        coroutineScope.launch { store.pruneImages(danglingOnly, viewModel, environmentId) }
                    }
                ) {
                    Text(if (danglingOnly) "Prune" else "Prune unused")
                }
            },
            dismissButton = {
                TextButton(onClick = { pruneDanglingOnly = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ImageList(
    viewModel: AppViewModel,
    store: ImagesScreenStore,
    environmentId: Int?,
    onSelect: (String) -> Unit,
    onPull: () -> Unit,
    onPrune: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }

    val sorted = store.images.sortedWith(
        compareBy<ImageSummary>({ !it.isUnused }, { it.displayName.lowercase() })
    )
    val filtered = sorted.filter { image ->
        val matchesSearch = search.isBlank() ||
            image.displayName.contains(search.trim(), ignoreCase = true) ||
            image.shortId.startsWith(search.trim(), ignoreCase = true) ||
            image.id.contains(search.trim(), ignoreCase = true)
        val matchesFilter = when (filter) {
            "in-use" -> image.containers > 0
            "unused" -> image.isUnused
            else -> true
        }
        matchesSearch && matchesFilter
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        store.error?.let { message -> ImageErrorCard(message) }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search images") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            FilterChip(selected = filter == "in-use", onClick = { filter = "in-use" }, label = { Text("In use") })
            FilterChip(selected = filter == "unused", onClick = { filter = "unused" }, label = { Text("Unused") })
        }

        store.listActionMessage()?.let { message -> ImageSecondaryText(message) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onPrune(true) },
                enabled = store.activeActionID == null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Prune", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = { onPrune(false) },
                enabled = store.activeActionID == null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Prune unused", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onPull, modifier = Modifier.weight(1f)) {
                Text("Pull", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (store.isLoading && store.images.isEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        } else if (filtered.isEmpty()) {
            ImageSecondaryText(if (filter == null) "No images" else "No images in $filter")
        } else {
            filtered.forEach { image ->
                ImageRow(image = image, onClick = { onSelect(image.id) })
            }
        }
    }
}

@Composable
private fun ImageRow(image: ImageSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = image.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (image.isUnused) {
                    Text(
                        text = "Unused",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Text(
                text = image.shortId,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ImageSecondaryText(
                "${image.size.dockhandByteCount} · ${image.createdAtText} · ${image.containers.localizedContainersCountText}"
            )
        }
    }
}

@Composable
private fun ImageDetail(
    viewModel: AppViewModel,
    store: ImagesScreenStore,
    image: ImageSummary,
    environmentId: Int?,
    onBack: () -> Unit,
    onPullRequested: (ImagePullTarget) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val scope = remember { viewModel.connectionScope }
    val isCurrentScope = viewModel.isCurrentScope(scope)

    var scanDialogVisible by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<ImageScanDocument?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var pendingTagDeletion by remember { mutableStateOf<String?>(null) }

    val liveImage = store.imageOrNull(image.id) ?: image

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = liveImage.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (liveImage.isUnused) {
                    ImageSecondaryText("Unused")
                }
            }
        }

        if (!isCurrentScope) {
            ImageWarningCard("Server or environment changed. Go back and reopen this image before running actions.")
        }

        store.error?.let { message -> ImageErrorCard(message) }
        store.actionMessageFor(liveImage.id)?.let { message -> ImageSecondaryText(message) }

        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = liveImage.id,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ImageMetric("Size", liveImage.size.dockhandByteCount, Modifier.weight(1f))
                    ImageMetric("Virtual", liveImage.virtualSize.dockhandByteCount, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ImageMetric("Created", liveImage.createdAtText, Modifier.weight(1f))
                    ImageMetric("Used by", liveImage.containers.localizedContainersCountText, Modifier.weight(1f))
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Actions", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            scanResult = null
                            scanError = null
                            scanDialogVisible = true
                            coroutineScope.launch {
                                isScanning = true
                                try {
                                    scanResult = store.scanImage(liveImage, viewModel, environmentId)
                                } catch (scanFailure: Throwable) {
                                    if (scanFailure is CancellationException) throw scanFailure
                                    scanError = scanFailure.dockhandUserFacingMessage
                                } finally {
                                    isScanning = false
                                }
                            }
                        },
                        enabled = isCurrentScope,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Scan", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { showTagDialog = true },
                        enabled = isCurrentScope,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Tag", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onPullRequested(ImagePullTarget(liveImage.displayName, liveImage.id)) },
                        enabled = isCurrentScope,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pull", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { showDeleteConfirmation = true },
                        enabled = isCurrentScope && liveImage.containers == 0,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Delete", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (liveImage.containers > 0) {
                    ImageSecondaryText("Delete is disabled while the image is used by containers.")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Tags", style = MaterialTheme.typography.titleMedium)
                if (liveImage.allTags.isEmpty()) {
                    ImageSecondaryText("No tags")
                } else {
                    liveImage.allTags.forEach { tag ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(Modifier.weight(1f)) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                            TextButton(
                                onClick = { pendingTagDeletion = tag },
                                enabled = isCurrentScope && !store.isRunning("delete-tag", tag)
                            ) {
                                Text("Delete")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        if (liveImage.allDigests.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Digests", style = MaterialTheme.typography.titleMedium)
                    liveImage.allDigests.forEach { digest ->
                        SelectionContainer {
                            Text(
                                text = digest,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (liveImage.labelPairs.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Labels", style = MaterialTheme.typography.titleMedium)
                    liveImage.labelPairs.forEach { (key, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary
                            )
                            ImageSecondaryText(value)
                        }
                    }
                }
            }
        }
    }

    if (scanDialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!isScanning) scanDialogVisible = false },
            title = { Text("Scan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isScanning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp))
                            Text("Scanning ${liveImage.displayName}")
                        }
                    }
                    scanError?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    scanResult?.let { document ->
                        Text(document.stage.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                        ImageSecondaryText(document.message)
                        document.progress?.let { progress ->
                            LinearProgressIndicator(
                                progress = { (progress.toFloat() / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            ImageSecondaryText("$progress%")
                        }
                        if (document.results.isEmpty()) {
                            ImageSecondaryText("No vulnerabilities reported")
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = document.results.joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { scanDialogVisible = false }, enabled = !isScanning) {
                    Text("Close")
                }
            }
        )
    }

    if (showTagDialog) {
        TagImageDialog(
            image = liveImage,
            onDismiss = { showTagDialog = false },
            onSave = { repo, tag ->
                showTagDialog = false
                coroutineScope.launch { store.tagImage(liveImage, repo, tag, viewModel, environmentId) }
            }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete image") },
            text = { Text("This removes the full image object from the selected Dockhand environment.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        coroutineScope.launch { store.deleteImage(liveImage.id, viewModel, environmentId) }
                    }
                ) {
                    Text("Delete image")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingTagDeletion?.let { tag ->
        AlertDialog(
            onDismissRequest = { pendingTagDeletion = null },
            title = { Text("Delete tag") },
            text = { Text("Remove tag $tag?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTagDeletion = null
                        coroutineScope.launch { store.deleteImageTag(tag, liveImage.id, viewModel, environmentId) }
                    }
                ) {
                    Text("Delete tag")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTagDeletion = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PullImageDialog(
    viewModel: AppViewModel,
    store: ImagesScreenStore,
    environmentId: Int?,
    target: ImagePullTarget,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var imageName by remember(target) { mutableStateOf(target.suggestedName) }
    var tag by remember(target) { mutableStateOf("") }
    val pulling = store.pullStatus == ImagePullStatus.PULLING

    AlertDialog(
        onDismissRequest = { if (!pulling) onDismiss() },
        title = { Text("Pull image") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = imageName,
                    onValueChange = { imageName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Image name") },
                    placeholder = { Text("nginx") },
                    enabled = !pulling,
                    singleLine = true
                )
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tag (optional)") },
                    enabled = !pulling,
                    singleLine = true
                )

                if (store.pullStatus != ImagePullStatus.IDLE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (pulling) {
                            CircularProgressIndicator(Modifier.size(18.dp))
                        }
                        Text(store.pullStatusTitle, style = MaterialTheme.typography.titleSmall)
                        if (store.pullLayers.isNotEmpty()) {
                            ImageSecondaryText("${store.completedPullLayerCount} / ${store.pullLayers.size} layers")
                        }
                    }
                    if (pulling && store.pullLayers.isNotEmpty()) {
                        LinearProgressIndicator(
                            progress = { store.pullProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (store.pullTotalBytes > 0) {
                        ImageSecondaryText(
                            "Downloaded: ${store.pullDownloadedBytes.dockhandByteCount} / ${store.pullTotalBytes.dockhandByteCount}"
                        )
                    }
                    store.pullStatusMessage?.let { message -> ImageSecondaryText(message) }
                    store.pullError?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (store.pullOutput.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = store.pullOutput.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                pulling -> TextButton(onClick = onDismiss) { Text("Cancel") }
                store.pullStatus == ImagePullStatus.COMPLETE -> TextButton(onClick = onDismiss) { Text("Close") }
                else -> Button(
                    onClick = {
                        coroutineScope.launch {
                            store.pullImage(
                                imageName = imageName.trim(),
                                tag = tag,
                                actionScope = target.actionScope,
                                viewModel = viewModel,
                                environmentId = environmentId
                            )
                        }
                    },
                    enabled = imageName.isNotBlank()
                ) {
                    Text(if (store.pullStatus == ImagePullStatus.IDLE) "Pull" else "Retry")
                }
            }
        },
        dismissButton = {
            if (!pulling && store.pullStatus != ImagePullStatus.COMPLETE) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun TagImageDialog(
    image: ImageSummary,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var repo by remember(image.id) { mutableStateOf(defaultRepoName(image.displayName)) }
    var tag by remember(image.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag image") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Repository") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tag") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(repo.trim(), tag.trim()) },
                enabled = repo.isNotBlank() && tag.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private val ImagesScreenStore.pullStatusTitle: String
    get() = when (pullStatus) {
        ImagePullStatus.IDLE -> ""
        ImagePullStatus.PULLING -> "Pulling layers…"
        ImagePullStatus.COMPLETE -> if (isPulledImageUpToDate) "Image is already up to date" else "Pull completed"
        ImagePullStatus.FAILED -> "Pull failed"
        ImagePullStatus.CANCELLED -> "Monitoring cancelled"
    }

private fun defaultRepoName(displayName: String): String {
    val colonIndex = displayName.lastIndexOf(':')
    val slashIndex = displayName.lastIndexOf('/')
    return if (colonIndex > slashIndex) displayName.substring(0, colonIndex) else displayName
}

@Composable
private fun ImageMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ImageEmptyState(title: String, message: String, modifier: Modifier = Modifier) {
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
private fun ImageErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
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
private fun ImageWarningCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
    }
}

@Composable
private fun ImageSecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
