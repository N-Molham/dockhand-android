package pro.dockhand.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import pro.dockhand.mobile.app.AppViewModel
import pro.dockhand.mobile.data.DataStorePreferencesRepository
import pro.dockhand.mobile.data.KeystoreSecureStore
import pro.dockhand.mobile.ui.DockhandApp
import pro.dockhand.mobile.ui.theme.DockhandTheme

private val Context.dockhandPreferences: DataStore<Preferences> by preferencesDataStore("dockhand_preferences")
private val Context.dockhandSecure: DataStore<Preferences> by preferencesDataStore("dockhand_secure")

class MainActivity : ComponentActivity() {

    private lateinit var appViewModel: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appViewModel = AppViewModel(
            preferences = DataStorePreferencesRepository(applicationContext.dockhandPreferences),
            secureStore = KeystoreSecureStore(applicationContext.dockhandSecure),
            scope = lifecycleScope
        )

        setContent {
            DockhandTheme {
                DockhandApp(viewModel = appViewModel)
            }
        }
    }
}
