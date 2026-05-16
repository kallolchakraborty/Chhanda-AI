package com.chhanda.ai

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
class MainActivity : FragmentActivity() {
    private var systemViewModel: SystemViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        

        setContent {
            // PRO HARDENING: Delay PDFBox and other heavy native inits until UI is ready
            val context = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(Unit) {
                try {
                    android.util.Log.i("BOOT", "Initializing PDFBox in UI context...")
                    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
                    android.util.Log.i("BOOT", "PDFBox SUCCESS")
                } catch (e: Throwable) {
                    android.util.Log.e("BOOT", "Deferred init FAILED", e)
                }
            }

            val vm: SystemViewModel = hiltViewModel()
            systemViewModel = vm
            val isDark by vm.darkMode.collectAsState()
            
            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                ChhandaApp(vm)
            }

            // PRO FIX: Request Permissions for Foreground Service and Storage
            val permissions = mutableListOf<String>()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
                // For RAG we often need files, so requesting media permissions is a good first step on API 33+
                permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
                permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
                permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            
            
            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val denied = result.filter { !it.value }
                if (denied.isNotEmpty()) {
                    vm.addLog("SYSTEM", "Some permissions denied. App functionality might be limited.", "WARNING")
                }
            }
            
            LaunchedEffect(Unit) {
                launcher.launch(permissions.toTypedArray())
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

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun ChhandaApp(systemViewModel: SystemViewModel) {
    val navController = rememberNavController()
    val items = listOf(
        Screen.Dashboard,
        Screen.Models,
        Screen.Settings,
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

    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val appLanguage by systemViewModel.appLanguage.collectAsState()
                val ragEnabled by systemViewModel.ragEnabled.collectAsState()
                
                items.forEach { screen ->
                    if (screen == Screen.Models && !ragEnabled) return@forEach
                    
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
        androidx.compose.animation.SharedTransitionLayout {
            NavHost(
                navController = navController, 
                startDestination = "welcome", 
                modifier = Modifier.padding(innerPadding)
            ) {
                composable("welcome") { WelcomeScreen(navController) }
                composable(Screen.Dashboard.route) { 
                    DashboardScreen(
                        navController = navController, 
                        viewModel = systemViewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable
                    ) 
                }
                composable(Screen.Models.route) { 
                    val ragEnabled by systemViewModel.ragEnabled.collectAsState()
                    if (ragEnabled) {
                        KnowledgeBaseScreen(navController, systemViewModel) 
                    } else {
                        LaunchedEffect(Unit) {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                            }
                        }
                    }
                }
                composable(Screen.Settings.route) { ConfigScreen(navController, systemViewModel) }
                composable(Screen.Logs.route) { LogsScreen(navController, systemViewModel) }
                composable(
                    "chat/{modelName}?sessionId={sessionId}&readOnly={readOnly}",
                    arguments = listOf(
                        androidx.navigation.navArgument("modelName") { type = androidx.navigation.NavType.StringType },
                        androidx.navigation.navArgument("sessionId") { 
                            type = androidx.navigation.NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        androidx.navigation.navArgument("readOnly") {
                            type = androidx.navigation.NavType.BoolType
                            defaultValue = false
                        }
                    )
                ) { backStackEntry ->
                    val readOnly = backStackEntry.arguments?.getBoolean("readOnly") ?: false
                    val viewModel: ChatViewModel = hiltViewModel()
                    ChatScreen(
                        navController = navController, 
                        viewModel = viewModel, 
                        isReadOnly = readOnly,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable
                    )
                }
            }
        }
    }
}

sealed class Screen(val route: String, val translationKey: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "dashboard", Icons.Default.Home)
    object Models : Screen("models", "models", Icons.Default.List)
    object Settings : Screen("settings", "settings", Icons.Default.Settings)
    object Logs : Screen("logs", "logs", Icons.Default.Code)
}
