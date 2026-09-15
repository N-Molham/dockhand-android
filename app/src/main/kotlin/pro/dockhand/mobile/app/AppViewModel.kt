package pro.dockhand.mobile.app

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.dockhand.mobile.api.DockhandApi
import pro.dockhand.mobile.api.DockhandConnectionStage
import pro.dockhand.mobile.api.DockhandConnectionStageException
import pro.dockhand.mobile.api.DockhandServerAddress
import pro.dockhand.mobile.api.DockhandService
import pro.dockhand.mobile.api.Environment
import pro.dockhand.mobile.api.dockhandUserFacingMessage
import pro.dockhand.mobile.api.isDockhandCancellation
import pro.dockhand.mobile.data.PreferencesRepository
import pro.dockhand.mobile.data.SecureStore
import pro.dockhand.mobile.data.ServerProfile

data class DockhandConnectionScope(
    val profileId: String?,
    val environmentId: Int?
)

data class AppUiState(
    val serverProfiles: List<ServerProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val environments: List<Environment> = emptyList(),
    val selectedEnvironmentId: Int? = null,
    val isLoadingEnvironments: Boolean = false,
    val environmentError: String? = null,
    val lastHealthStatus: String? = null,
    val dashboardRefreshRevision: Int = 0
)

class AppViewModel(
    private val preferences: PreferencesRepository,
    private val secureStore: SecureStore,
    private val scope: CoroutineScope,
    private val serviceFactory: (DockhandServiceConfig) -> DockhandApi = { config ->
        DockhandService(
            baseUrl = config.baseUrl,
            token = config.token,
            allowCleartext = config.allowCleartext,
            customHeaders = config.customHeaders
        )
    }
) {

    data class DockhandServiceConfig(
        val baseUrl: String,
        val token: String,
        val allowCleartext: Boolean,
        val customHeaders: Map<String, String>
    )

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var token: String = ""
    private var customHeaders: Map<String, String> = emptyMap()

    init {
        scope.launch { loadPersistedState() }
    }

    val selectedProfile: ServerProfile?
        get() = _state.value.serverProfiles
            .firstOrNull { it.id == _state.value.selectedProfileId }
            ?: _state.value.serverProfiles.firstOrNull()

    val selectedEnvironment: Environment?
        get() = _state.value.environments
            .firstOrNull { it.id == _state.value.selectedEnvironmentId }
            ?: _state.value.environments.firstOrNull()

    val connectionScope: DockhandConnectionScope
        get() = DockhandConnectionScope(_state.value.selectedProfileId, _state.value.selectedEnvironmentId)

    fun isCurrentScope(scope: DockhandConnectionScope): Boolean = connectionScope == scope

    fun service(): DockhandApi? {
        val profile = selectedProfile ?: return null
        val normalized = DockhandServerAddress.normalized(profile.baseUrl) ?: return null
        return serviceFactory(
            DockhandServiceConfig(
                baseUrl = normalized,
                token = token,
                allowCleartext = profile.allowCleartext,
                customHeaders = customHeaders
            )
        )
    }

    fun bootstrap() {
        scope.launch { refreshEnvironments(forceEnvironmentReset = false) }
    }

    suspend fun saveServerProfile(
        profileId: String?,
        name: String,
        baseUrlText: String,
        tokenValue: String,
        allowCleartext: Boolean = false,
        makeActive: Boolean = true
    ) {
        val cleanedName = name.trim()
        val cleanedUrl = baseUrlText.trim()
        val resolvedName = cleanedName.ifEmpty { cleanedUrl }
        val targetId = profileId ?: UUID.randomUUID().toString()
        val profile = ServerProfile(
            id = targetId,
            name = resolvedName,
            baseUrl = cleanedUrl,
            allowCleartext = allowCleartext
        )

        val profiles = _state.value.serverProfiles.toMutableList()
        val existingIndex = profiles.indexOfFirst { it.id == targetId }
        if (existingIndex >= 0) {
            profiles[existingIndex] = profiles[existingIndex].copy(name = resolvedName, baseUrl = cleanedUrl)
        } else {
            profiles.add(profile)
        }
        val sorted = profiles.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        _state.update { it.copy(serverProfiles = sorted) }
        preferences.saveProfiles(sorted)
        secureStore.writeToken(targetId, tokenValue.trim())

        if (makeActive || _state.value.selectedProfileId == null) {
            selectServerProfile(targetId, forceEnvironmentReset = true)
        }
    }

    suspend fun setCleartextAllowed(profileId: String, allowed: Boolean) {
        val profiles = _state.value.serverProfiles.map { profile ->
            if (profile.id == profileId) profile.copy(allowCleartext = allowed) else profile
        }
        _state.update { it.copy(serverProfiles = profiles) }
        preferences.saveProfiles(profiles)
    }

    suspend fun deleteServerProfile(profileId: String) {
        val profiles = _state.value.serverProfiles.filterNot { it.id == profileId }
        _state.update { it.copy(serverProfiles = profiles) }
        preferences.saveProfiles(profiles)
        preferences.removeSelectedEnvironmentId(profileId)
        preferences.removeDashboardSnapshots(profileId)
        secureStore.deleteProfileSecrets(profileId)

        if (_state.value.selectedProfileId == profileId) {
            val nextId = profiles.firstOrNull()?.id
            _state.update {
                it.copy(
                    selectedProfileId = nextId,
                    environments = emptyList(),
                    lastHealthStatus = null,
                    environmentError = null
                )
            }
            preferences.setSelectedProfileId(nextId)
            token = nextId?.let { secureStore.readToken(it) }.orEmpty()
            customHeaders = nextId?.let { secureStore.readCustomHeaders(it) }.orEmpty()
            val storedEnvironmentId = nextId?.let { preferences.loadSelectedEnvironmentId(it) }
            _state.update { it.copy(selectedEnvironmentId = storedEnvironmentId) }
            refreshEnvironments(forceEnvironmentReset = true)
        }
    }

    suspend fun selectServerProfile(profileId: String, forceEnvironmentReset: Boolean = false) {
        if (_state.value.selectedProfileId == profileId && !forceEnvironmentReset) return

        _state.update {
            it.copy(
                selectedProfileId = profileId,
                environments = emptyList(),
                environmentError = null,
                lastHealthStatus = null
            )
        }
        preferences.setSelectedProfileId(profileId)
        token = secureStore.readToken(profileId)
        customHeaders = secureStore.readCustomHeaders(profileId)
        val storedEnvironmentId = preferences.loadSelectedEnvironmentId(profileId)
        _state.update { it.copy(selectedEnvironmentId = storedEnvironmentId) }
        refreshEnvironments(forceEnvironmentReset = true)
    }

    suspend fun refreshEnvironments(forceEnvironmentReset: Boolean = false) {
        val profile = selectedProfile
        if (profile == null) {
            _state.update {
                it.copy(environments = emptyList(), environmentError = null, lastHealthStatus = null)
            }
            return
        }

        val normalized = DockhandServerAddress.normalized(profile.baseUrl)
        if (normalized == null) {
            _state.update {
                it.copy(
                    environmentError = "Invalid Dockhand URL",
                    environments = emptyList(),
                    lastHealthStatus = null
                )
            }
            return
        }

        _state.update { it.copy(isLoadingEnvironments = true, environmentError = null) }

        try {
            val service = service() ?: run {
                _state.update { it.copy(isLoadingEnvironments = false, environmentError = "Invalid Dockhand URL") }
                return
            }

            val health = try {
                service.fetchHealthStatus()
            } catch (error: Throwable) {
                throw DockhandConnectionStageException(DockhandConnectionStage.HEALTH, error)
            }

            val loaded = try {
                service.fetchEnvironments()
            } catch (error: Throwable) {
                throw DockhandConnectionStageException(DockhandConnectionStage.ENVIRONMENTS, error)
            }

            val sorted = loaded.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            val preferredEnvironmentId = if (forceEnvironmentReset) {
                preferences.loadSelectedEnvironmentId(profile.id) ?: _state.value.selectedEnvironmentId
            } else {
                _state.value.selectedEnvironmentId
            }
            val resolvedEnvironmentId = if (preferredEnvironmentId != null && sorted.any { it.id == preferredEnvironmentId }) {
                preferredEnvironmentId
            } else {
                sorted.firstOrNull()?.id
            }

            _state.update {
                it.copy(
                    environments = sorted,
                    selectedEnvironmentId = resolvedEnvironmentId,
                    lastHealthStatus = health,
                    isLoadingEnvironments = false
                )
            }
            preferences.setSelectedEnvironmentId(profile.id, resolvedEnvironmentId)
        } catch (error: Throwable) {
            if (error.isDockhandCancellation) {
                _state.update { it.copy(isLoadingEnvironments = false) }
                return
            }
            _state.update {
                it.copy(
                    environmentError = error.dockhandUserFacingMessage,
                    environments = emptyList(),
                    isLoadingEnvironments = false
                )
            }
        }
    }

    suspend fun selectEnvironment(environmentId: Int) {
        if (_state.value.selectedEnvironmentId == environmentId) return
        _state.update { it.copy(selectedEnvironmentId = environmentId) }
        selectedProfile?.id?.let { profileId ->
            preferences.setSelectedEnvironmentId(profileId, environmentId)
        }
    }

    fun requestDashboardRefresh() {
        _state.update { it.copy(dashboardRefreshRevision = it.dashboardRefreshRevision + 1) }
    }

    private suspend fun loadPersistedState() {
        val profiles = preferences.loadProfiles()
        val storedProfileId = preferences.loadSelectedProfileId()
        val resolvedProfileId = storedProfileId
            ?.takeIf { storedId -> profiles.any { it.id == storedId } }
            ?: profiles.firstOrNull()?.id

        if (storedProfileId != resolvedProfileId) {
            preferences.setSelectedProfileId(resolvedProfileId)
        }

        token = resolvedProfileId?.let { secureStore.readToken(it) }.orEmpty()
        customHeaders = resolvedProfileId?.let { secureStore.readCustomHeaders(it) }.orEmpty()
        val storedEnvironmentId = resolvedProfileId?.let { preferences.loadSelectedEnvironmentId(it) }

        _state.update {
            it.copy(
                serverProfiles = profiles,
                selectedProfileId = resolvedProfileId,
                selectedEnvironmentId = storedEnvironmentId
            )
        }
    }
}
