package pro.dockhand.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.dockhand.mobile.api.DockhandApi
import pro.dockhand.mobile.api.DockhandServiceError
import pro.dockhand.mobile.api.StackAction
import pro.dockhand.mobile.api.StackContainerDetail
import pro.dockhand.mobile.api.StackDeployOptions
import pro.dockhand.mobile.api.StackEditorDocument
import pro.dockhand.mobile.api.StackRedeployResult
import pro.dockhand.mobile.api.StackRedeployStartResult
import pro.dockhand.mobile.api.StackSummary
import pro.dockhand.mobile.api.UpdateRawEnvRequest
import pro.dockhand.mobile.api.UpdateStackComposeRequest
import pro.dockhand.mobile.api.dockhandUserFacingMessage
import pro.dockhand.mobile.app.AppViewModel
import pro.dockhand.mobile.ui.CodeHighlightCache
import pro.dockhand.mobile.ui.ContainerAction
import pro.dockhand.mobile.ui.EnvLineHighlighter
import pro.dockhand.mobile.ui.LineNumberGutter
import pro.dockhand.mobile.ui.YamlLineHighlighter
import pro.dockhand.mobile.ui.canPerform
import pro.dockhand.mobile.ui.codeHighlightTransformation
import pro.dockhand.mobile.ui.dockhandStateRank
import pro.dockhand.mobile.ui.localizedDockhandStateLabel
import pro.dockhand.mobile.ui.localizedServicesCountText
import pro.dockhand.mobile.ui.localizedStatusText
import pro.dockhand.mobile.ui.normalizedDockhandState
import pro.dockhand.mobile.ui.rememberCodeHighlightColors
import pro.dockhand.mobile.ui.servicesCount
import pro.dockhand.mobile.ui.statusRank
import pro.dockhand.mobile.ui.supportsRedeploy

private const val REDEPLOY_TIMEOUT_MS = 600_000L
private const val REDEPLOY_POLL_MS = 500L

private enum class StackRedeployStatus { IDLE, DEPLOYING, COMPLETE, FAILED, CANCELLED }

private class StacksScreenStore {
    var stacks by mutableStateOf<List<StackSummary>>(emptyList())
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var actionMessage by mutableStateOf<String?>(null)
    var document by mutableStateOf<StackEditorDocument?>(null)
    var isLoadingDocument by mutableStateOf(false)
    var isSaving by mutableStateOf(false)
    var saveMessage by mutableStateOf<String?>(null)
    var saveMessageIsError by mutableStateOf(false)
    var redeployStatus by mutableStateOf(StackRedeployStatus.IDLE)
    var redeploySteps by mutableStateOf<List<String>>(emptyList())
    var redeployOutput by mutableStateOf<List<String>>(emptyList())
    var redeployError by mutableStateOf<String?>(null)
    var isRedeploying by mutableStateOf(false)

    private var actionMessageOwner by mutableStateOf<String?>(null)
    private var activeStackActionID by mutableStateOf<String?>(null)
    private var activeContainerActionID by mutableStateOf<String?>(null)

    suspend fun load(viewModel: AppViewModel, environmentId: Int?) {
        val service = viewModel.service() ?: return
        if (environmentId == null) {
            stacks = emptyList()
            return
        }
        isLoading = true
        error = null
        try {
            stacks = service.fetchStacks(environmentId)
                .sortedWith(compareBy<StackSummary>({ it.statusRank }, { it.name.lowercase() }))
        } catch (loadError: Throwable) {
            if (loadError is CancellationException) throw loadError
            error = loadError.dockhandUserFacingMessage
        } finally {
            isLoading = false
        }
    }

