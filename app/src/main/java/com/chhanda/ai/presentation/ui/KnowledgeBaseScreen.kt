package com.chhanda.ai.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.lifecycle.viewmodel.compose.viewModel
import com.chhanda.ai.presentation.viewmodel.SystemViewModel
import com.chhanda.ai.presentation.ui.ModelInfo
import androidx.navigation.NavController
import androidx.compose.foundation.clickable
import com.chhanda.ai.Screen

import com.chhanda.ai.presentation.ui.components.ChhandaSectionHeader
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.chhanda.ai.domain.usecase.DocType
import com.chhanda.ai.presentation.ui.components.ChhandaCard
import com.chhanda.ai.presentation.ui.components.ChhandaLogo
import com.chhanda.ai.util.Localization

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(
    navController: NavController,
    viewModel: SystemViewModel = viewModel()
) {
    val ownedModels by viewModel.ownedModels.collectAsState()
    val allFiles by viewModel.allFiles.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    var showAllFiles by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var showConfirmUpload by remember { mutableStateOf(false) }

    val isIngesting by viewModel.isIngesting.collectAsState()
    val ingestionProgress by viewModel.ingestionProgress.collectAsState()
    val ingestionMessage by viewModel.ingestionMessage.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                selectedUris = uris
                showConfirmUpload = true
            }
        }
    )

    val activeModelName = remember(ownedModels) {
        ownedModels.firstOrNull { it.isActive }?.name ?: Localization.getString("no_active_model", appLanguage)
    }
    val context = LocalContext.current
    
    var showResetDialog by remember { mutableStateOf(false) }
    var modelToDelete by remember { mutableStateOf<String?>(null) }
    
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlLabel by remember { mutableStateOf("") }
    var urlLink by remember { mutableStateOf("") }
    var showUrlScrapingConfirm by remember { mutableStateOf(false) }
    
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navController.navigate(Screen.Dashboard.route) }) {
                            ChhandaLogo(size = 32)
                        }

                        Spacer(Modifier.width(12.dp))
                        Text(Localization.getString("memory", appLanguage), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(Localization.getString("data_management", appLanguage), fontSize = 12.sp, color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(Localization.getString("models", appLanguage), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 8.dp))
                
                TargetContextSelector(
                    ownedModels = ownedModels,
                    activeModelName = activeModelName,
                    appLanguage = appLanguage,
                    onModelSelected = { viewModel.activateModel(it) }
                )
            }
            
            item {
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.CloudUpload, title = Localization.getString("data_ingestion", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    UploadRagCard(
                        appLanguage = appLanguage, 
                        onUpload = { filePickerLauncher.launch(arrayOf("application/pdf", "image/*", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "audio/*")) },
                        onConnectUrl = { showUrlDialog = true }
                    )
                }
            }
            
            item {
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.BarChart, title = Localization.getString("stats", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    IndexedStatsCard(appLanguage = appLanguage, fileCount = allFiles.size)
                }
            }
            
            item {
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.Storage, title = Localization.getString("storage_metrics", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    VectorStorageStatusCard(appLanguage = appLanguage, totalSize = allFiles.sumOf { it.size })
                }
            }
            
            if (allFiles.isNotEmpty()) {
                item {
                    RecentUploadsSectionHeader(
                        appLanguage = appLanguage, 
                        onViewArchive = { showAllFiles = !showAllFiles }, 
                        isExpanded = showAllFiles,
                        isVisible = allFiles.size > 10
                    )
                }
                
                val displayFiles = if (showAllFiles || allFiles.size <= 10) allFiles else allFiles.take(3)
                items(count = displayFiles.size) { index ->
                    val file = displayFiles[index]
                    RagFileItem(
                        file = file, 
                        onDelete = { viewModel.deleteFiles(listOf(file.id)) },
                        onClick = { viewModel.openFile(file) }
                    )
                }
            }
            
            item {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { showResetDialog = true },
                    enabled = !isIngesting,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))
                ) {
                    Icon(Icons.Default.DeleteForever, null)
                    Spacer(Modifier.width(8.dp))
                    Text(Localization.getString("wipe_memory", appLanguage), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(80.dp))
            }
        }

        if (showConfirmUpload) {
            AlertDialog(
                onDismissRequest = { showConfirmUpload = false },
                title = { Text("Confirm Ingestion") },
                text = { Text("Are you sure you want to ingest ${selectedUris.size} files into the Knowledge Base?") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.ingestDocuments(selectedUris)
                        showConfirmUpload = false
                    }) { Text("Confirm") }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmUpload = false }) { Text("Cancel") }
                }
            )
        }

        com.chhanda.ai.presentation.ui.components.IngestionProgressDialog(
            isIngesting = isIngesting,
            progress = ingestionProgress,
            message = ingestionMessage,
            onDismiss = { viewModel.dismissIngestionProgress() }
        )

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text(Localization.getString("wipe_memory", appLanguage), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Choose deletion type:", fontWeight = FontWeight.Bold)
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.clearVectorStore()
                                    showResetDialog = false
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(Localization.getString("empty_vector_db", appLanguage), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(4.dp))
                                Text(Localization.getString("empty_vector_db_text", appLanguage), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.clearAllData()
                                    showResetDialog = false
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(Localization.getString("wipe_memory", appLanguage), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(4.dp))
                                Text(Localization.getString("purge_history_text", appLanguage), fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text(Localization.getString("cancel", appLanguage))
                    }
                }
            )
        }

        if (showUrlDialog) {
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text("Connect Website URL", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = urlLabel,
                            onValueChange = { urlLabel = it },
                            label = { Text("Label (e.g., Project Wiki)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = urlLink,
                            onValueChange = { urlLink = it },
                            label = { Text("Website Link (URL)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                IconButton(onClick = {
                                    clipboardManager.getText()?.text?.let { urlLink = it }
                                }) {
                                    Icon(Icons.Default.ContentPaste, "Paste from Clipboard")
                                }
                            }
                        )
                        if (urlLink.contains("youtube.com") || urlLink.contains("youtu.be")) {
                            Text("YouTube scrapping is not allowed.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (urlLink.contains("youtube.com") || urlLink.contains("youtu.be")) return@Button
                            showUrlScrapingConfirm = true
                            showUrlDialog = false
                        },
                        enabled = urlLabel.isNotBlank() && urlLink.isNotBlank() && !(urlLink.contains("youtube.com") || urlLink.contains("youtu.be")),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Scrape")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showUrlScrapingConfirm) {
            AlertDialog(
                onDismissRequest = { showUrlScrapingConfirm = false },
                title = { Text("Confirm Website Scraping") },
                text = { Text("Chhanda will now fetch and index the content from '$urlLink'. This may take a moment depending on the site size.") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.processScrapeUrl(urlLink, urlLabel)
                        showUrlScrapingConfirm = false
                        urlLink = ""
                        urlLabel = ""
                    }) {
                        Text("Confirm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlScrapingConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Background Ingestion Prompt
        val pendingPrompt = viewModel.pendingBackgroundPrompt
        if (pendingPrompt != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissIngestionPrompt() },
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HourglassEmpty, null, tint = Color(0xFF2563EB))
                        Spacer(Modifier.width(8.dp))
                        Text("Processing Time")
                    }
                },
                text = { 
                    Text("This file is quite large and might take more than 30 seconds to index. Would you like to process it in the background? You'll be notified once it's finished.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (pendingPrompt.url != null) {
                                viewModel.processScrapeUrl(pendingPrompt.url, pendingPrompt.label ?: "Website", true)
                            } else {
                                viewModel.processIngestDocuments(pendingPrompt.uris, true)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text("Run in Background")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        if (pendingPrompt.url != null) {
                            viewModel.processScrapeUrl(pendingPrompt.url, pendingPrompt.label ?: "Website", false)
                        } else {
                            viewModel.processIngestDocuments(pendingPrompt.uris, false)
                        }
                    }) {
                        Text("Foreground")
                    }
                }
            )
        }

        // Ingestion Error Handling
        val ingestionError by viewModel.ingestionError.collectAsState()
        if (ingestionError != null) {
            AlertDialog(
                onDismissRequest = { viewModel.clearIngestionError() },
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Ingestion Failed")
                    }
                },
                text = { Text(ingestionError ?: "An unexpected error occurred during indexing.") },
                confirmButton = {
                    Button(onClick = { viewModel.clearIngestionError() }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

@Composable
fun TargetContextSelector(
    ownedModels: List<ModelInfo>,
    activeModelName: String,
    appLanguage: String,
    onModelSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Target\nContext:", 
                fontSize = 11.sp, 
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), 
                lineHeight = 12.sp
            )
            Spacer(Modifier.width(16.dp))
            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = ownedModels.size > 1) { expanded = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer, 
                                shape = CircleShape, 
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome, 
                                    null, 
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer, 
                                    modifier = Modifier.padding(4.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                activeModelName, 
                                fontSize = 13.sp, 
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        if (ownedModels.size > 1) {
                            Icon(
                                Icons.Default.KeyboardArrowDown, 
                                null, 
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    if (ownedModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(Localization.getString("no_downloaded_models", appLanguage)) },
                            onClick = { expanded = false }
                        )
                    } else {
                        ownedModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.name) },
                                onClick = {
                                    onModelSelected(model.name)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UploadRagCard(appLanguage: String, onUpload: () -> Unit, onConnectUrl: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surface)
            // Dashed border simulation
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(32.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.CloudUpload, 
                        null, 
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(Localization.getString("upload_rag", appLanguage), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                Localization.getString("upload_desc", appLanguage),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onUpload,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(Localization.getString("browse_files", appLanguage), fontSize = 12.sp)
                }
                Button(
                    onClick = onConnectUrl,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Connect URL", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LibraryBooks, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun IndexedStatsCard(appLanguage: String, fileCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth().height(160.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background wave pattern simulation
            Icon(
                Icons.Default.AutoAwesome, 
                null, 
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f), 
                modifier = Modifier.align(Alignment.BottomEnd).size(120.dp).offset(x = 20.dp, y = 20.dp)
            )
            
            Column(modifier = Modifier.padding(24.dp)) {
                Text(Localization.getString("total_indexed", appLanguage), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$fileCount", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" " + Localization.getString("files", appLanguage), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TrendingUp, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(" 12% growth this week", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun VectorStorageStatusCard(appLanguage: String, totalSize: Long) {
    val totalSizeGB = totalSize.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val limitGB = 20.0
    val progress = (totalSizeGB / limitGB).toFloat().coerceIn(0f, 1f)
    
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(Localization.getString("vector_storage", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)
            )
            Spacer(Modifier.height(12.dp))
            val sizeStr = if (totalSizeGB < 0.1) {
                "%.2f MB".format(totalSize.toDouble() / (1024.0 * 1024.0))
            } else {
                "%.2f GB".format(totalSizeGB)
            }
            Text("$sizeStr / $limitGB GB " + Localization.getString("used", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
fun RecentUploadsSectionHeader(appLanguage: String, onViewArchive: () -> Unit, isExpanded: Boolean, isVisible: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(Localization.getString("recent_uploads", appLanguage), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (isVisible) {
            TextButton(onClick = onViewArchive) {
                Text(if (isExpanded) "Hide Archive" else "View Archive", color = Color(0xFF1967D2), fontWeight = FontWeight.Bold)
                Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ArrowForward, null, modifier = Modifier.size(16.dp), tint = Color(0xFF1967D2))
            }
        }
    }
}

@Composable
fun RagFileItem(file: com.chhanda.ai.data.repository.UploadedFileEntity, onDelete: () -> Unit, onClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val icon = when (file.format) {
        "PDF" -> androidx.compose.material.icons.Icons.Default.PictureAsPdf
        "IMAGE" -> androidx.compose.material.icons.Icons.Default.Image
        "AUDIO" -> androidx.compose.material.icons.Icons.Default.Mic
        else -> androidx.compose.material.icons.Icons.Default.Description
    }
    val color = when (file.format) {
        "PDF" -> androidx.compose.ui.graphics.Color.Red
        "IMAGE" -> androidx.compose.ui.graphics.Color.Cyan
        "AUDIO" -> androidx.compose.ui.graphics.Color.Blue
        else -> androidx.compose.ui.graphics.Color.Gray
    }
    Surface(
        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                color = color.copy(alpha = 0.15f),
                shape = androidx.compose.foundation.shape.CircleShape
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                val sizeStr = if (file.size < 1024 * 1024) {
                    "%.2f KB".format(file.size.toDouble() / 1024.0)
                } else {
                    "%.2f MB".format(file.size.toDouble() / (1024.0 * 1024.0))
                }
                Text("$sizeStr • ${file.format}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            IconButton(onClick = { showMenu = true }) { 
                Icon(androidx.compose.material.icons.Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete", color = Color.Red) },
                        onClick = {
                            onDelete()
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                    )
                }
            }
        }
    }
}


