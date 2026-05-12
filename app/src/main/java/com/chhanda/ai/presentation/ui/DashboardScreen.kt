package com.chhanda.ai.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import com.chhanda.ai.domain.model.QRCodeGenerator
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll

import com.chhanda.ai.presentation.ui.components.ChhandaSectionHeader
import com.chhanda.ai.presentation.ui.components.ChhandaCard
import com.chhanda.ai.presentation.ui.components.ChhandaLogo
import com.chhanda.ai.Screen
import androidx.navigation.NavController
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.chhanda.ai.presentation.viewmodel.SystemViewModel
import com.chhanda.ai.util.Localization
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: SystemViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val ramUsage by viewModel.ramUsage.collectAsState()
    val appStorageUsage by viewModel.appStorageUsage.collectAsState()
    val tps by viewModel.tokensPerSec.collectAsState()
    val port by viewModel.serverPort.collectAsState()
    val deviceTemperature by viewModel.deviceTemperature.collectAsState()
    val ownedModels by viewModel.ownedModels.collectAsState()
    val sharedModels by viewModel.sharedModels.collectAsState()
    val downloadableModels by viewModel.downloadableModels.collectAsState()
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val actualPort by viewModel.serverActualPort.collectAsState()
    val isVpnActive by viewModel.isVpnActive.collectAsState()
    val networkIps by viewModel.networkIps.collectAsState()
    val isTunnelActive by viewModel.isTunnelActive.collectAsState()

    val processorInfo by viewModel.processorInfo.collectAsState()
    val tunnelUrl by viewModel.tunnelUrl.collectAsState()
    val publicUrl by viewModel.publicUrl.collectAsState()
    val displayPort = if (actualPort > 0) actualPort else 8888
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val activeDeviceCount by viewModel.activeDeviceCount.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloadPauseState by viewModel.downloadPauseFlow.collectAsState()
    val isModelLoading by viewModel.isModelLoading.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val vectorDbUsage by viewModel.vectorDbUsage.collectAsState()
    val vectorDbCapacityBytes by viewModel.vectorDbCapacityBytes.collectAsState()
    val context = LocalContext.current
    var showModelPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeviceManager by remember { mutableStateOf(false) }
    var showStorageManager by remember { mutableStateOf(false) }
    var deviceIdToClear by remember { mutableStateOf<String?>(null) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showHotspotPrompt by remember { mutableStateOf(false) }
    val hasNetworkState by viewModel.hasNetwork.collectAsState()
    var expandedAssistant by remember { mutableStateOf<String?>(null) }
    var isApiKeyVisibleInQr by remember { mutableStateOf(false) }
    var modelToDelete by remember { mutableStateOf<String?>(null) }
    var showBackgroundExitDialog by remember { mutableStateOf(false) }

    // Detect exit/background state
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    
    // BACK HANDLER: If server is active, ask if user wants to keep it in background
    androidx.activity.compose.BackHandler(enabled = isServerRunning) {
        showBackgroundExitDialog = true
    }

    // LIFECYCLE: Show toast when app goes to background in offline mode
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                if (isServerRunning) { 
                    android.widget.Toast.makeText(
                        context, 
                        "AI Node is active in background. Use notification to STOP.", 
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Background Exit Dialog
    if (showBackgroundExitDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundExitDialog = false },
            title = { Text("Chhanda Gateway") },
            text = { Text("The AI server is running. Do you want to STOP the server or keep it running in the background for other devices?") },
            confirmButton = {
                TextButton(onClick = { 
                    showBackgroundExitDialog = false
                    (context as? android.app.Activity)?.moveTaskToBack(true)
                }) {
                    Text("Keep Running")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showBackgroundExitDialog = false
                    com.chhanda.ai.service.ChhandaForegroundService.stop(context)
                    (context as? android.app.Activity)?.finish()
                }) {
                    Text("Stop Server", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    val showVectorWarning by viewModel.showVectorStorageWarning.collectAsState()
    if (showVectorWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissVectorStorageWarning() },
            icon = { Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Storage Nearly Full") },
            text = { Text("The vector database is 90% full. Please empty the vector database or free up phone space to continue using RAG.") },
            confirmButton = {
                Button(onClick = { 
                    viewModel.dismissVectorStorageWarning()
                    showStorageManager = true 
                }) {
                    Text("Manage Storage")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissVectorStorageWarning() }) {
                    Text("Dismiss")
                }
            }
        )
    }
    var showHistorySheet by remember { mutableStateOf(false) }
    var selectedModelForHistory by remember { mutableStateOf<String?>(null) }
    val selectedSessions = remember { mutableStateListOf<String>() }
    val activeModelName = remember(ownedModels, sharedModels) {
        (ownedModels + sharedModels).firstOrNull { it.isActive }?.name ?: "No Active Model"
    }
    val anyActiveModel = remember(ownedModels, sharedModels) {
        (ownedModels + sharedModels).any { it.isActive }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            // Copy URI to a temp file or get path (complicated on Android)
            // For now, we'll try to resolve the path if it's a file URI or similar
            // or use a utility to copy it to filesDir.
            val path = com.chhanda.ai.util.FileUtils.getPathFromUri(context, it)
            if (path != null) {
                viewModel.registerCustomModel(java.io.File(path))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChhandaLogo(size = 32)

                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Chhanda", 
                            fontSize = 20.sp, 
                            fontWeight = FontWeight.ExtraBold, 
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    val isServerRunning by viewModel.isServerRunning.collectAsState()
                    val ipAddress by viewModel.localIpAddress.collectAsState()
                    val isQrEnabled = (isServerRunning || isModelLoading) && anyActiveModel
                    
                    var showPulse by remember { mutableStateOf(false) }
                    LaunchedEffect(isQrEnabled) {
                        if (isQrEnabled) {
                            showPulse = true
                            delay(5000)
                            showPulse = false
                        }
                    }

                    val pulseScale by animateFloatAsState(
                        targetValue = if (showPulse) 1.2f else 1.0f,
                        animationSpec = if (showPulse) {
                            infiniteRepeatable(
                                animation = tween(600, easing = LinearOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            )
                        } else {
                            tween(300)
                        },
                        label = "QrPulse"
                    )

                    val networkIps by viewModel.networkIps.collectAsState()
                    val isHotspot = networkIps.any { it.startsWith("192.168.43.") || it.startsWith("192.168.44.") }
                    val hasNetwork = networkIps.isNotEmpty() && networkIps.first() != "127.0.0.1"

                    // Network status icon removed - logic moved to QR button click



                    IconButton(
                        onClick = { 
                            if (!hasNetworkState) {
                                showHotspotPrompt = true 
                            } else {
                                showQrDialog = true
                            }
                            showPulse = false
                        },
                        enabled = isQrEnabled,
                        modifier = Modifier.scale(pulseScale)
                    ) { 
                        Icon(
                            Icons.Default.QrCode, 
                            contentDescription = "Share Server",
                            tint = if (isQrEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                        ) 
                    }

                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {


            item {
                if (isVpnActive) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                Localization.getString("vpn_warning", appLanguage),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                ActiveModelCard(
                    modelName = activeModelName,
                    isRunning = isServerRunning,
                    isLocalModelPresent = (ownedModels + sharedModels).isNotEmpty(),
                    port = displayPort.toString(),
                    onStop = { viewModel.stopServer() },
                    onStart = { showModelPicker = true },
                    onTryIt = { 
                        selectedModelForHistory = activeModelName
                        showHistorySheet = true 
                    },
                    isLoading = isModelLoading,
                    temperature = deviceTemperature,
                    appLanguage = appLanguage,
                    ipAddress = viewModel.localIpAddress.collectAsState().value
                )
            }
            
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatCard(Modifier.weight(1f), Localization.getString("app_storage", appLanguage), appStorageUsage, Icons.Default.Storage)
                    StatCard(
                        Modifier.weight(1f), 
                        Localization.getString("processor", appLanguage), 
                        processorInfo, 
                        Icons.Default.DeveloperBoard
                    )
                }
            }
            

            
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatCard(Modifier.weight(1f), Localization.getString("tps", appLanguage), tps, Icons.Default.Speed)
                    StatCard(
                        Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)), 
                        Localization.getString("devices", appLanguage), 
                        "$activeDeviceCount " + Localization.getString("connected", appLanguage), 
                        Icons.Default.Devices,
                        onClick = { showDeviceManager = true }
                    )
                }
            }
            
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChhandaSectionHeader(
                        icon = Icons.Default.LibraryBooks, 
                        title = Localization.getString("internal_models", appLanguage), 
                        badge = "${ownedModels.size} " + Localization.getString("secure_badge", appLanguage)
                    )
                    Row {
                        IconButton(onClick = { viewModel.scanForModels() }, enabled = !isScanning) {
                            if (isScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, Localization.getString("refresh", appLanguage), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = { fileLauncher.launch("*/*") }) {
                            Icon(Icons.Default.AddCircle, Localization.getString("import", appLanguage), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            
            items(
                items = ownedModels,
                key = { "owned_${it.name}" }
            ) { model ->
                LocalModelItem(
                    model = model,
                    isServerRunning = isServerRunning,
                    onActivate = { viewModel.activateModel(model.name) }, 
                    onStop = { viewModel.stopServer() },
                    onTryIt = { 
                        viewModel.activateModel(model.name)
                        selectedModelForHistory = model.name
                        showHistorySheet = true 
                    },
                    onDelete = { modelToDelete = model.name },
                    appLanguage = appLanguage
                )
            }

            if (sharedModels.isNotEmpty()) {
                item {
                    ChhandaSectionHeader(
                        icon = Icons.Default.FolderShared, 
                        title = Localization.getString("shared_models", appLanguage), 
                        badge = "${sharedModels.size} " + Localization.getString("detected_badge", appLanguage)
                    )
                }
                
                items(
                    items = sharedModels,
                    key = { "shared_${it.name}" }
                ) { model ->
                    LocalModelItem(
                        model = model,
                        isServerRunning = isServerRunning,
                        onActivate = { viewModel.activateModel(model.name) }, 
                        onStop = { viewModel.stopServer() },
                        onTryIt = { 
                            viewModel.activateModel(model.name)
                            selectedModelForHistory = model.name
                            showHistorySheet = true 
                        },
                        onDelete = { modelToDelete = model.name },
                        appLanguage = appLanguage
                    )
                }
            }
            
            item {
                ChhandaSectionHeader(icon = Icons.Default.CloudDownload, title = Localization.getString("downloadable_models", appLanguage), badge = "")
            }
            
            items(
                items = downloadableModels,
                key = { "dl_${it.name}" }
            ) { model ->
                DownloadableModelItem(
                    model = model, 
                    progress = downloadProgress[model.name],
                    isPaused = downloadPauseState[model.name] == true,
                    onDownload = { 
                        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                             Manifest.permission.READ_MEDIA_VIDEO
                        } else {
                             Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }

                        val isPermissionGranted = ContextCompat.checkSelfPermission(
                            context,
                            permission
                        ) == PackageManager.PERMISSION_GRANTED

                        if (isPermissionGranted || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            viewModel.downloadModel(model)
                        } else {
                            permissionLauncher.launch(permission)
                        }
                    },
                    onPause = { viewModel.pauseDownload(model.name) },
                    onResume = { viewModel.resumeDownload(model.name, model) },
                    onCancel = { viewModel.cancelDownload(model.name) },
                    appLanguage = appLanguage
                )
            }
            

            item {
                ChhandaSectionHeader(icon = Icons.Default.Chat, title = Localization.getString("chat_management", appLanguage), badge = Localization.getString("secure_badge", appLanguage))
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(Localization.getString("manage_chat_history", appLanguage), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(Localization.getString("manage_chat_history_desc", appLanguage), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = { showStorageManager = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(Localization.getString("open", appLanguage))
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (showHistorySheet && selectedModelForHistory != null) {
        val modelName = selectedModelForHistory!!
        val sessions by viewModel.getSessionsForModel(modelName).collectAsState(initial = emptyList())

        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Chat History - $modelName", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    
                    if (selectedSessions.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.deleteSessions(selectedSessions.toList())
                                selectedSessions.clear()
                            }
                        ) {
                            Icon(Icons.Default.Delete, "Delete Selected", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                
                Spacer(Modifier.height(20.dp))

                // Option to start a new chat
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        showHistorySheet = false
                        navController.navigate("chat/$modelName")
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, "New Chat", tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Start New Chat", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (sessions.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No previous chats", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sessions) { sessionId ->
                            val isSelected = selectedSessions.contains(sessionId)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                                onClick = {
                                    if (selectedSessions.isNotEmpty()) {
                                        if (isSelected) selectedSessions.remove(sessionId) else selectedSessions.add(sessionId)
                                    } else {
                                        showHistorySheet = false
                                        navController.navigate("chat/$modelName?sessionId=$sessionId")
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedSessions.add(sessionId) else selectedSessions.remove(sessionId)
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "Session: ${sessionId.take(8)}...", // Show short ID
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    if (selectedSessions.isEmpty()) {
                                        IconButton(
                                            onClick = {
                                                viewModel.deleteSessions(listOf(sessionId))
                                            }
                                        ) {
                                            Icon(Icons.Default.Delete, "Delete Session", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeviceManager) {
        ModalBottomSheet(
            onDismissRequest = { showDeviceManager = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Device Management", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.purgeDeviceLogs() }) {
                        Text("Clear Logs", color = MaterialTheme.colorScheme.error)
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                val ipAddress by viewModel.localIpAddress.collectAsState()
                val anyActiveModel = (ownedModels + sharedModels).any { it.isActive }
                val isQrEnabled = (isServerRunning || isModelLoading) && anyActiveModel
                
                val contentColor = if (isQrEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                
                Surface(
                    onClick = { 
                        if (isQrEnabled) {
                            if (!hasNetworkState) {
                                showHotspotPrompt = true
                            } else {
                                showQrDialog = true
                            }
                        }
                    },
                    color = if (isQrEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isQrEnabled
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCode, null, tint = contentColor)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Share via QR Code", fontWeight = FontWeight.Bold, color = contentColor)
                            Text("Invite other devices to use this AI model.", fontSize = 12.sp, color = contentColor.copy(alpha = 0.7f))
                        }
                        Icon(Icons.Default.ArrowForward, null, tint = contentColor)
                    }
                }
                
                Spacer(Modifier.height(24.dp))
                
                Text("Connection History", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(connectedDevices) { device ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(8.dp).clip(CircleShape).background(if (device.isCurrentlyConnected) Color(0xFF4ADE80) else Color.Gray)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.deviceName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("${device.ipAddress} • ${device.userAgent}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                Text(
                                    if (device.isCurrentlyConnected) "Connected" else "Disconnected",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (device.isCurrentlyConnected) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(Modifier.width(12.dp))

                                Column(horizontalAlignment = Alignment.End) {
                                    val dateStr = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(device.connectionTime))
                                    Text(dateStr, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (device.durationMs != null) {
                                        val mins = device.durationMs / 60000
                                        Text("${mins}m session", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    } else if (device.isCurrentlyConnected) {
                                        Text("Active Now", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4ADE80))
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        deviceIdToClear = device.deviceName // or deviceId if available
                                        showClearHistoryConfirm = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteSweep, 
                                        null, 
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                    if (connectedDevices.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text("No external devices connected yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            }
                        }
                    }
                }
                
                if (connectedDevices.any { it.isCurrentlyConnected }) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.disconnectAllDevices() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("Disconnect All Devices", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        ModalBottomSheet(
            onDismissRequest = { showModelPicker = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Spacer(Modifier.height(24.dp))

                if ((ownedModels + sharedModels).isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.FolderOff, 
                                null, 
                                modifier = Modifier.size(64.dp), 
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "No Models Found", 
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Place model files in Downloads or app folder.", 
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.scanForModels() },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Scan Storage")
                            }
                        }
                    }
                } else {
                    LazyColumn {
                        items(ownedModels + sharedModels) { model ->
                            Surface(
                                onClick = {
                                    viewModel.activateModel(model.name)
                                    showModelPicker = false
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                color = if (model.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Dns, 
                                        null, 
                                        tint = if (model.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            model.name, 
                                            fontWeight = FontWeight.Bold, 
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            model.details, 
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (model.isActive) {
                                        Icon(
                                            Icons.Default.CheckCircle, 
                                            null, 
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text(Localization.getString("clear_history_title", appLanguage)) },
            text = { Text(Localization.getString("clear_history_text", appLanguage)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deviceIdToClear?.let { viewModel.clearHistoryForDevice(it) }
                        showClearHistoryConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("CLEAR")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text("CANCEL")
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(Localization.getString("purge_history_title", appLanguage)) },
            text = { Text(Localization.getString("purge_history_text", appLanguage)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllData()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(Localization.getString("confirm_purge", appLanguage))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("CANCEL")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.error,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    GatewayDialog(
        show = showQrDialog || showHotspotPrompt,
        onDismiss = { 
            showQrDialog = false
            showHotspotPrompt = false
        },
        viewModel = viewModel,
        displayPort = displayPort,
        tunnelUrl = tunnelUrl
    )


    if (showStorageManager) {
        val storageSummary by viewModel.storageSummary.collectAsState()
        ModalBottomSheet(
            onDismissRequest = { showStorageManager = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            StorageManagerSheet(
                summary = storageSummary,
                onClearAll = { 
                    viewModel.clearAllData()
                    showStorageManager = false
                },
                onClearDevice = { deviceId ->
                    viewModel.clearHistoryForDevice(deviceId)
                },
                navController = navController,
                viewModel = viewModel
            )
        }
    }

    if (modelToDelete != null) {
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Model?") },
            text = { Text("Are you sure you want to permanently delete '${modelToDelete}'? This will remove the model weights from your device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        modelToDelete?.let { viewModel.deleteModel(it) }
                        modelToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("DELETE")
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text("CANCEL")
                }
            }
        )
    }


}

@Composable
fun StorageManagerSheet(
    summary: com.chhanda.ai.presentation.viewmodel.StorageSummary,
    onClearAll: () -> Unit,
    onClearDevice: (String) -> Unit,
    navController: NavController,
    viewModel: com.chhanda.ai.presentation.viewmodel.SystemViewModel
) {
    var expandedDeviceId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 40.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("History Manager", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        
        Spacer(Modifier.height(24.dp))
        
        // Level 1: Global Summary
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Global LLM History", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("${summary.totalMessages} total messages across ${summary.totalDevices} devices", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                    Button(
                        onClick = onClearAll,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Purge All", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        val isIngesting by viewModel.isIngesting.collectAsState()
        val progress by viewModel.ingestionProgress.collectAsState()
        val message by viewModel.ingestionMessage.collectAsState()
        
        com.chhanda.ai.presentation.ui.components.IngestionProgressDialog(
            isIngesting = isIngesting,
            progress = progress,
            message = message,
            onDismiss = { viewModel.dismissIngestionProgress() }
        )
        
        Text("DEVICES & HISTORY", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        
        // Level 2 & 3: Device List and their History
        LazyColumn(
            modifier = Modifier.heightIn(max = 500.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(summary.devicesHistory) { deviceHistory ->
                DeviceHistoryItem(
                    deviceHistory = deviceHistory,
                    isExpanded = expandedDeviceId == deviceHistory.deviceId,
                    onToggleExpand = {
                        expandedDeviceId = if (expandedDeviceId == deviceHistory.deviceId) null else deviceHistory.deviceId
                    },
                    onClear = { onClearDevice(deviceHistory.deviceId) },
                    onThreadClick = { sessionId, modelName, readOnly ->
                        navController.navigate("chat/$modelName?sessionId=$sessionId&readOnly=$readOnly")
                    }
                )
            }
            
            if (summary.devicesHistory.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("No chat history available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        var selectedFiles by remember { mutableStateOf(setOf<String>()) }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("RAG FILES", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            if (selectedFiles.isNotEmpty()) {
                TextButton(onClick = {
                    viewModel.deleteFiles(selectedFiles.toList())
                    selectedFiles = emptySet()
                }) {
                    Text("Delete Selected (${selectedFiles.size})", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        
        val showAllFiles by viewModel.showAllFiles.collectAsState()
        val recentFiles by viewModel.recentFiles.collectAsState()
        val allFiles by viewModel.allFiles.collectAsState()
        
        val filesToShow = if (showAllFiles) allFiles else recentFiles
        
        LazyColumn(
            modifier = Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filesToShow) { file ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!file.isDeleted) {
                        Checkbox(
                            checked = selectedFiles.contains(file.id),
                            onCheckedChange = { checked ->
                                selectedFiles = if (checked) {
                                    selectedFiles + file.id
                                } else {
                                    selectedFiles - file.id
                                }
                            }
                        )
                    } else {
                        Spacer(Modifier.width(48.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            file.name, 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 12.sp,
                            color = if (file.isDeleted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
                            textDecoration = if (file.isDeleted) null else androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier.clickable(enabled = !file.isDeleted) {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(android.net.Uri.parse(file.path), context.contentResolver.getType(android.net.Uri.parse(file.path)))
                                        flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Cannot open file", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        Text(
                            "${file.format} · ${file.size / 1024} KB" + if (file.isDeleted) " · Deleted" else "", 
                            fontSize = 10.sp, 
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    if (!file.isDeleted && selectedFiles.isEmpty()) {
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { 
                                        viewModel.deleteFiles(listOf(file.id))
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Open") },
                                    onClick = { 
                                        try {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                setDataAndType(android.net.Uri.parse(file.path), context.contentResolver.getType(android.net.Uri.parse(file.path)))
                                                flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "Cannot open file", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Launch, null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                }
            }
            
            if (filesToShow.isEmpty()) {
                item {
                    Text("No files uploaded for RAG.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            
            if (!showAllFiles && allFiles.size > 10) {
                item {
                    TextButton(onClick = { viewModel.setShowAllFiles(true) }) {
                        Text("See More Files", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceHistoryItem(
    deviceHistory: com.chhanda.ai.presentation.viewmodel.DeviceHistoryInfo,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClear: () -> Unit,
    onThreadClick: (sessionId: String, modelName: String, readOnly: Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (deviceHistory.deviceId == "local") Icons.Default.Smartphone else Icons.Default.Devices,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(deviceHistory.deviceName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("${deviceHistory.messageCount} messages", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (isExpanded) androidx.compose.material.icons.Icons.Default.ExpandLess else androidx.compose.material.icons.Icons.Default.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(androidx.compose.material.icons.Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                }
            }
            
            if (isExpanded) {
                Divider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                Column(modifier = Modifier.padding(16.dp)) {
                    val threads = deviceHistory.messages.groupBy { it.sessionId }
                    threads.forEach { (sessionId, messages) ->
                        val firstMessage = messages.firstOrNull { it.role == "user" }?.text 
                            ?: messages.firstOrNull()?.text ?: "Empty Chat"
                        val snippet = if (firstMessage.length > 40) firstMessage.take(40) + "..." else firstMessage
                        val modelName = messages.firstOrNull()?.modelName ?: "unknown"
                        val readOnly = deviceHistory.deviceId != "local"
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onThreadClick(sessionId, modelName, readOnly) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ChatBubble, 
                                null, 
                                modifier = Modifier.size(16.dp), 
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(snippet, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${messages.size} messages · $modelName", 
                                    fontSize = 10.sp, 
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight, 
                                null, 
                                modifier = Modifier.size(16.dp), 
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                    }
                    if (threads.isEmpty()) {
                        Text(
                            "No messages available",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveModelCard(
    modelName: String, 
    isRunning: Boolean,
    isLocalModelPresent: Boolean,
    port: String, 
    onStop: () -> Unit, 
    onStart: () -> Unit,
    onTryIt: () -> Unit,
    isLoading: Boolean = false,
    temperature: Double,
    appLanguage: String = "English",
    ipAddress: String = "Detecting..."
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(
                if (isRunning) listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                else listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f))
            ))
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(8.dp), 
                    color = if (isRunning) Color(0xFF4ADE80) else Color(0xFFE5E7EB), 
                    shape = CircleShape
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRunning) Localization.getString("connected", appLanguage).uppercase() 
                    else Localization.getString("stopped", appLanguage).uppercase(), 
                    color = if (isRunning) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f), 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold
                )
            }
            if (isLoading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    trackColor = (if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    Localization.getString("loading", appLanguage),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isRunning) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChhandaLogo(size = 32, modelName = modelName)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        Localization.getString("active_model", appLanguage),
                        color = (if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if(modelName == "No Active Model") Localization.getString("no_active_model", appLanguage) else modelName, 
                        color = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, 
                        fontSize = 20.sp, 
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            
            Spacer(Modifier.height(20.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(color = (if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, tint = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(12.dp))
                        Text(" $ipAddress", color = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Surface(color = (if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, null, tint = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(12.dp))
                        Text(" " + Localization.getString("server_port", appLanguage) + ": $port", color = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }

                val isTempAlert = temperature >= 40.0
                val infiniteTransition = rememberInfiniteTransition(label = "TempAlert")
                val tempAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "TempPulse"
                )

                Surface(
                    color = if (isTempAlert) Color.Red.copy(alpha = 0.2f * tempAlpha) 
                            else (if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.15f), 
                    shape = RoundedCornerShape(12.dp),
                    border = if (isTempAlert) androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = tempAlpha)) else null
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Thermostat, 
                            null, 
                            tint = if (isTempAlert) Color.Red else (if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer), 
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            " %.1f°C".format(temperature), 
                            color = if (isTempAlert) Color.Red else (if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer), 
                            fontSize = 11.sp, 
                            fontWeight = if (isTempAlert) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isRunning) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.StopCircle, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = Localization.getString("stop_server", appLanguage), 
                                color = MaterialTheme.colorScheme.onPrimary, 
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                    Button(
                        onClick = onTryIt,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Icon(Icons.Default.Bolt, null)
                        Spacer(Modifier.width(8.dp))
                        Text(Localization.getString("try_it", appLanguage), fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = isLocalModelPresent,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = Color.Gray.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(Localization.getString("start_server", appLanguage), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(
    modifier: Modifier, 
    label: String, 
    value: String, 
    icon: ImageVector, 
    isAlert: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "StatAlert")
    val alertAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AlertPulse"
    )

    Surface(
        modifier = modifier.height(110.dp),
        onClick = { onClick?.invoke() },
        color = if (isAlert) Color.Red.copy(alpha = 0.12f * alertAlpha) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(24.dp),
        border = if (isAlert) androidx.compose.foundation.BorderStroke(1.5.dp, Color.Red.copy(alpha = alertAlpha)) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, 
                    null, 
                    tint = if (isAlert) Color.Red else MaterialTheme.colorScheme.primary, 
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    label, 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = if (isAlert) Color.Red.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(12.dp))
            val lines = value.split("\n")
            if (lines.size > 1) {
                Text(
                    lines[0], 
                    fontSize = 18.sp, 
                    fontWeight = FontWeight.Black,
                    color = if (isAlert) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    lines[1], 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Medium,
                    color = if (isAlert) Color.Red.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                Text(
                    value, 
                    fontSize = 20.sp, 
                    fontWeight = FontWeight.Black,
                    color = if (isAlert) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LocalModelItem(
    model: ModelInfo, 
    isServerRunning: Boolean, 
    onActivate: () -> Unit, 
    onStop: () -> Unit, 
    onTryIt: () -> Unit,
    onDelete: () -> Unit,
    appLanguage: String = "English"
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(32.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    color = if (model.isActive && isServerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        Icons.Default.Dns, 
                        null, 
                        tint = if (model.isActive && isServerRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, 
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(model.details, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                if (model.isActive && isServerRunning) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            Localization.getString("active", appLanguage), 
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), 
                            color = MaterialTheme.colorScheme.onPrimaryContainer, 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 10.sp
                        )
                    }
                } else if (model.isActive) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            Localization.getString("ready", appLanguage), 
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), 
                            color = MaterialTheme.colorScheme.onSecondaryContainer, 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.isActive && isServerRunning) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = Localization.getString("stop_server", appLanguage), 
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                    Button(
                        onClick = onTryIt,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Default.Bolt, null, modifier = Modifier.size(16.dp))
                        Text(" " + Localization.getString("try_it", appLanguage), fontSize = 14.sp)
                    }
                } else {
                    Button(
                        onClick = onActivate,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Text(" " + Localization.getString("start_server", appLanguage), fontSize = 14.sp)
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(40.dp)
                ) {
                    IconButton(onClick = onDelete) { 
                        Icon(
                            Icons.Default.Delete, 
                            null, 
                            modifier = Modifier.size(18.dp), 
                            tint = MaterialTheme.colorScheme.error
                        ) 
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadableModelItem(
    model: DownloadModelInfo, 
    progress: Float?, 
    isPaused: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    appLanguage: String = "English"
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (model.isRecommended) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(24.dp),
        border = if (model.isRecommended) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    color = if (model.isRecommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        if (model.isRecommended) Icons.Default.AutoAwesome else Icons.Default.CloudDownload, 
                        null, 
                        tint = if (model.isRecommended) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, 
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        if (model.isRecommended) {
                            Spacer(Modifier.width(8.dp))
                            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    " " + Localization.getString("recommended", appLanguage).uppercase(), 
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), 
                                    color = MaterialTheme.colorScheme.onPrimary, 
                                    fontWeight = FontWeight.Bold, 
                                    fontSize = 8.sp
                                )
                            }
                        }
                    }
                    Text(model.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Localization.getString("size", appLanguage), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.width(4.dp))
                Text(model.size, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                if (progress != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (progress < 1f) {
                                if (progress < 0f) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.width(100.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        progress = progress,
                                        modifier = Modifier.width(100.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            
                            // Buttons
                            if (isPaused) {
                                IconButton(onClick = onResume, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            } else if (progress < 1f) {
                                IconButton(onClick = onPause, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Pause, contentDescription = "Pause", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                            
                            Spacer(Modifier.width(4.dp))
                            
                            IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            }
                        }
                        
                        if (progress < 1f) {
                            Spacer(Modifier.height(4.dp))
                            val text = if (progress < 0f) {
                                if (isPaused) Localization.getString("paused", appLanguage) else Localization.getString("downloading", appLanguage)
                            } else {
                                "${(progress * 100).toInt()}% ${if (isPaused) Localization.getString("paused", appLanguage) else Localization.getString("downloaded", appLanguage)}"
                            }
                            Text(
                                text, 
                                fontSize = 10.sp, 
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                        Text(" " + Localization.getString("download", appLanguage), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

data class ModelInfo(
    val name: String, 
    val details: String, 
    val isActive: Boolean,
    val hasUpdate: Boolean = false
)

data class DownloadModelInfo(
    val name: String, 
    val description: String, 
    val size: String,
    val isRecommended: Boolean = false,
    val hasUpdate: Boolean = false
)

@Composable
fun AssistantConfigSection(
    name: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    config: String
) {
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            androidx.compose.animation.AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = Color.Black.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            config,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionDetailCard(
    title: String, 
    value: String, 
    trailingIcon: ImageVector? = null,
    onIconClick: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    value, 
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (trailingIcon != null && onIconClick != null) {
                    IconButton(onClick = onIconClick, modifier = Modifier.size(24.dp)) {
                        Icon(trailingIcon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun GatewayDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    viewModel: com.chhanda.ai.presentation.viewmodel.SystemViewModel,
    displayPort: Int,
    tunnelUrl: String
) {
    if (!show) return
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val networkIps by viewModel.networkIps.collectAsState()
    val hasNetworkState by viewModel.hasNetwork.collectAsState()
    var isSetupConfirmed by remember { mutableStateOf(false) }
    var isApiKeyMasked by remember { mutableStateOf(true) }
    val showSetup = !hasNetworkState && !isSetupConfirmed

    androidx.compose.ui.window.Dialog(onDismissRequest = { 
        isSetupConfirmed = false
        onDismiss() 
    }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Unified Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.QrCode, 
                        null, 
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (showSetup) "Network Setup" else "Chhanda Gateway", 
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(Modifier.height(16.dp))

                if (showSetup) {
                    // SETUP MODE
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("To share Chhanda AI, please turn on your Mobile Hotspot.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.1f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("1. Open Hotspot Settings.", fontSize = 12.sp)
                                Text("2. Turn on the Hotspot switch.", fontSize = 12.sp)
                                Text("3. Come back and tap 'I've Connected'.", fontSize = 12.sp)
                            }
                        }
                        
                        Spacer(Modifier.height(20.dp))
                        
                        Button(
                            onClick = {
                                try {
                                    val intent = android.content.Intent().apply {
                                        action = "android.settings.TETHER_WIFI_SETTINGS"
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Open Hotspot Settings")
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        TextButton(
                            onClick = { 
                                viewModel.manualRefreshNetwork()
                                if (hasNetworkState) {
                                    isSetupConfirmed = true 
                                } else {
                                    // Try once more after a tiny delay, or just let them through
                                    isSetupConfirmed = true
                                    if (!hasNetworkState) {
                                        android.widget.Toast.makeText(context, "Scanning for network...", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("I've Connected")
                        }
                    }
                } else {
                    // READY MODE
                    val ip by viewModel.localIpAddress.collectAsState()
                    val publicUrl by viewModel.publicUrl.collectAsState()
                    val apiKey by viewModel.apiKey.collectAsState()
                    val currentIp = networkIps.firstOrNull() ?: ip ?: "127.0.0.1"
                    
                    val baseServerUrl = if (publicUrl.isNotBlank()) {
                        publicUrl.removeSuffix("/")
                    } else if (tunnelUrl.isNotEmpty()) {
                        tunnelUrl.removeSuffix("/")
                    } else {
                        "http://$currentIp:$displayPort"
                    }
                    
                    val apiUrl = "$baseServerUrl/v1"
                    
                    val continueConfig = """
                    models:
                      - name: Gemma 4 Local
                        provider: openai
                        model: gemma-4
                        apiBase: $apiUrl
                        apiKey: $apiKey
                        roles: [chat, edit, autocomplete]
                    """.trimIndent()

                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // QR Code
                        Surface(
                            modifier = Modifier.size(160.dp),
                            color = Color.White,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(12.dp)) {
                                val qrIp = when {
                                    networkIps.any { it.startsWith("192.168.43.") || it.startsWith("192.168.44.") } -> {
                                        networkIps.find { it.endsWith(".1") } ?: networkIps.find { it.startsWith("192.168.43.") } ?: networkIps.first()
                                    }
                                    else -> currentIp
                                }
                                val chatUrl = "http://$qrIp:$displayPort?key=$apiKey"
                                val qrBitmap = remember(chatUrl) { QRCodeGenerator.generate(chatUrl, 400) }
                                if (qrBitmap != null) {
                                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Chat QR", modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(20.dp))
                        
                        // API Credentials
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("API URL", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("API URL", apiUrl))
                                    }, modifier = Modifier.size(20.dp)) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(12.dp))
                                    }
                                }
                                Text(apiUrl, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                
                                Spacer(Modifier.height(8.dp))
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("API KEY", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    IconButton(onClick = { isApiKeyMasked = !isApiKeyMasked }, modifier = Modifier.size(20.dp)) {
                                        Icon(if (isApiKeyMasked) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, modifier = Modifier.size(12.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("API Key", apiKey))
                                    }, modifier = Modifier.size(20.dp)) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(12.dp))
                                    }
                                }
                                Text(
                                    if (isApiKeyMasked) "••••••••••••••••" else apiKey, 
                                    fontSize = 11.sp, 
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.clickable { isApiKeyMasked = !isApiKeyMasked }
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        
                        // Continue Config
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Continue Config", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Config", continueConfig))
                                        android.widget.Toast.makeText(context, "Config copied!", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Surface(
                                color = Color.Black.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    continueConfig,
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    TextButton(
                        onClick = { 
                            isSetupConfirmed = false
                            onDismiss() 
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