    suspend fun runStackAction(
        action: StackAction,
        stackName: String,
        viewModel: AppViewModel,
        environmentId: Int?
    ) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        activeStackActionID = stackActionID(action, stackName)
        error = null
        try {
            service.stackAction(action, stackName, environmentId)
            actionMessage = when (action) {
                StackAction.START -> "$stackName started"
                StackAction.STOP -> "$stackName stopped"
                StackAction.RESTART -> "$stackName restarted"
                StackAction.DOWN -> "$stackName brought down"
                StackAction.REDEPLOY -> "$stackName redeployed"
            }
            actionMessageOwner = stackName
            viewModel.requestDashboardRefresh()
            load(viewModel, environmentId)
        } catch (actionError: Throwable) {
            if (actionError is CancellationException) throw actionError
            error = actionError.dockhandUserFacingMessage
        } finally {
            activeStackActionID = null
        }
    }

    suspend fun redeploy(
        stack: StackSummary,
        viewModel: AppViewModel,
        environmentId: Int?,
        options: StackDeployOptions
    ) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        activeStackActionID = stackActionID(StackAction.REDEPLOY, stack.name)
        isRedeploying = true
        error = null
        resetRedeployProgress()
        redeployStatus = StackRedeployStatus.DEPLOYING
        if (options.pull) {
            redeploySteps = listOf("Starting redeploy…")
        }
        try {
            if (options.pull) {
                val result = when (val started = service.startStackRedeploy(stack.name, environmentId, options)) {
                    is StackRedeployStartResult.Job -> watchStackRedeployJob(service, started.id)
                    is StackRedeployStartResult.Completed -> started.result
                }
                applyRedeployResult(result)
                redeploySteps = redeploySteps + "Redeploy completed"
            } else {
                service.redeployStack(stack.name, environmentId, options)
                redeploySteps = listOf("Redeploy completed")
            }
            redeployStatus = StackRedeployStatus.COMPLETE
            actionMessage = "${stack.name} redeployed"
            actionMessageOwner = stack.name
            if (options.pull) {
                stack.containerDetails.forEach { container ->
                    try {
                        service.clearPendingContainerUpdate(container.id, environmentId)
                    } catch (clearError: Throwable) {
                        if (clearError is CancellationException) throw clearError
                    }
                }
            }
            viewModel.requestDashboardRefresh()
            load(viewModel, environmentId)
        } catch (redeployError: Throwable) {
            if (redeployError is CancellationException) {
                redeployStatus = StackRedeployStatus.CANCELLED
                redeploySteps = redeploySteps + "Progress monitoring cancelled"
                throw redeployError
            }
            redeployStatus = StackRedeployStatus.FAILED
            this.redeployError = redeployError.dockhandUserFacingMessage
            error = redeployError.dockhandUserFacingMessage
        } finally {
            isRedeploying = false
            activeStackActionID = null
        }
    }

    private suspend fun watchStackRedeployJob(service: DockhandApi, jobId: String): StackRedeployResult {
        val deadline = System.currentTimeMillis() + REDEPLOY_TIMEOUT_MS
        var cursor = 0
        while (true) {
            val jobSnapshot = service.fetchStackRedeployJob(jobId)
            if (cursor < jobSnapshot.lines.size) {
                jobSnapshot.lines.drop(cursor)
                    .filter { it.event != "result" }
                    .forEach { line ->
                        val status = line.data.status
                        if (!status.isNullOrEmpty() && redeploySteps.lastOrNull() != status) {
                            redeploySteps = redeploySteps + status
                        }
                    }
                cursor = jobSnapshot.lines.size
            }
            if (jobSnapshot.status != "running") {
                val result = jobSnapshot.result ?: throw DockhandServiceError.InvalidResponse
                if (jobSnapshot.status == "error" || result.success == false || result.error != null) {
                    throw DockhandServiceError.Message(result.error ?: "Stack redeploy failed")
                }
                return result
            }
            if (System.currentTimeMillis() >= deadline) {
                throw DockhandServiceError.Message("Stack redeploy timed out")
            }
            delay(REDEPLOY_POLL_MS)
        }
    }

    private fun applyRedeployResult(result: StackRedeployResult) {
        val output = result.output
        if (output.isNullOrEmpty()) return
        val ansiPattern = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        redeployOutput = output.lines()
            .map { ansiPattern.replace(it, "") }
            .filter { it.trim().isNotEmpty() }
    }

    private fun resetRedeployProgress() {
        redeployStatus = StackRedeployStatus.IDLE
        redeploySteps = emptyList()
        redeployOutput = emptyList()
        redeployError = null
    }

    suspend fun deleteStack(
        stack: StackSummary,
        viewModel: AppViewModel,
        environmentId: Int?,
        deleteVolumes: Boolean
    ): Boolean {
        val service = viewModel.service() ?: return false
        if (environmentId == null) return false
        activeStackActionID = stackActionID(StackAction.DOWN, stack.name) + ":delete"
        error = null
        try {
            service.deleteStack(stack.name, environmentId, deleteVolumes)
            for (attempt in 0 until 6) {
                load(viewModel, environmentId)
                if (stacks.none { it.name == stack.name }) {
                    actionMessage = if (deleteVolumes) "${stack.name} removed with volumes" else "${stack.name} removed"
                    actionMessageOwner = stack.name
                    viewModel.requestDashboardRefresh()
                    return true
                }
                if (attempt < 5) delay(450)
            }
            error = "Dockhand reported success, but the stack is still present."
            return false
        } catch (deleteError: Throwable) {
            if (deleteError is CancellationException) throw deleteError
            error = deleteError.dockhandUserFacingMessage
            return false
        } finally {
            activeStackActionID = null
        }
    }

    suspend fun runContainerAction(
        action: ContainerAction,
        container: StackContainerDetail,
        stackName: String,
        viewModel: AppViewModel,
        environmentId: Int?
    ) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        activeContainerActionID = containerActionID(action, container.id)
        error = null
        try {
            when (action) {
                ContainerAction.START -> service.startContainer(container.id, environmentId)
                ContainerAction.STOP -> service.stopContainer(container.id, environmentId)
                ContainerAction.RESTART -> service.restartContainer(container.id, environmentId)
                ContainerAction.PAUSE -> service.pauseContainer(container.id, environmentId)
                ContainerAction.UNPAUSE -> service.unpauseContainer(container.id, environmentId)
            }
            actionMessage = "${container.name} ${action.completedLabel}"
            actionMessageOwner = stackName
            viewModel.requestDashboardRefresh()
            load(viewModel, environmentId)
        } catch (actionError: Throwable) {
            if (actionError is CancellationException) throw actionError
            error = actionError.dockhandUserFacingMessage
        } finally {
            activeContainerActionID = null
        }
    }

    suspend fun loadDocument(viewModel: AppViewModel, stackName: String, environmentId: Int?) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        isLoadingDocument = true
        try {
            document = service.fetchStackEditorDocument(stackName, environmentId)
            saveMessage = null
            saveMessageIsError = false
        } catch (loadError: Throwable) {
            if (loadError is CancellationException) throw loadError
            saveMessage = loadError.dockhandUserFacingMessage
            saveMessageIsError = true
        } finally {
            isLoadingDocument = false
        }
    }

    suspend fun saveCompose(
        text: String,
        stackName: String,
        viewModel: AppViewModel,
        environmentId: Int?
    ) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        try {
            StackEditorValidator.validateCompose(text)
        } catch (validationError: StackEditorValidationException) {
            saveMessage = validationError.message
            saveMessageIsError = true
            return
        }
        isSaving = true
        saveMessage = null
        saveMessageIsError = false
        try {
            val currentDocument = document
            service.updateStackCompose(
                stackName,
                environmentId,
                UpdateStackComposeRequest(
                    content = text,
                    composePath = currentDocument?.composePath,
                    envPath = currentDocument?.envPath ?: currentDocument?.suggestedEnvPath
                )
            )
            saveMessage = "Compose saved"
            load(viewModel, environmentId)
        } catch (saveError: Throwable) {
            if (saveError is CancellationException) throw saveError
            saveMessage = saveError.dockhandUserFacingMessage
            saveMessageIsError = true
        } finally {
            isSaving = false
        }
    }

    suspend fun saveEnv(
        text: String,
        stackName: String,
        viewModel: AppViewModel,
        environmentId: Int?
    ) {
        val service = viewModel.service() ?: return
        if (environmentId == null) return
        try {
            StackEditorValidator.validateEnv(text)
        } catch (validationError: StackEditorValidationException) {
            saveMessage = validationError.message
            saveMessageIsError = true
            return
        }
        isSaving = true
        saveMessage = null
        saveMessageIsError = false
        try {
            service.updateStackEnvFile(stackName, environmentId, UpdateRawEnvRequest(content = text))
            saveMessage = if (text.trim().isEmpty()) ".env removed" else ".env saved"
            load(viewModel, environmentId)
        } catch (saveError: Throwable) {
            if (saveError is CancellationException) throw saveError
            saveMessage = saveError.dockhandUserFacingMessage
            saveMessageIsError = true
        } finally {
            isSaving = false
        }
    }

    fun stackNamed(name: String): StackSummary? = stacks.firstOrNull { it.name == name }

    fun actionMessageFor(stackName: String): String? =
        if (actionMessageOwner == stackName) actionMessage else null

    fun isRunning(action: StackAction, stackName: String): Boolean =
        activeStackActionID == stackActionID(action, stackName)

    fun isRunning(action: ContainerAction, containerID: String): Boolean =
        activeContainerActionID == containerActionID(action, containerID)

    val hasPendingAction: Boolean
        get() = activeStackActionID != null || activeContainerActionID != null

    fun isDeletingStack(stackName: String): Boolean =
        activeStackActionID == stackActionID(StackAction.DOWN, stackName) + ":delete"

    private fun stackActionID(action: StackAction, stackName: String): String = "$stackName:${action.name}"

    private fun containerActionID(action: ContainerAction, containerID: String): String =
        "$containerID:${action.name}"
}

