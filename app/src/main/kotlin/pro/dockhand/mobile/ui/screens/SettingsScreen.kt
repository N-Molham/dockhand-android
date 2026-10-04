package pro.dockhand.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.launch
import pro.dockhand.mobile.api.DockhandServerAddress
import pro.dockhand.mobile.app.AppUiState
import pro.dockhand.mobile.app.AppViewModel
import pro.dockhand.mobile.data.ServerProfile

private sealed interface ProfileEditorTarget {
    data object Create : ProfileEditorTarget
    data class Edit(val profileId: String) : ProfileEditorTarget
}

internal data class HeaderDraft(val name: String = "", val value: String = "")

private val HEADER_TOKEN_SPECIALS = "!#$%&'*+-.^_`|~"

private val RESERVED_HEADER_NAMES = setOf(
    "authorization",
    "accept",
    "content-type",
    "content-length",
    "host",
    "connection",
    "upgrade"
)

internal fun headerNameError(rawName: String): String? {
    val name = rawName.trim()
    if (name.isEmpty()) return "Header name is required."
    val validCharacters = name.all { character ->
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character in HEADER_TOKEN_SPECIALS
    }
    if (!validCharacters) return "Use only letters, digits or !#$%&'*+-.^_`|~ characters."
    if (name.lowercase(Locale.ROOT) in RESERVED_HEADER_NAMES) {
        return "This header is managed by the app and cannot be overridden."
    }
    return null
}

internal fun headerValueError(rawValue: String): String? {
    if (rawValue.contains('\r') || rawValue.contains('\n')) return "Line breaks are not allowed."
    return null
}

private fun isCleartextAddress(raw: String): Boolean =
    raw.trim().startsWith("http://", ignoreCase = true)

@Composable
fun SettingsScreen(viewModel: AppViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    var editorTarget by remember { mutableStateOf<ProfileEditorTarget?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val target = editorTarget
    if (target != null) {
        BackHandler(onBack = { editorTarget = null })
        ProfileEditor(
            viewModel = viewModel,
            state = state,
            target = target,
            modifier = modifier,
            onBack = { editorTarget = null },
            onFinished = { message ->
                statusMessage = message
                editorTarget = null
            }
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close settings")
            }
        }

        Text(
            text = "Dockhand Android v${pro.dockhand.mobile.BuildConfig.VERSION_NAME} (${pro.dockhand.mobile.BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ActiveServerCard(
            state = state,
            onRefresh = {
                viewModel.refreshEnvironmentsInScope()
                statusMessage = "Active server refreshed"
            },
            onSelectProfile = { profileId ->
                viewModel.selectServerProfileInScope(profileId, forceEnvironmentReset = true)
                statusMessage = "Server changed"
            },
            onAddServer = { editorTarget = ProfileEditorTarget.Create }
        )

        if (state.serverProfiles.isNotEmpty()) {
            ServerLibraryCard(
                state = state,
                onSelectProfile = { profileId ->
                    viewModel.selectServerProfileInScope(profileId, forceEnvironmentReset = true)
                    statusMessage = "Server changed"
                },
                onAddServer = { editorTarget = ProfileEditorTarget.Create },
                onEditProfile = { profileId -> editorTarget = ProfileEditorTarget.Edit(profileId) },
                onDeleteProfile = { profileId ->
                    viewModel.deleteServerProfileInScope(profileId)
                    statusMessage = "Server deleted"
                }
            )
        }

        state.environmentError?.let { error ->
            StatusCard(message = error, isError = true)
        }
        statusMessage?.let { message ->
            StatusCard(message = message, isError = false)
        }
    }
}

