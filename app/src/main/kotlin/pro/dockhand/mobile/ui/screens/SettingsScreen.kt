package pro.dockhand.mobile.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pro.dockhand.mobile.app.AppViewModel

@Composable
fun SettingsScreen(viewModel: AppViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Text("Settings", modifier = modifier.fillMaxSize())
}