private val ContainerAction.completedLabel: String
    get() = when (this) {
        ContainerAction.START -> "started"
        ContainerAction.STOP -> "stopped"
        ContainerAction.RESTART -> "restarted"
        ContainerAction.PAUSE -> "paused"
        ContainerAction.UNPAUSE -> "resumed"
    }

@Composable
fun StacksScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val appState by viewModel.state.collectAsState()
    val environment = appState.environments.firstOrNull { it.id == appState.selectedEnvironmentId }
        ?: appState.environments.firstOrNull()
    val store = remember { StacksScreenStore() }
    val environmentId = environment?.id
    var selectedStackName by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(appState.selectedProfileId, environmentId, appState.dashboardRefreshRevision) {
        store.load(viewModel, environmentId)
    }

    if (environment == null) {
        StackEmptyState(
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

    val selectedStack = selectedStackName?.let { name -> store.stackNamed(name) }
    val scope = remember(selectedStackName) { viewModel.connectionScope }
    val isCurrentScope = viewModel.isCurrentScope(scope)

    LaunchedEffect(selectedStackName, scope) {
        val name = selectedStackName ?: return@LaunchedEffect
        store.loadDocument(viewModel, name, environmentId)
    }

    BackHandler(enabled = selectedStack != null) {
        if (editorOpen) {
            editorOpen = false
        } else {
            selectedStackName = null
        }
    }

    if (selectedStack != null) {
        if (editorOpen) {
            StackEditor(
                store = store,
                stackName = selectedStack.name,
                isCurrentScope = isCurrentScope,
                onBack = { editorOpen = false },
                onSave = { pane, compose, env ->
                    coroutineScope.launch {
                        if (pane == 0) {
                            store.saveCompose(compose, selectedStack.name, viewModel, environmentId)
                        } else {
                            store.saveEnv(env, selectedStack.name, viewModel, environmentId)
                        }
                    }
                },
                modifier = modifier
            )
        } else {
            StackDetail(
                viewModel = viewModel,
                store = store,
                stack = selectedStack,
                environmentId = environmentId,
                isCurrentScope = isCurrentScope,
                onBack = {
                    editorOpen = false
                    selectedStackName = null
                },
                onEdit = { editorOpen = true },
                modifier = modifier
            )
        }
    } else {
        StackList(
            store = store,
            selectedName = null,
            onSelect = {
                editorOpen = false
                selectedStackName = it
            },
            modifier = modifier
        )
    }
}

@Composable
private fun StackList(
    store: StacksScreenStore,
    selectedName: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var stateFilter by remember { mutableStateOf<String?>(null) }
    val availableStates = remember(store.stacks) {
        store.stacks
            .map { it.status.normalizedDockhandState }
            .distinct()
            .sortedWith(compareBy({ state: String -> state.dockhandStateRank }, { state: String -> state }))
    }
    val filtered = store.stacks.filter { stack ->
        stateFilter == null || stack.status.normalizedDockhandState == stateFilter
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        store.error?.let { message -> StackErrorCard(message) }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = stateFilter == null,
                onClick = { stateFilter = null },
                label = { Text("All states") }
            )
            availableStates.forEach { state ->
                FilterChip(
                    selected = stateFilter == state,
                    onClick = { stateFilter = state },
                    label = { Text(state.localizedDockhandStateLabel) }
                )
            }
        }

        store.actionMessage?.let { message -> StackSecondaryText(message) }

        when {
            store.isLoading && store.stacks.isEmpty() -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            filtered.isEmpty() -> {
                StackSecondaryText(
                    if (stateFilter == null) "No stacks" else "No stacks in $stateFilter"
                )
            }

            else -> {
                filtered.forEach { stack ->
                    StackRow(
                        stack = stack,
                        selected = stack.name == selectedName,
                        onClick = { onSelect(stack.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StackRow(stack: StackSummary, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stack.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stack.localizedStatusText.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (stack.status.normalizedDockhandState == "running") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            StackSecondaryText(stack.servicesCount.localizedServicesCountText)
        }
    }
}

@Composable
private fun StackDetail(
    viewModel: AppViewModel,
    store: StacksScreenStore,
    stack: StackSummary,
    environmentId: Int?,
    isCurrentScope: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    var showDownConfirmation by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showRedeployDialog by remember { mutableStateOf(false) }
    var pullOption by remember { mutableStateOf(true) }
    var buildOption by remember { mutableStateOf(false) }
    var forceRecreateOption by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
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
                    text = stack.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StackSecondaryText(stack.localizedStatusText)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit compose and .env")
            }
        }

        store.document?.composePath?.takeIf { it.isNotEmpty() }?.let { path ->
            SelectionContainer {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!isCurrentScope) {
            StackWarningCard("Server or environment changed. Go back and reopen this stack before saving or running actions.")
        }

        store.error?.let { message -> StackErrorCard(message) }

        StackActionsCard(
            store = store,
            stack = stack,
            isCurrentScope = isCurrentScope,
            onAction = { action ->
                when (action) {
                    StackAction.DOWN -> showDownConfirmation = true
                    StackAction.REDEPLOY -> showRedeployDialog = true
                    else -> coroutineScope.launch {
                        store.runStackAction(action, stack.name, viewModel, environmentId)
                    }
                }
            },
            onDelete = { showDeleteConfirmation = true }
        )

        StackContainersCard(
            viewModel = viewModel,
            store = store,
            stack = stack,
            environmentId = environmentId,
            isCurrentScope = isCurrentScope
        )
    }

    if (showDownConfirmation) {
        AlertDialog(
            onDismissRequest = { showDownConfirmation = false },
            title = { Text("Bring stack down") },
            text = { Text("This stops and removes the containers created by this stack in the selected environment.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDownConfirmation = false
                        coroutineScope.launch {
                            store.runStackAction(StackAction.DOWN, stack.name, viewModel, environmentId)
                        }
                    }
                ) {
                    Text("Down stack")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete stack") },
            text = { Text("This removes the stack from the selected Dockhand environment. Deleting volumes also removes attached persistent data.") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showDeleteConfirmation = false
                            coroutineScope.launch {
                                val deleted = store.deleteStack(stack, viewModel, environmentId, deleteVolumes = true)
                                if (deleted) onBack()
                            }
                        }
                    ) {
                        Text("Delete stack and volumes")
                    }
                    TextButton(
                        onClick = {
                            showDeleteConfirmation = false
                            coroutineScope.launch {
                                val deleted = store.deleteStack(stack, viewModel, environmentId, deleteVolumes = false)
                                if (deleted) onBack()
                            }
                        }
                    ) {
                        Text("Delete stack")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRedeployDialog) {
        StackRedeployDialog(
            store = store,
            stack = stack,
            pullOption = pullOption,
            buildOption = buildOption,
            forceRecreateOption = forceRecreateOption,
            onPullOptionChange = { pullOption = it },
            onBuildOptionChange = { buildOption = it },
            onForceRecreateOptionChange = { forceRecreateOption = it },
            onDeploy = {
                coroutineScope.launch {
                    store.redeploy(
                        stack,
                        viewModel,
                        environmentId,
                        StackDeployOptions(
                            pull = pullOption,
                            build = buildOption,
                            forceRecreate = forceRecreateOption
                        )
                    )
                }
            },
            onDismiss = { showRedeployDialog = false }
        )
    }
}

@Composable
private fun StackActionsCard(
    store: StacksScreenStore,
    stack: StackSummary,
    isCurrentScope: Boolean,
    onAction: (StackAction) -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Stack actions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                store.actionMessageFor(stack.name)?.let { message -> StackSecondaryText(message) }
            }
            val busy = store.hasPendingAction || !isCurrentScope
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StackActionButton(
                    title = "Start",
                    leadingIcon = true,
                    running = store.isRunning(StackAction.START, stack.name),
                    enabled = stack.canPerform(StackAction.START) && !busy,
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(StackAction.START) }
                )
                StackActionButton(
                    title = "Stop",
                    running = store.isRunning(StackAction.STOP, stack.name),
                    enabled = stack.canPerform(StackAction.STOP) && !busy,
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(StackAction.STOP) }
                )
                StackActionButton(
                    title = "Restart",
                    running = store.isRunning(StackAction.RESTART, stack.name),
                    enabled = stack.canPerform(StackAction.RESTART) && !busy,
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(StackAction.RESTART) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StackActionButton(
                    title = "Down",
                    running = store.isRunning(StackAction.DOWN, stack.name),
                    enabled = stack.canPerform(StackAction.DOWN) && !busy,
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(StackAction.DOWN) }
                )
                if (stack.supportsRedeploy) {
                    StackActionButton(
                        title = "Redeploy",
                        running = store.isRunning(StackAction.REDEPLOY, stack.name),
                        enabled = stack.canPerform(StackAction.REDEPLOY) && !busy,
                        prominent = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(StackAction.REDEPLOY) }
                    )
                }
                StackActionButton(
                    title = "Delete",
                    running = store.isDeletingStack(stack.name),
                    enabled = !busy,
                    destructive = true,
                    modifier = Modifier.weight(1f),
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun StackActionButton(
    title: String,
    running: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    leadingIcon: Boolean = false,
    prominent: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val content: @Composable () -> Unit = {
        if (running) {
            CircularProgressIndicator(Modifier.size(16.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (leadingIcon) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (prominent) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, content = { content() })
    } else if (destructive) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            content()
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            content()
        }
    }
}

@Composable
private fun StackContainersCard(
    viewModel: AppViewModel,
    store: StacksScreenStore,
    stack: StackSummary,
    environmentId: Int?,
    isCurrentScope: Boolean
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Containers",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                StackSecondaryText(stack.servicesCount.localizedServicesCountText)
            }
            if (stack.containerDetails.isEmpty()) {
                StackSecondaryText("No containers in this stack")
            } else {
                stack.containerDetails.forEach { container ->
                    StackContainerCard(
                        viewModel = viewModel,
                        store = store,
                        container = container,
                        stackName = stack.name,
                        environmentId = environmentId,
                        isCurrentScope = isCurrentScope
                    )
                }
            }
        }
    }
}

