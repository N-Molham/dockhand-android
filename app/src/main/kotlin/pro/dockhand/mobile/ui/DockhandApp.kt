package pro.dockhand.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import pro.dockhand.mobile.app.AppViewModel
import pro.dockhand.mobile.ui.screens.ContainersScreen
import pro.dockhand.mobile.ui.screens.DashboardScreen
import pro.dockhand.mobile.ui.screens.ImagesScreen
import pro.dockhand.mobile.ui.screens.SettingsScreen
import pro.dockhand.mobile.ui.screens.StacksScreen

private enum class AppTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Home),
    CONTAINERS("Containers", Icons.AutoMirrored.Filled.List),
    STACKS("Stacks", Icons.Default.Menu),
    IMAGES("Images", Icons.Default.Star)
}

@Composable
fun DockhandApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.bootstrap()
    }

    BackHandler(enabled = showSettings) {
        showSettings = false
    }
    BackHandler(enabled = !showSettings && selectedTab != AppTab.DASHBOARD) {
        selectedTab = AppTab.DASHBOARD
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            EnvironmentHeaderBar(
                state = state,
                onSelectEnvironment = { environmentId ->
                    viewModel.selectEnvironmentInScope(environmentId)
                },
                onRefresh = {
                    viewModel.refreshCurrentScreen()
                    viewModel.requestDashboardRefresh()
                },
                onOpenSettings = { showSettings = true }
            )
        },
        bottomBar = {
            if (!showSettings) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (showSettings) {
            SettingsScreen(viewModel = viewModel, onClose = { showSettings = false }, modifier = Modifier.padding(padding))
        } else {
            when (selectedTab) {
                AppTab.DASHBOARD -> DashboardScreen(viewModel, Modifier.padding(padding))
                AppTab.CONTAINERS -> ContainersScreen(viewModel, Modifier.padding(padding))
                AppTab.STACKS -> StacksScreen(viewModel, Modifier.padding(padding))
                AppTab.IMAGES -> ImagesScreen(viewModel, Modifier.padding(padding))
            }
        }
    }
}