@Composable
private fun ActiveServerCard(
    state: AppUiState,
    onRefresh: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onAddServer: () -> Unit
) {
    val profile = state.serverProfiles.firstOrNull { it.id == state.selectedProfileId }
        ?: state.serverProfiles.firstOrNull()
    val environment = state.environments.firstOrNull { it.id == state.selectedEnvironmentId }
        ?: state.environments.firstOrNull()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("Active server", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Quick context and switching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when {
                    state.isLoadingEnvironments -> CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                    state.lastHealthStatus != null -> StatusBadge(
                        label = "STATUS",
                        value = state.lastHealthStatus.uppercase(Locale.ROOT)
                    )
                }
            }

            if (profile == null) {
                Text("No server configured", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Add a Dockhand server to start switching environments.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onAddServer, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add server")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        profile.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Environment: ${environment?.name ?: "None selected"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (profile.allowCleartext) {
                        Text(
                            "Cleartext HTTP is allowed for this server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ServerSwitcherMenu(
                        profiles = state.serverProfiles,
                        selectedProfileId = state.selectedProfileId,
                        onSelectProfile = onSelectProfile
                    )
                    OutlinedButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refresh")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun ServerSwitcherMenu(
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Switch server")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = if (profile.id == selectedProfileId) {
                        { Icon(Icons.Default.CheckCircle, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelectProfile(profile.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun ServerLibraryCard(
    state: AppUiState,
    onSelectProfile: (String) -> Unit,
    onAddServer: () -> Unit,
    onEditProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    val environmentName = (state.environments.firstOrNull { it.id == state.selectedEnvironmentId }
        ?: state.environments.firstOrNull())?.name

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("Server library", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Each server keeps its own URL, token and environment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onAddServer) {
                    Icon(Icons.Default.Add, contentDescription = "Add server")
                }
            }
            state.serverProfiles.forEach { profile ->
                val isActive = profile.id == state.selectedProfileId
                ServerProfileRow(
                    profile = profile,
                    isActive = isActive,
                    environmentName = if (isActive) environmentName else null,
                    canDelete = state.serverProfiles.size > 1 || !isActive,
                    onSelect = { if (!isActive) onSelectProfile(profile.id) },
                    onEdit = { onEditProfile(profile.id) },
                    onDelete = { onDeleteProfile(profile.id) }
                )
            }
        }
    }
}

@Composable
private fun ServerProfileRow(
    profile: ServerProfile,
    isActive: Boolean,
    environmentName: String?,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isActive) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Active server",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    profile.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isActive && environmentName != null) {
                    Text(
                        "Environment: $environmentName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit ${profile.name}")
            }
            IconButton(onClick = { showDeleteConfirmation = true }, enabled = canDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${profile.name}")
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete server?") },
            text = { Text("This removes the saved URL, token and per-server environment selection.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ProfileEditor(
    viewModel: AppViewModel,
    state: AppUiState,
    target: ProfileEditorTarget,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onFinished: (String) -> Unit
) {
    val profileId = (target as? ProfileEditorTarget.Edit)?.profileId
    val profile = profileId?.let { id -> state.serverProfiles.firstOrNull { it.id == id } }
    val isActive = profileId != null && state.selectedProfileId == profileId
    val environment = state.environments.firstOrNull { it.id == state.selectedEnvironmentId }
        ?: state.environments.firstOrNull()

    var name by remember(target) { mutableStateOf(profile?.name.orEmpty()) }
    var baseUrl by remember(target) { mutableStateOf(profile?.baseUrl ?: "http://") }
    var token by remember(target) { mutableStateOf("") }
    var tokenRemovalRequested by remember(target) { mutableStateOf(false) }
    var allowCleartext by remember(target) { mutableStateOf(profile?.allowCleartext ?: false) }
    var headerDrafts by remember(target) { mutableStateOf<List<HeaderDraft>>(emptyList()) }
    var headersLoaded by remember(target) { mutableStateOf(profileId == null) }
    var isSaving by remember(target) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(target) {
        if (profileId != null) {
            val stored = viewModel.customHeaders(profileId)
            headerDrafts = stored.entries.map { HeaderDraft(name = it.key, value = it.value) }
            headersLoaded = true
        }
    }

    val normalizedUrl = DockhandServerAddress.normalized(baseUrl)
    val cleartextPossible = isCleartextAddress(baseUrl)
    val urlError = if (baseUrl.isNotBlank() && normalizedUrl == null) {
        "Enter a valid http:// or https:// server address."
    } else {
        null
    }
    val populatedDrafts = headerDrafts.filter { it.name.isNotBlank() || it.value.isNotBlank() }
    val headersValid = populatedDrafts.all {
        headerNameError(it.name) == null && headerValueError(it.value) == null
    }
    val canSave = normalizedUrl != null && headersValid && headersLoaded && !isSaving

    fun saveProfile() {
        val normalized = normalizedUrl ?: return
        val headers = populatedDrafts.associate { it.name.trim() to it.value.trim() }
        isSaving = true
        scope.launch {
            viewModel.saveServerProfile(
                profileId = profileId,
                name = name,
                baseUrlText = normalized,
                tokenValue = when {
                    token.isNotBlank() -> token
                    tokenRemovalRequested -> ""
                    else -> null
                },
                allowCleartext = cleartextPossible && allowCleartext,
                makeActive = true
            )
            if (profileId != null) {
                viewModel.setCleartextAllowed(profileId, cleartextPossible && allowCleartext)
                viewModel.saveCustomHeaders(profileId, headers)
            }
            isSaving = false
            onFinished("Server saved")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (profile == null) "New Server" else "Server Details",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
        }

        if (profileId != null) {
            Text(
                text = if (isActive) {
                    "Active server with environment: ${environment?.name ?: "None selected"}"
                } else {
                    "This is not the active server."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connection", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    isError = urlError != null,
                    supportingText = if (urlError != null) {
                        { Text(urlError) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        tokenRemovalRequested = false
                    },
                    label = { Text("Bearer token (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Text(
                            if (profileId == null) {
                                "Optional. Leave empty for servers without authentication."
                            } else if (tokenRemovalRequested) {
                                "The stored token will be removed when you save."
                            } else {
                                "Leave blank to keep the stored token unchanged."
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (profileId != null && !tokenRemovalRequested) {
                    TextButton(onClick = {
                        token = ""
                        tokenRemovalRequested = true
                    }) {
                        Text("Remove stored token")
                    }
                }
                if (cleartextPossible) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("Allow cleartext HTTP", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Only enable this for a trusted self-hosted server. Traffic, including the token, is sent unencrypted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = allowCleartext, onCheckedChange = { allowCleartext = it })
                    }
                }
            }
        }

        if (profileId != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Custom headers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Sent with every request to this server. Reserved headers are managed by the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!headersLoaded) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    headerDrafts.forEachIndexed { index, draft ->
                        HeaderEditorRow(
                            draft = draft,
                            nameError = if (draft.name.isNotBlank() || draft.value.isNotBlank()) {
                                headerNameError(draft.name)
                            } else {
                                null
                            },
                            valueError = headerValueError(draft.value),
                            onNameChange = { newName ->
                                headerDrafts = headerDrafts.mapIndexed { itemIndex, item ->
                                    if (itemIndex == index) item.copy(name = newName) else item
                                }
                            },
                            onValueChange = { newValue ->
                                headerDrafts = headerDrafts.mapIndexed { itemIndex, item ->
                                    if (itemIndex == index) item.copy(value = newValue) else item
                                }
                            },
                            onRemove = {
                                headerDrafts = headerDrafts.filterIndexed { itemIndex, _ ->
                                    itemIndex != index
                                }
                            }
                        )
                    }
                    TextButton(onClick = { headerDrafts = headerDrafts + HeaderDraft() }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add header")
                    }
                }
            }
        }

        if (profileId != null && !isActive) {
            OutlinedButton(
                onClick = {
                    viewModel.selectServerProfileInScope(profileId, forceEnvironmentReset = true)
                    onFinished("Server changed")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Use this server")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
            Button(
                onClick = { saveProfile() },
                enabled = canSave,
                modifier = Modifier.weight(1f)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (profile == null) "Create" else "Save")
                }
            }
        }

        if (profileId != null) {
            OutlinedButton(
                onClick = { showDeleteConfirmation = true },
                enabled = !(state.serverProfiles.size <= 1 && isActive),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete server")
            }
        }
    }

    if (showDeleteConfirmation && profileId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete server?") },
            text = { Text("This removes the saved URL, token and per-server environment selection.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteServerProfileInScope(profileId)
                        onFinished("Server deleted")
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HeaderEditorRow(
    draft: HeaderDraft,
    nameError: String?,
    valueError: String?,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = onNameChange,
            label = { Text("Header name") },
            singleLine = true,
            isError = nameError != null,
            supportingText = if (nameError != null) {
                { Text(nameError) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft.value,
                onValueChange = onValueChange,
                label = { Text("Value") },
                singleLine = true,
                isError = valueError != null,
                supportingText = if (valueError != null) {
                    { Text(valueError) }
                } else {
                    null
                },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove header")
            }
        }
    }
}

@Composable
private fun StatusCard(message: String, isError: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(16.dp)
        )
    }
}