@Composable
private fun StackContainerCard(
    viewModel: AppViewModel,
    store: StacksScreenStore,
    container: StackContainerDetail,
    stackName: String,
    environmentId: Int?,
    isCurrentScope: Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(container.name, style = MaterialTheme.typography.titleSmall)
                StackSecondaryText(container.service)
            }
            Text(
                text = container.state.localizedDockhandStateLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (container.state.normalizedDockhandState == "running") {
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
        if (container.status.isNotEmpty()) {
            StackSecondaryText(container.status)
        }
        val busy = store.hasPendingAction || !isCurrentScope
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ContainerAction.entries.take(3).forEach { action ->
                ContainerActionButton(
                    action = action,
                    running = store.isRunning(action, container.id),
                    enabled = container.canPerform(action) && !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        coroutineScope.launch {
                            store.runContainerAction(action, container, stackName, viewModel, environmentId)
                        }
                    }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ContainerAction.entries.drop(3).forEach { action ->
                ContainerActionButton(
                    action = action,
                    running = store.isRunning(action, container.id),
                    enabled = container.canPerform(action) && !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        coroutineScope.launch {
                            store.runContainerAction(action, container, stackName, viewModel, environmentId)
                        }
                    }
                )
            }
            Spacer(Modifier.weight(1f))
        }
        HorizontalDivider()
    }
}

