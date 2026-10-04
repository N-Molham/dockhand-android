package pro.dockhand.mobile.app

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.dockhand.mobile.api.DockhandService
import pro.dockhand.mobile.data.InMemorySecureStore
import pro.dockhand.mobile.data.PreferencesRepository
import pro.dockhand.mobile.data.ServerProfile

class AppViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var preferences: InMemoryPreferencesRepository
    private lateinit var secureStore: InMemorySecureStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        preferences = InMemoryPreferencesRepository()
        secureStore = InMemorySecureStore()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun createViewModel(scope: CoroutineScope): AppViewModel =
        AppViewModel(preferences, secureStore, scope) { config ->
            DockhandService(
                baseUrl = config.baseUrl,
                token = config.token,
                allowCleartext = config.allowCleartext,
                customHeaders = config.customHeaders
            )
        }

    private fun enqueueEnvironmentResponse() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"ok","timestamp":"2026-07-05T00:00:00Z"}""")
                .build()
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    [
                      {"id":2,"name":"Prod","port":2375,"protocol":"tcp","icon":"server","collectActivity":false,"collectMetrics":false,"highlightChanges":false,"labels":[],"connectionType":"socket","socketPath":"/var/run/docker.sock","createdAt":"2026-07-05T00:00:00Z"},
                      {"id":1,"name":"Lab","port":2375,"protocol":"tcp","icon":"server","collectActivity":false,"collectMetrics":false,"highlightChanges":false,"labels":[],"connectionType":"socket","socketPath":"/var/run/docker.sock","createdAt":"2026-07-05T00:00:00Z"}
                    ]
                    """.trimIndent()
                )
                .build()
        )
    }

    private suspend fun seedProfile(
        id: String = "profile-1",
        name: String = "Test",
        baseUrl: String = server.url("/").toString()
    ): ServerProfile {
        val profile = ServerProfile(id = id, name = name, baseUrl = baseUrl, allowCleartext = true)
        preferences.saveProfiles(listOf(profile))
        preferences.setSelectedProfileId(id)
        return profile
    }

    @Test
    fun bootstrapLoadsEnvironmentsAndHealth() = runTest {
        seedProfile()
        enqueueEnvironmentResponse()
        val viewModel = createViewModel(this)

        waitUntilCondition { viewModel.state.value.environments.size == 2 }

        val state = viewModel.state.value
        assertEquals("ok", state.lastHealthStatus)
        assertEquals(listOf(1, 2), state.environments.map { it.id })
        assertEquals(1, state.selectedEnvironmentId)
        assertEquals(1, preferences.storedEnvironmentId("profile-1"))
    }

    @Test
    fun repairsMissingSelectedProfile() = runTest {
        seedProfile()
        preferences.setSelectedProfileId("removed-profile")
        val viewModel = createViewModel(this)
        waitUntilCondition { viewModel.state.value.selectedProfileId == "profile-1" }

        assertEquals("profile-1", preferences.loadSelectedProfileId())
    }

    @Test
    fun saveServerProfileStoresTokenAndSelects() = runTest {
        enqueueEnvironmentResponse()
        val viewModel = createViewModel(this)

        viewModel.saveServerProfile(
            profileId = null,
            name = "  ",
            baseUrlText = server.url("/").toString(),
            tokenValue = "  dh_secret\n",
            allowCleartext = true
        )
        waitUntilCondition { viewModel.state.value.environments.isNotEmpty() }

        val profile = viewModel.state.value.serverProfiles.first()
        assertEquals(server.url("/").toString(), profile.name)
        assertEquals("dh_secret", secureStore.readToken(profile.id))
        assertEquals(profile.id, viewModel.state.value.selectedProfileId)
    }

    @Test
    fun deleteServerProfileClearsSecretsAndSwitches() = runTest {
        seedProfile(id = "a", name = "A")
        preferences.saveProfiles(
            listOf(
                ServerProfile(id = "a", name = "A", baseUrl = server.url("/").toString()),
                ServerProfile(id = "b", name = "B", baseUrl = server.url("/").toString())
            )
        )
        preferences.setSelectedProfileId("a")
        secureStore.writeToken("a", "token-a")
        val viewModel = createViewModel(this)
        waitUntilCondition { viewModel.state.value.selectedProfileId == "a" }

        enqueueEnvironmentResponse()
        viewModel.deleteServerProfile("a")
        waitUntilCondition { viewModel.state.value.selectedProfileId == "b" }

        assertEquals(listOf("b"), viewModel.state.value.serverProfiles.map { it.id })
        assertEquals("", secureStore.readToken("a"))
    }

    @Test
    fun selectEnvironmentPersistsForProfile() = runTest {
        seedProfile()
        enqueueEnvironmentResponse()
        val viewModel = createViewModel(this)
        waitUntilCondition { viewModel.state.value.environments.size == 2 }

        viewModel.selectEnvironment(1)
        assertEquals(1, preferences.storedEnvironmentId("profile-1"))
        assertEquals(1, viewModel.state.value.selectedEnvironmentId)
    }

    @Test
    fun healthFailureSetsUserFacingError() = runTest {
        seedProfile()
        server.enqueue(MockResponse.Builder().code(500).body("""{"error":"boom"}""").build())
        val viewModel = createViewModel(this)

        waitUntilCondition { viewModel.state.value.environmentError != null }

        val error = viewModel.state.value.environmentError.orEmpty()
        assertTrue(error.contains("Dockhand"))
        assertFalse(error.contains("500"))
        assertTrue(viewModel.state.value.environments.isEmpty())
    }

    @Test
    fun invalidServerUrlReportsInvalidAddress() = runTest {
        seedProfile(baseUrl = "example.com:3000")
        val viewModel = createViewModel(this)

        waitUntilCondition { viewModel.state.value.environmentError != null }

        assertEquals("Invalid Dockhand URL", viewModel.state.value.environmentError)
        assertNull(viewModel.service())
    }

    @Test
    fun cleartextConsentPersistsPerProfile() = runTest {
        seedProfile()
        val viewModel = createViewModel(this)
        waitUntilCondition { viewModel.state.value.serverProfiles.isNotEmpty() }

        viewModel.setCleartextAllowed("profile-1", true)
        waitUntilCondition { viewModel.state.value.serverProfiles.first().allowCleartext }

        assertTrue(preferences.loadProfiles().first().allowCleartext)
    }

    @Test
    fun requestDashboardRefreshIncrementsRevision() {
        val viewModel = createViewModel(CoroutineScope(Dispatchers.Unconfined))
        val before = viewModel.state.value.dashboardRefreshRevision
        viewModel.requestDashboardRefresh()
        assertEquals(before + 1, viewModel.state.value.dashboardRefreshRevision)
    }

    @Test
    fun serviceUsesStoredTokenAndHeaders() = runTest {
        seedProfile()
        secureStore.writeToken("profile-1", "stored-token")
        secureStore.writeCustomHeaders("profile-1", mapOf("X-Gateway" to "abc"))
        enqueueEnvironmentResponse()
        val viewModel = createViewModel(this)
        waitUntilCondition { viewModel.state.value.environments.isNotEmpty() }

        assertNotNull(viewModel.service())
        val requests = listOf(server.takeRequest(), server.takeRequest())
        assertEquals("Bearer stored-token", requests.first().headers["Authorization"])
    }

    private suspend fun waitUntilCondition(
        timeoutMillis: Long = 5_000,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            kotlinx.coroutines.delay(25)
        }
        error("Condition not met within ${timeoutMillis}ms")
    }
}

