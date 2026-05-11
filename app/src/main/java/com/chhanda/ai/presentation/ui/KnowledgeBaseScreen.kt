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
    val appLanguage by viewModel.appLanguage.collectAsState()
    val activeModelName = remember(ownedModels) {
        ownedModels.firstOrNull { it.isActive }?.name ?: Localization.getString("no_active_model", appLanguage)
    }
    val context = LocalContext.current
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                val mimeType = context.contentResolver.getType(it)
                val type = when {
                    mimeType?.startsWith("image/") == true -> DocType.IMAGE
                    mimeType == "application/pdf" -> DocType.PDF
                    mimeType == "text/plain" -> DocType.TXT
                    mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocType.WORD
                    else -> DocType.TXT
                }
                viewModel.ingestDocument(it, type)
            }
        }
    )

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
                    UploadRagCard(appLanguage = appLanguage, onUpload = { filePickerLauncher.launch(arrayOf("application/pdf", "image/*", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) })
                }
            }
            
            item {
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.BarChart, title = Localization.getString("stats", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    IndexedStatsCard(appLanguage = appLanguage)
                }
            }
            
            item {
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.Storage, title = Localization.getString("storage_metrics", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    VectorStorageStatusCard(appLanguage = appLanguage)
                }
            }
            
            item {
                RecentUploadsSectionHeader(appLanguage = appLanguage)
            }
            
            items(ragFiles) { file ->
                RagFileItem(file)
            }
            
            item {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.clearAllData() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))
                ) {
                    Icon(Icons.Default.Warning, null)
                    Spacer(Modifier.width(8.dp))
                    Text(Localization.getString("wipe_memory", appLanguage), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(80.dp))
            }
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
fun UploadRagCard(appLanguage: String, onUpload: () -> Unit) {
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
                    modifier = Modifier.width(130.dp).height(48.dp)
                ) {
                    Text(Localization.getString("browse_files", appLanguage))
                }
                Button(
                    onClick = onUpload,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.width(130.dp).height(48.dp)
                ) {
                    Text("Connect URL", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun IndexedStatsCard(appLanguage: String) {
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
                    Text("1,248", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun VectorStorageStatusCard(appLanguage: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(Localization.getString("vector_storage", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = 0.71f,
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)
            )
            Spacer(Modifier.height(12.dp))
            Text("14.2 GB / 20 GB " + Localization.getString("used", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
fun RecentUploadsSectionHeader(appLanguage: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(Localization.getString("recent_uploads", appLanguage), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = {}) {
            Text("View Archive", color = Color(0xFF1967D2), fontWeight = FontWeight.Bold)
            Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(16.dp), tint = Color(0xFF1967D2))
        }
    }
}

@Composable
fun RagFileItem(file: RagFileData) {
    Surface(
        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                color = file.color.copy(alpha = 0.15f),
                shape = CircleShape
            ) {
                Icon(file.icon, null, tint = file.color, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("${file.size} • Updated ${file.time}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

data class RagFileData(val name: String, val size: String, val time: String, val icon: ImageVector, val color: Color)
val ragFiles = listOf(
    RagFileData("quarterly_report_q3.pdf", "12.4 MB", "2 hours ago", Icons.Default.PictureAsPdf, Color.Red),
    RagFileData("interview_recording_final.mp3", "8.1 MB", "5 hours ago", Icons.Default.Mic, Color.Blue),
    RagFileData("infrastructure_schema.png", "2.1 MB", "yesterday", Icons.Default.Image, Color.Cyan)
)