@Composable
private fun ContainerActionButton(
    action: ContainerAction,
    running: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        if (running) {
            CircularProgressIndicator(Modifier.size(14.dp))
        } else {
            Text(action.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StackEditor(
    store: StacksScreenStore,
    stackName: String,
    isCurrentScope: Boolean,
    onBack: () -> Unit,
    onSave: (pane: Int, compose: String, env: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var composeText by remember { mutableStateOf("") }
    var envText by remember { mutableStateOf("") }
    var activePane by remember { mutableIntStateOf(0) }

    LaunchedEffect(store.document) {
        val loadedDocument = store.document ?: return@LaunchedEffect
        composeText = loadedDocument.composeContent
        envText = loadedDocument.envContent
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = stackName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { onSave(activePane, composeText, envText) },
                enabled = isCurrentScope && !store.isSaving && !store.isLoadingDocument
            ) {
                if (store.isSaving) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                } else {
                    Text("Save")
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = activePane == 0,
                onClick = { activePane = 0 },
                label = { Text("Compose") }
            )
            FilterChip(
                selected = activePane == 1,
                onClick = { activePane = 1 },
                label = { Text(".env") }
            )
        }

        if (store.document?.needsFileLocation == true) {
            Text(
                text = store.document?.composeError ?: "Dockhand needs the compose file location for this stack.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        store.saveMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (store.saveMessageIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        if (store.isLoadingDocument) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(Modifier.size(18.dp))
                StackSecondaryText("Loading stack files…")
            }
        }

        val editorTextStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        val verticalScrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()
        val highlightColors = rememberCodeHighlightColors()
        val highlightCache = remember(activePane) {
            CodeHighlightCache(
                if (activePane == 0) YamlLineHighlighter::spans else EnvLineHighlighter::spans
            )
        }
        var highlightVersion by remember(activePane) { mutableIntStateOf(0) }
        val visibleText = if (activePane == 0) composeText else envText
        LaunchedEffect(visibleText, activePane) {
            val snapshot = visibleText
            delay(HIGHLIGHT_DEBOUNCE_MS)
            withContext(Dispatchers.Default) { highlightCache.update(snapshot) }
            highlightVersion++
        }
        val highlightTransformation = remember(highlightVersion, highlightColors, activePane) {
            codeHighlightTransformation(highlightColors, highlightCache)
        }
        val lineCount = visibleText.count { it == '\n' } + 1
        val gutterWidth = ((lineCount.toString().length * 8) + 20).dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
            ) {
                LineNumberGutter(
                    lineCount = lineCount,
                    textStyle = editorTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .width(gutterWidth)
                        .padding(start = 4.dp, end = 8.dp)
                )
                BasicTextField(
                    value = visibleText,
                    onValueChange = { updated ->
                        if (activePane == 0) composeText = updated else envText = updated
                    },
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScrollState)
                        .padding(start = 4.dp, end = 8.dp),
                    textStyle = editorTextStyle,
                    visualTransformation = highlightTransformation
                )
            }
        }
    }
}

