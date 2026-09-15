package pro.dockhand.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import pro.dockhand.mobile.app.AppViewModel
import pro.dockhand.mobile.data.DataStorePreferencesRepository
import pro.dockhand.mobile.data.KeystoreSecureStore
import pro.dockhand.mobile.ui.DockhandApp
import pro.dockhand.mobile.ui.theme.DockhandTheme

class MainActivity : ComponentActivity() {

    private lateinit var appViewModel: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val preferenceStore = createDataStore(this, "dockhand_preferences")
        val secureStore = createDataStore(this, "dockhand_secure")

        appViewModel = AppViewModel(
            preferences = DataStorePreferencesRepository(preferenceStore),
            secureStore = KeystoreSecureStore(secureStore),
            scope = lifecycleScope
        )

        setContent {
            DockhandTheme {
                DockhandApp(viewModel = appViewModel)
            }
        }
    }

    private fun createDataStore(context: Context, name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ) {
            File(context.filesDir, "$name.preferences_pb")
        }
}
