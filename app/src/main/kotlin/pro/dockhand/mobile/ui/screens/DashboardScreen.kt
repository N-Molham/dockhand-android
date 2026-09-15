package pro.dockhand.mobile.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pro.dockhand.mobile.app.AppViewModel

@Composable
fun DashboardScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    Text("Dashboard", modifier = modifier.fillMaxSize())
}