private const val HIGHLIGHT_DEBOUNCE_MS = 80L

@Composable
private fun StackRedeployDialog(
    store: StacksScreenStore,
    stack: StackSummary,
    pullOption: Boolean,
    buildOption: Boolean,
    forceRecreateOption: Boolean,
    onPullOptionChange: (Boolean) -> Unit,
    onBuildOptionChange: (Boolean) -> Unit,
    onForceRecreateOptionChange: (Boolean) -> Unit,
    onDeploy: () -> Unit,
    onDismiss: () -> Unit
) {
    val showProgress = store.isRedeploying || store.redeployStatus != StackRedeployStatus.IDLE
    AlertDialog(
        onDismissRequest = { if (!store.isRedeploying) onDismiss() },
        title = { Text("Redeploy") },
        text = {
            if (!showProgress) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Choose how Dockhand should redeploy this compose stack.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ToggleRow("Pull images", pullOption, onPullOptionChange)
                    ToggleRow("Build images", buildOption, onBuildOptionChange)
                    ToggleRow("Force recreate", forceRecreateOption, onForceRecreateOptionChange)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (store.redeployStatus) {
                            StackRedeployStatus.COMPLETE -> Icon(Icons.Default.CheckCircle, contentDescription = null)
                            StackRedeployStatus.FAILED, StackRedeployStatus.CANCELLED ->
                                Icon(Icons.Default.Warning, contentDescription = null)
                            else -> CircularProgressIndicator(Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(store.redeployStatus.title, style = MaterialTheme.typography.titleSmall)
                            StackSecondaryText(stack.name)
                        }
                    }
                    if (store.isRedeploying) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    store.redeploySteps.forEach { step ->
                        StackSecondaryText(step)
                    }
                    store.redeployError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (store.redeployOutput.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = store.redeployOutput.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (showProgress) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (
                        store.redeployStatus == StackRedeployStatus.FAILED ||
                        store.redeployStatus == StackRedeployStatus.CANCELLED
                    ) {
                        Button(onClick = onDeploy, enabled = !store.isRedeploying) {
                            Text("Retry")
                        }
                    }
                    TextButton(onClick = onDismiss, enabled = !store.isRedeploying) {
                        Text("Close")
                    }
                }
            } else {
                Button(onClick = onDeploy, enabled = !store.isRedeploying) {
                    Text("Deploy")
                }
            }
        },
        dismissButton = {
            if (!showProgress) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private val StackRedeployStatus.title: String
    get() = when (this) {
        StackRedeployStatus.IDLE -> "Redeploy"
        StackRedeployStatus.DEPLOYING -> "Pulling images and redeploying…"
        StackRedeployStatus.COMPLETE -> "Redeploy completed"
        StackRedeployStatus.FAILED -> "Redeploy failed"
        StackRedeployStatus.CANCELLED -> "Monitoring cancelled"
    }

@Composable
private fun StackEmptyState(title: String, message: String, modifier: Modifier = Modifier) {
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
private fun StackErrorCard(message: String) {
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
private fun StackWarningCard(message: String) {
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
private fun StackSecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
