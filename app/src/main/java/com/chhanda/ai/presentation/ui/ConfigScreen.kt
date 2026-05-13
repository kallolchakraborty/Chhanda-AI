package com.chhanda.ai.presentation.ui
import android.content.Intent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.chhanda.ai.presentation.ui.components.ChhandaSectionHeader
import com.chhanda.ai.presentation.ui.components.ChhandaCard
import com.chhanda.ai.presentation.ui.components.ChhandaLogo

import androidx.navigation.NavController
import com.chhanda.ai.Screen
import com.chhanda.ai.util.Localization
import androidx.compose.ui.platform.LocalContext


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    navController: NavController,
    viewModel: SystemViewModel = viewModel()
) {
    val isDark by viewModel.darkMode.collectAsState()
    val port by viewModel.serverPort.collectAsState()
    val ctxLength by viewModel.contextLength.collectAsState()
    val hfToken by viewModel.hfToken.collectAsState()
    val maxDevices by viewModel.maxDevices.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val vectorDbCapacityBytes by viewModel.vectorDbCapacityBytes.collectAsState()
    val showRestart by viewModel.showRestartDialog.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val turboQuantEnabled by viewModel.turboQuantEnabled.collectAsState()
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    var tempPort by remember(port) { mutableStateOf(port) }
    var tempHfToken by remember(hfToken) { mutableStateOf(hfToken) }
    var tempApiKey by remember(apiKey) { mutableStateOf(apiKey) }
    var showSaveConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navController.navigate(Screen.Dashboard.route) }) {
                            ChhandaLogo(size = 32)
                        }

                        Spacer(Modifier.width(12.dp))
                        Text(Localization.getString("settings", appLanguage), fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                Spacer(Modifier.height(8.dp))
            }
            
            item {
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.AutoAwesome, title = Localization.getString("appearance", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    ChhandaCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(Localization.getString("dark_mode", appLanguage), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text(Localization.getString("dark_mode_desc", appLanguage), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Switch(checked = isDark, onCheckedChange = { viewModel.toggleDarkMode(it) })
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        Text(Localization.getString("language", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = appLanguage,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                Localization.supportedLanguages.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang) },
                                        onClick = {
                                            viewModel.setAppLanguage(lang)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        Text("TTS Voice", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        
                        val availableVoices by viewModel.availableVoices.collectAsState()
                        val selectedVoice by viewModel.selectedVoice.collectAsState()
                        var voiceExpanded by remember { mutableStateOf(false) }
                        
                        ExposedDropdownMenuBox(
                            expanded = voiceExpanded,
                            onExpandedChange = { voiceExpanded = !voiceExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedVoice,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = voiceExpanded,
                                onDismissRequest = { voiceExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("System Default") },
                                    onClick = {
                                        viewModel.setSelectedVoice("Default")
                                        voiceExpanded = false
                                    }
                                )
                                for (voice in availableVoices) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(voice)
                                                IconButton(
                                                    onClick = { 
                                                        viewModel.playSample(voice, appLanguage)
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Play Sample",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSelectedVoice(voice)
                                            voiceExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            item {
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.Dns, title = Localization.getString("network_settings", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    ChhandaCard {
                        Text(Localization.getString("server_port", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tempPort,
                            onValueChange = { tempPort = it },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        
                        Spacer(Modifier.height(24.dp))
                        Text(Localization.getString("context_length", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(Localization.getString("context_length_desc", appLanguage), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Slider(
                            value = (ctxLength.toFloat() / 32768f).coerceIn(0f, 1f),
                            onValueChange = { viewModel.setContextLength((it * 32768).toInt()) }
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("102", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                                Text("$ctxLength tokens", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            Text("32,768", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("TurboQuant", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("Enable KV-cache compression to reduce memory use during long-context inference.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Switch(
                                checked = turboQuantEnabled,
                                onCheckedChange = { viewModel.toggleTurboQuant(it) }
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        Text(Localization.getString("max_devices", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(Localization.getString("max_devices_desc", appLanguage), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Slider(
                            value = (maxDevices.toFloat() / 20f).coerceIn(0f, 1f),
                            onValueChange = { viewModel.setMaxDevices((it * 20).toInt().coerceAtLeast(1)) }
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                                Text("$maxDevices devices", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            Text("20", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }

                        Spacer(Modifier.height(24.dp))
                        val capacityGb = vectorDbCapacityBytes.toDouble() / (1024 * 1024 * 1024)
                        Text(Localization.getString("vector_db_capacity", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Automatically managed (1GB minimum, up to 15% of remaining storage).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(12.dp))
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                            Text(String.format(java.util.Locale.US, "Current Limit: %.1f GB", capacityGb), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
            
            item {
                Column {
                    ChhandaSectionHeader(icon = Icons.Default.DeleteSweep, title = "Auto Delete Settings")
                    Spacer(Modifier.height(12.dp))
                    AutoDeleteSettingsCard(viewModel = viewModel, appLanguage = appLanguage)
                }
            }

            item {
                Column {
                    var isKeyVisible by remember { mutableStateOf(false) }

                    ChhandaSectionHeader(icon = Icons.Default.Lock, title = Localization.getString("security", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    ChhandaCard {
                        Text(Localization.getString("api_key", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(Localization.getString("api_key_desc", appLanguage), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tempApiKey,
                            onValueChange = { tempApiKey = it },
                            visualTransformation = if (isKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = { 
                                Row {
                                    IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                                        Icon(if (isKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null)
                                    }
                                    IconButton(onClick = { 
                                        val newKey = "CH-${java.util.UUID.randomUUID().toString().take(8).uppercase()}"
                                        tempApiKey = newKey
                                    }) {
                                        Icon(Icons.Default.Refresh, null)
                                    }
                                    IconButton(onClick = { 
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(tempApiKey))
                                    }) {
                                        Icon(Icons.Default.ContentCopy, null)
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        
                        Spacer(Modifier.height(8.dp))

                        Spacer(Modifier.height(24.dp))
                        Text(Localization.getString("hf_token", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(Localization.getString("hf_token_desc", appLanguage), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tempHfToken,
                            onValueChange = { tempHfToken = it },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            placeholder = { Text("hf_...") },
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(tempHfToken)) }) {
                                        Icon(Icons.Default.ContentCopy, null)
                                    }
                                    IconButton(onClick = { 
                                        clipboardManager.getText()?.text?.let { tempHfToken = it }
                                    }) {
                                        Icon(Icons.Default.ContentPaste, null)
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
            
            item {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { showSaveConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text(Localization.getString("save_changes", appLanguage), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(40.dp))
            }
        }

        if (showSaveConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showSaveConfirmDialog = false },
                title = { Text("Confirm Changes") },
                text = { Text("Are you sure you want to save the changes?") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setServerPort(tempPort)
                        viewModel.setHfToken(tempHfToken)
                        viewModel.setApiKey(tempApiKey)
                        showSaveConfirmDialog = false
                    }) {
                        Text("Confirm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        }

        if (showRestart) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissRestartDialog() },
                title = { Text(Localization.getString("restart_required", appLanguage)) },
                text = { Text(Localization.getString("restart_msg", appLanguage)) },
                confirmButton = {
                    TextButton(onClick = { 
                        viewModel.dismissRestartDialog()
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        val componentName = intent?.component
                        val mainIntent = Intent.makeRestartActivityTask(componentName)
                        context.startActivity(mainIntent)
                        Runtime.getRuntime().exit(0)
                    }) {
                        Text("OK")
                    }
                }
            )
        }
    }

@Composable
fun AutoDeleteSettingsCard(viewModel: com.chhanda.ai.presentation.viewmodel.SystemViewModel, appLanguage: String) {
    val autoDeleteDays by viewModel.autoDeleteDays.collectAsState()
    val autoDeleteEnabled by viewModel.autoDeleteEnabled.collectAsState()
    
    ChhandaCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto Delete", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Automatically clear old vector data", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Switch(
                checked = autoDeleteEnabled,
                onCheckedChange = { viewModel.setAutoDeleteEnabled(it) }
            )
        }
        
        if (autoDeleteEnabled) {
            Spacer(Modifier.height(24.dp))
            Text("Delete files older than $autoDeleteDays days", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Slider(
                value = autoDeleteDays.toFloat(),
                onValueChange = { viewModel.setAutoDeleteDays(it.toInt()) },
                valueRange = 1f..30f,
                steps = 29
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1 day", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Text("30 days", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}