private class InMemoryPreferencesRepository : PreferencesRepository {

    private val profilesFlow = MutableStateFlow<List<ServerProfile>>(emptyList())
    private val selectedProfileFlow = MutableStateFlow<String?>(null)
    private val environmentIds = mutableMapOf<String, Int>()
    private val snapshots = mutableMapOf<String, String>()
    private val shellPreferences = mutableMapOf<String, String>()
    private var customUsers: List<String> = emptyList()

    override val serverProfiles: Flow<List<ServerProfile>> = profilesFlow
    override val selectedProfileId: Flow<String?> = selectedProfileFlow

    override suspend fun loadProfiles(): List<ServerProfile> = profilesFlow.value
    override suspend fun saveProfiles(profiles: List<ServerProfile>) {
        profilesFlow.value = profiles
    }
    override suspend fun loadSelectedProfileId(): String? = selectedProfileFlow.value
    override suspend fun setSelectedProfileId(profileId: String?) {
        selectedProfileFlow.value = profileId
    }
    override suspend fun loadSelectedEnvironmentId(profileId: String): Int? = environmentIds[profileId]
    override suspend fun setSelectedEnvironmentId(profileId: String, environmentId: Int?) {
        if (environmentId == null) environmentIds.remove(profileId) else environmentIds[profileId] = environmentId
    }
    override suspend fun removeSelectedEnvironmentId(profileId: String) {
        environmentIds.remove(profileId)
    }
    override suspend fun loadDashboardSnapshot(profileId: String, environmentId: Int): String? =
        snapshots["$profileId.$environmentId"]
    override suspend fun saveDashboardSnapshot(profileId: String, environmentId: Int, json: String) {
        snapshots["$profileId.$environmentId"] = json
    }
    override suspend fun removeDashboardSnapshots(profileId: String) {
        snapshots.keys.removeAll { it.startsWith("$profileId.") }
    }
    override suspend fun loadShellPreference(key: String): String? = shellPreferences[key]
    override suspend fun setShellPreference(key: String, value: String) {
        shellPreferences[key] = value
    }
    override suspend fun loadCustomShellUsers(): List<String> = customUsers
    override suspend fun saveCustomShellUsers(users: List<String>) {
        customUsers = users
    }

    fun storedEnvironmentId(profileId: String): Int? = environmentIds[profileId]
}
