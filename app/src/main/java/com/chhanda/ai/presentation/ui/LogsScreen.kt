package com.chhanda.ai.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.chhanda.ai.presentation.viewmodel.SystemViewModel
import com.chhanda.ai.domain.model.LogEntry
import com.chhanda.ai.presentation.ui.components.ChhandaLogo
import com.chhanda.ai.util.Localization

import androidx.navigation.NavController
import com.chhanda.ai.Screen


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    navController: NavController,
    viewModel: SystemViewModel = viewModel()
) {
    val logs by viewModel.logs.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf("ALL") }
    var sortDescending by remember { mutableStateOf(true) }
    var filterExpanded by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("DATE") }
    var sortExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    
    val selectedLogIds = remember { mutableStateListOf<String>() }
    val isSelectionMode = selectedLogIds.isNotEmpty()
    
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        val logsText = logs.joinToString("\n") { log ->
                            "[${log.timestamp}] [${log.tag}] [${log.status}] ${log.message}"
                        }
                        outputStream.write(logsText.toByteArray())
                    }
                    viewModel.addLog("EXPORT", "Logs exported successfully", "SUCCESS")
                } catch (e: Exception) {
                    viewModel.addLog("EXPORT", "Failed to export logs: ${e.message}", "ERROR")
                }
            }
        }
    )
    
    val filteredLogs = remember(logs, searchQuery, selectedStatus, sortBy, sortDescending) {
        val baseList = if (searchQuery.isBlank()) {
            logs
        } else {
            logs.filter { log ->
                log.tag.contains(searchQuery, ignoreCase = true) ||
                log.message.contains(searchQuery, ignoreCase = true) ||
                log.status.contains(searchQuery, ignoreCase = true)
            }
        }
        
        val filtered = if (selectedStatus == "ALL") {
            baseList
        } else {
            baseList.filter { it.status == selectedStatus }
        }

        val sorted = when(sortBy) {
            "DATE" -> filtered
            "TAG" -> filtered.sortedBy { it.tag }
            "STATUS" -> filtered.sortedBy { it.status }
            else -> filtered
        }

        if (sortDescending) sorted.reversed() else sorted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navController.navigate(Screen.Dashboard.route) }) {
                            ChhandaLogo(size = 32)
                        }

                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (isSelectionMode) "${selectedLogIds.size} selected" else Localization.getString("logs", appLanguage), 
                            fontSize = 22.sp, 
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = { showDeleteSelectedConfirm = true }) {
                            Icon(Icons.Default.Delete, "Delete selected", tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = { selectedLogIds.clear() }) {
                            Icon(Icons.Default.Close, "Cancel selection")
                        }
                    } else {
                        IconButton(onClick = { 
                            if (logs.isNotEmpty()) {
                                showClearConfirm = true
                            }
                        }) {
                            Icon(Icons.Default.DeleteSweep, "Clear all", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                placeholder = { Text(Localization.getString("search_hint", appLanguage)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                },
                shape = RoundedCornerShape(32.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                ),
                singleLine = true
            )
            
            // Filter and Actions Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Dropdown Filter
                Box {
                    Surface(
                        onClick = { filterExpanded = true },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(44.dp).width(120.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (selectedStatus == "ALL") Localization.getString("all_logs", appLanguage) else selectedStatus.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(20.dp))
                        }
                    }

                    DropdownMenu(
                        expanded = filterExpanded,
                        onDismissRequest = { filterExpanded = false }
                    ) {
                        listOf("ALL", "SUCCESS", "WARN", "ERROR").forEach { status ->
                            DropdownMenuItem(
                                text = { Text(if (status == "ALL") Localization.getString("all_logs", appLanguage) else status.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    selectedStatus = status
                                    filterExpanded = false
                                }
                            )
                        }
                    }
                }

                // Sorting Button
                Box {
                    Surface(
                        onClick = { sortExpanded = true },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(44.dp).width(100.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                when(sortBy) {
                                    "DATE" -> "Date"
                                    "TAG" -> "Type"
                                    "STATUS" -> "Status"
                                    else -> "Sort"
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        listOf("DATE", "TAG", "STATUS").forEach { option ->
                            DropdownMenuItem(
                                text = { 
                                    Text(when(option) {
                                        "DATE" -> "Date"
                                        "TAG" -> "Type"
                                        "STATUS" -> "Status"
                                        else -> option
                                    })
                                },
                                onClick = {
                                    sortBy = option
                                    sortExpanded = false
                                }
                            )
                        }
                    }
                }

                // Direction Toggle
                Surface(
                    onClick = { sortDescending = !sortDescending },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (sortDescending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            contentDescription = "Sort Direction",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
                
                Button(
                    onClick = { 
                        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault()).format(java.util.Date())
                        createDocumentLauncher.launch("chhanda_logs_$timestamp.txt") 
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    modifier = Modifier.height(44.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Text(" " + Localization.getString("export", appLanguage), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(Modifier.height(24.dp))
            
            // Live Feed Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    Localization.getString("live_feed", appLanguage), 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), 
                    letterSpacing = 1.sp
                )
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), 
                    shape = RoundedCornerShape(99.dp)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.size(6.dp), color = MaterialTheme.colorScheme.error, shape = CircleShape) {}
                        Text(" " + Localization.getString("live_badge", appLanguage), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(
                    items = filteredLogs,
                    key = { it.id }
                ) { log ->
                    val isSelected = selectedLogIds.contains(log.id)
                    LogItem(
                        log = log,
                        isSelected = isSelected,
                        isSelectionMode = isSelectionMode,
                        onToggleSelection = {
                            if (selectedLogIds.contains(log.id)) {
                                selectedLogIds.remove(log.id)
                            } else {
                                selectedLogIds.add(log.id)
                            }
                        }
                    )
                }
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text(Localization.getString("clear_logs_confirm", appLanguage)) },
                text = { Text(Localization.getString("clear_logs_desc", appLanguage)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearAllLogs()
                            showClearConfirm = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(Localization.getString("confirm", appLanguage))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text(Localization.getString("cancel", appLanguage))
                    }
                }
            )
        }

        if (showDeleteSelectedConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteSelectedConfirm = false },
                title = { Text("Delete ${selectedLogIds.size} logs?") },
                text = { Text("This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteLogs(selectedLogIds.toList())
                            selectedLogIds.clear()
                            showDeleteSelectedConfirm = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(Localization.getString("confirm", appLanguage))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteSelectedConfirm = false }) {
                        Text(Localization.getString("cancel", appLanguage))
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterChip(selected: Boolean, label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, 
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, 
                fontSize = 14.sp, 
                fontWeight = FontWeight.Bold
            )
            if (icon != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    icon, 
                    null, 
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, 
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogItem(
    log: LogEntry,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onToggleSelection: () -> Unit = {}
) {
    val statusColor = when(log.status) {
        "SUCCESS" -> Color(0xFF10B981)
        "WARN" -> Color(0xFFF59E0B)
        "ERROR" -> Color(0xFFEF4444)
        else -> Color(0xFF2563EB)
    }
    val icon = when(log.tag) {
        "SYSTEM" -> Icons.Default.SettingsInputComponent
        "CONFIG" -> Icons.Default.Settings
        "SECURITY" -> Icons.Default.Lock
        "STORAGE" -> Icons.Default.Storage
        else -> Icons.Default.AutoAwesome
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (isSelectionMode) onToggleSelection() },
                onLongClick = { onToggleSelection() }
            ),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
               else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Surface(modifier = Modifier.size(28.dp), color = statusColor.copy(alpha = 0.1f), shape = CircleShape) {
                Icon(icon, null, tint = statusColor, modifier = Modifier.padding(6.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(log.tag, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            log.status, 
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), 
                            fontSize = 9.sp, 
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(log.timestamp, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                Spacer(Modifier.height(4.dp))
                Text(log.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}


