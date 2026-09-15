package pro.dockhand.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import pro.dockhand.mobile.api.dockhandJson

interface PreferencesRepository {
    val serverProfiles: Flow<List<ServerProfile>>
    val selectedProfileId: Flow<String?>

    suspend fun loadProfiles(): List<ServerProfile>
    suspend fun saveProfiles(profiles: List<ServerProfile>)
    suspend fun loadSelectedProfileId(): String?
    suspend fun setSelectedProfileId(profileId: String?)
    suspend fun loadSelectedEnvironmentId(profileId: String): Int?
    suspend fun setSelectedEnvironmentId(profileId: String, environmentId: Int?)
    suspend fun removeSelectedEnvironmentId(profileId: String)
    suspend fun loadDashboardSnapshot(profileId: String, environmentId: Int): String?
    suspend fun saveDashboardSnapshot(profileId: String, environmentId: Int, json: String)
    suspend fun removeDashboardSnapshots(profileId: String)
    suspend fun loadShellPreference(key: String): String?
    suspend fun setShellPreference(key: String, value: String)
    suspend fun loadCustomShellUsers(): List<String>
    suspend fun saveCustomShellUsers(users: List<String>)
}

class DataStorePreferencesRepository(
    private val dataStore: DataStore<Preferences>
) : PreferencesRepository {

    override val serverProfiles: Flow<List<ServerProfile>> =
        dataStore.data.map { preferences -> decodeProfiles(preferences[PROFILES]) }

    override val selectedProfileId: Flow<String?> =
        dataStore.data.map { preferences -> preferences[SELECTED_PROFILE_ID] }

    override suspend fun loadProfiles(): List<ServerProfile> =
        decodeProfiles(dataStore.data.first()[PROFILES])

    override suspend fun saveProfiles(profiles: List<ServerProfile>) {
        val payload = dockhandJson.encodeToString(PROFILES_SERIALIZER, profiles)
        dataStore.edit { preferences -> preferences[PROFILES] = payload }
    }

    override suspend fun loadSelectedProfileId(): String? =
        dataStore.data.first()[SELECTED_PROFILE_ID]

    override suspend fun setSelectedProfileId(profileId: String?) {
        dataStore.edit { preferences ->
            if (profileId == null) {
                preferences.remove(SELECTED_PROFILE_ID)
            } else {
                preferences[SELECTED_PROFILE_ID] = profileId
            }
        }
    }

    override suspend fun loadSelectedEnvironmentId(profileId: String): Int? =
        dataStore.data.first()[environmentKey(profileId)]?.toIntOrNull()

    override suspend fun setSelectedEnvironmentId(profileId: String, environmentId: Int?) {
        dataStore.edit { preferences ->
            if (environmentId == null) {
                preferences.remove(environmentKey(profileId))
            } else {
                preferences[environmentKey(profileId)] = environmentId.toString()
            }
        }
    }

    override suspend fun removeSelectedEnvironmentId(profileId: String) {
        dataStore.edit { preferences -> preferences.remove(environmentKey(profileId)) }
    }

    override suspend fun loadDashboardSnapshot(profileId: String, environmentId: Int): String? =
        dataStore.data.first()[snapshotKey(profileId, environmentId)]

    override suspend fun saveDashboardSnapshot(profileId: String, environmentId: Int, json: String) {
        dataStore.edit { preferences -> preferences[snapshotKey(profileId, environmentId)] = json }
    }

    override suspend fun removeDashboardSnapshots(profileId: String) {
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith("dashboard.$profileId.") }
                .forEach { preferences.remove(it) }
        }
    }

    override suspend fun loadShellPreference(key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun setShellPreference(key: String, value: String) {
        dataStore.edit { preferences -> preferences[stringPreferencesKey(key)] = value }
    }

    override suspend fun loadCustomShellUsers(): List<String> =
        dataStore.data.first()[CUSTOM_SHELL_USERS]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    override suspend fun saveCustomShellUsers(users: List<String>) {
        dataStore.edit { preferences -> preferences[CUSTOM_SHELL_USERS] = users.joinToString("\n") }
    }

    private fun decodeProfiles(payload: String?): List<ServerProfile> {
        if (payload.isNullOrEmpty()) return emptyList()
        return try {
            dockhandJson.decodeFromString(PROFILES_SERIALIZER, payload)
        } catch (error: Exception) {
            emptyList()
        }
    }

    private fun environmentKey(profileId: String) = stringPreferencesKey("environment.$profileId")

    private fun snapshotKey(profileId: String, environmentId: Int) =
        stringPreferencesKey("dashboard.$profileId.$environmentId")

    private companion object {
        val PROFILES = stringPreferencesKey("serverProfiles")
        val SELECTED_PROFILE_ID = stringPreferencesKey("selectedProfileId")
        val CUSTOM_SHELL_USERS = stringPreferencesKey("shell.customUsers")
        val PROFILES_SERIALIZER = ListSerializer(ServerProfile.serializer())
    }
}
