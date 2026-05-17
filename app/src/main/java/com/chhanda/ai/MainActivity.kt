package com.chhanda.ai

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.activity.enableEdgeToEdge

import com.chhanda.ai.presentation.viewmodel.SystemViewModel
import com.chhanda.ai.util.NativeLifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private var systemViewModel: SystemViewModel? = null
    
    @javax.inject.Inject
    lateinit var nativeLifecycleObserver: NativeLifecycleObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // Register global native lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(nativeLifecycleObserver)
        

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
            
            com.chhanda.ai.presentation.ui.theme.ChhandaTheme(darkTheme = isDark) {
                val appSecurityEnabled by vm.appSecurityEnabled.collectAsState()
                var isAppUnlocked by remember(appSecurityEnabled) { mutableStateOf(!appSecurityEnabled) }
                var appAuthError by remember { mutableStateOf<String?>(null) }
                val activity = context as? androidx.fragment.app.FragmentActivity

                LaunchedEffect(appSecurityEnabled) {
                    if (appSecurityEnabled) {
                        if (activity != null && com.chhanda.ai.util.BiometricAuthenticator.canAuthenticate(context)) {
                            isAppUnlocked = false
                            com.chhanda.ai.util.BiometricAuthenticator.authenticate(
                                activity = activity,
                                onResult = { success, error ->
                                    if (success) {
                                        isAppUnlocked = true
                                        appAuthError = null
                                    } else {
                                        appAuthError = error ?: "Authentication failed"
                                    }
                                }
                            )
                        } else {
                            isAppUnlocked = true
                        }
                    } else {
                        isAppUnlocked = true
                    }
                }

                if (!isAppUnlocked) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked",
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Secure Gateway",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = appAuthError ?: "Authentication required to access Chhanda AI",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(onClick = {
                                    if (activity != null) {
                                        com.chhanda.ai.util.BiometricAuthenticator.authenticate(
                                            activity = activity,
                                            onResult = { success, error ->
                                                if (success) {
                                                    isAppUnlocked = true
                                                    appAuthError = null
                                                } else {
                                                    appAuthError = error ?: "Authentication failed"
                                                }
                                            }
                                        )
                                    }
                                }) {
                                    Text("Unlock Application")
                                }
                            }
                        }
                    }
                } else {
                    ChhandaApp(vm)
                }
            }

            // PRO FIX: Request ALL Permissions at startup for smooth UX
            val permissions = mutableListOf<String>()
            
            // Core permissions
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
                permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
                permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
                permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            
            // Feature-specific permissions (Camera for QR, Audio for Voice)
            permissions.add(android.Manifest.permission.RECORD_AUDIO)
            permissions.add(android.Manifest.permission.CAMERA)
            
            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val denied = result.filter { !it.value }
                if (denied.isNotEmpty()) {
                    vm.addLog("SYSTEM", "Some permissions (${denied.keys.joinToString(", ")}) denied. App functionality might be limited.", "WARNING")
                } else {
                    vm.addLog("SYSTEM", "All permissions granted.", "SUCCESS")
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
                composable(Screen.Comparison.route) { ModelComparisonScreen(navController, systemViewModel) }
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
    object Comparison : Screen("comparison", "comparison", Icons.Default.Compare)
}
