package pro.dockhand.mobile.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pro.dockhand.mobile.app.AppViewModel

@Composable
fun StacksScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    Text("Stacks", modifier = modifier.fillMaxSize())
}
