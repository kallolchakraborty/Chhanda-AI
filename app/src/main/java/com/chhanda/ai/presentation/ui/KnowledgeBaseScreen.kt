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
import androidx.compose.material.icons.automirrored.filled.Article
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
    val vectorDbCapacityBytes by viewModel.vectorDbCapacityBytes.collectAsState()
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
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.CloudUpload, title = Localization.getString("data_ingestion", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    UploadRagCard(
                        appLanguage = appLanguage, 
                        onUpload = { filePickerLauncher.launch(arrayOf(
                            "application/pdf", 
                            "image/*", 
                            "text/plain", 
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // docx
                            "application/msword", // doc
                            "application/vnd.ms-excel", // xls
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // xlsx
                            "audio/*"
                        )) },
                        onConnectUrl = { showUrlDialog = true }
                    )
                }
            }
            
            item {
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.BarChart, title = Localization.getString("stats", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    IndexedStatsCard(appLanguage = appLanguage, allFiles = allFiles)
                }
            }
            
            item {
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.Storage, title = Localization.getString("storage_metrics", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    VectorStorageStatusCard(appLanguage = appLanguage, totalSize = allFiles.sumOf { it.size }, capacityBytes = vectorDbCapacityBytes)
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
            val autoDeleteDays by viewModel.autoDeleteDays.collectAsState()
            val autoDeleteEnabled by viewModel.autoDeleteEnabled.collectAsState()
            
            AlertDialog(
                onDismissRequest = { showConfirmUpload = false },
                title = { Text("Confirm Ingestion") },
                text = { 
                    Column {
                        Text("Are you sure you want to ingest ${selectedUris.size} files into the Knowledge Base?")
                        if (autoDeleteEnabled) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Note: The vector database will be emptied after $autoDeleteDays days. If you want to change then close the window and make the changes at the memory screen.",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
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
                        
                        Text("• 300MB download limit enforced\n• Supports public web pages & Kaggle datasets", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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

        val showInternetWarning by viewModel.showInternetWarning.collectAsState()
        if (showInternetWarning) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissInternetWarning() },
                icon = { Icon(Icons.Default.SignalWifiOff, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("No Internet Connection") },
                text = { Text("URL scraping requires an active internet connection. Please turn on your Wi-Fi or Mobile Data to continue.") },
                confirmButton = {
                    Button(onClick = { 
                        viewModel.dismissInternetWarning()
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                        }
                    }) {
                        Text("Turn on Data")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissInternetWarning() }) {
                        Text("Dismiss")
                    }
                }
            )
        }

        val showLlmServerWarning by viewModel.showLlmServerWarning.collectAsState()
        if (showLlmServerWarning) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissLlmServerWarning() },
                icon = { Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Start LLM Server") },
                text = { Text("Deep scraping/parsing (like Kaggle datasets) requires the on-device AI server to be active. Please start the LLM server from the Dashboard first.") },
                confirmButton = {
                    Button(onClick = { 
                        viewModel.dismissLlmServerWarning()
                        navController.navigate(Screen.Dashboard.route)
                    }) {
                        Text("Go to Dashboard")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissLlmServerWarning() }) {
                        Text("Dismiss")
                    }
                }
            )
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
                        Icons.Default.Upload, 
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
                Icon(Icons.Default.PictureAsPdf, "PDF", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Icon(Icons.Default.Description, "Word", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Icon(Icons.Default.TableChart, "Excel", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Icon(Icons.Default.Image, "Image", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Icon(Icons.Default.Mic, "Audio", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                Icon(Icons.Default.Public, "Web", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun IndexedStatsCard(appLanguage: String, allFiles: List<com.chhanda.ai.data.repository.UploadedFileEntity>) {
    val fileCount = allFiles.size
    val filesThisWeek = allFiles.count { it.timestamp > System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000L }
    val state7DaysAgo = fileCount - filesThisWeek
    val growth = if (state7DaysAgo > 0) {
        (filesThisWeek.toDouble() / state7DaysAgo) * 100
    } else if (filesThisWeek > 0) {
        100.0
    } else {
        0.0
    }
    val growthStr = "%.1f".format(growth)
    
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
                    Text(" $growthStr% growth this week", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}



@Composable
fun VectorStorageStatusCard(appLanguage: String, totalSize: Long, capacityBytes: Long) {
    val totalSizeGB = totalSize.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val limitGB = capacityBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val calculatedProgress = (totalSizeGB / limitGB).toFloat()
    val progress = if (totalSize > 0) calculatedProgress.coerceIn(0.05f, 1f) else 0f
    
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
            val sizeStr = if (totalSize < 1024 * 1024) {
                "%.2f KB".format(totalSize.toDouble() / 1024.0)
            } else if (totalSizeGB < 0.1) {
                "%.2f MB".format(totalSize.toDouble() / (1024.0 * 1024.0))
            } else {
                "%.2f GB".format(totalSizeGB)
            }
            val limitStr = "%.1f".format(limitGB)
            Text("$sizeStr / $limitStr GB " + Localization.getString("used", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
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
    val formatUpper = file.format.uppercase()
    val (icon, color, typeLabel) = when {
        formatUpper.contains("PDF") -> Triple(Icons.Default.PictureAsPdf, Color(0xFFB91C1C), "PDF Document")
        formatUpper.contains("IMAGE") || formatUpper.contains("PNG") || formatUpper.contains("JPG") -> Triple(Icons.Default.Image, Color(0xFF0891B2), "Image")
        formatUpper.contains("AUDIO") || formatUpper.contains("MP3") || formatUpper.contains("WAV") -> Triple(Icons.Default.Mic, Color(0xFF2563EB), "Audio")
        formatUpper.contains("WORD") || formatUpper.contains("DOC") -> Triple(Icons.Default.Description, Color(0xFF4F46E5), "Word Document")
        formatUpper.contains("EXCEL") || formatUpper.contains("XLS") -> Triple(Icons.Default.TableChart, Color(0xFF16A34A), "Excel Sheet")
        formatUpper.contains("WEB") || formatUpper.contains("URL") -> Triple(Icons.Default.Public, Color(0xFFEA580C), "Website")
        formatUpper.contains("TXT") -> Triple(Icons.AutoMirrored.Filled.Article, Color(0xFF6B7280), "Text File")
        else -> Triple(Icons.Default.Description, Color.Gray, file.format)
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
                color = color.copy(alpha = 0.2f),
                shape = androidx.compose.foundation.shape.CircleShape
            ) {
                Icon(
                    imageVector = icon, 
                    contentDescription = typeLabel, 
                    tint = color, 
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name, 
                    fontWeight = FontWeight.Bold, 
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                val sizeStr = if (file.size < 1024 * 1024) {
                    "%.2f KB".format(file.size.toDouble() / 1024.0)
                } else {
                    "%.2f MB".format(file.size.toDouble() / (1024.0 * 1024.0))
                }
                Text("$sizeStr • $typeLabel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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


