package com.chhanda.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.chhanda.ai.presentation.ui.*
import com.chhanda.ai.presentation.ui.components.ChhandaLogo
import com.chhanda.ai.presentation.viewmodel.ChatViewModel
import dagger.hilt.android.AndroidEntryPoint

import com.chhanda.ai.presentation.viewmodel.SystemViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var systemViewModel: SystemViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: SystemViewModel = hiltViewModel()
            systemViewModel = vm
            val isDark by vm.darkMode.collectAsState()
            
            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                ChhandaApp(vm)
            }

            // PRO FIX: Request Notification Permission for Foreground Service on API 33+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val permission = android.Manifest.permission.POST_NOTIFICATIONS
                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (!isGranted) {
                        vm.addLog("SYSTEM", "Notification permission denied. Background server may be less stable.", "WARNING")
                    }
                }
                LaunchedEffect(Unit) {
                    launcher.launch(permission)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        systemViewModel?.onVisibilityChanged(true)
    }

    override fun onStop() {
        super.onStop()
        systemViewModel?.onVisibilityChanged(false)
    }
}

@Composable
fun ChhandaApp(systemViewModel: SystemViewModel) {
    val navController = rememberNavController()
    val items = listOf(
        Screen.Dashboard,
        Screen.Models,
        Screen.Config,
        Screen.Logs
    )

    var showExitDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    
    androidx.activity.compose.BackHandler(enabled = true) {
        val navBackStackEntry = navController.currentBackStackEntry
        val currentRoute = navBackStackEntry?.destination?.route
        if (currentRoute == Screen.Dashboard.route) {
            showExitDialog = true
        } else {
            navController.popBackStack()
        }
    }
    
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit Chhanda") },
            text = { Text("Do you want to keep the app running in the background?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    val activity = (context as? android.app.Activity)
                    activity?.moveTaskToBack(true)
                }) {
                    Text("Yes, keep running")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    systemViewModel.shutdown()
                    val activity = (context as? android.app.Activity)
                    activity?.finish()
                }) {
                    Text("No, close completely")
                }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val appLanguage by systemViewModel.appLanguage.collectAsState()
                
                items.forEach { screen ->
                    val localizedTitle = com.chhanda.ai.util.Localization.getString(screen.translationKey, appLanguage)
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = localizedTitle) },
                        label = { Text(localizedTitle) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId)
                                    launchSingleTop = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF2563EB),
                            selectedTextColor = Color(0xFF2563EB),
                            indicatorColor = Color(0xFFDBEAFE)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController, 
            startDestination = Screen.Dashboard.route, 
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(navController, systemViewModel) }
            composable(Screen.Models.route) { KnowledgeBaseScreen(navController, systemViewModel) }
            composable(Screen.Config.route) { ConfigScreen(navController, systemViewModel) }
            composable(Screen.Logs.route) { LogsScreen(navController, systemViewModel) }
            composable(
                "chat/{modelName}?sessionId={sessionId}",
                arguments = listOf(
                    androidx.navigation.navArgument("modelName") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("sessionId") { 
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val viewModel: ChatViewModel = hiltViewModel()
                ChatScreen(navController = navController, viewModel = viewModel)
            }
        }
    }
}

sealed class Screen(val route: String, val translationKey: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "dashboard", Icons.Default.Home)
    object Models : Screen("models", "models", Icons.Default.LibraryBooks)
    object Config : Screen("config", "config", Icons.Default.Code)
    object Logs : Screen("logs", "logs", Icons.Default.Terminal)
}
