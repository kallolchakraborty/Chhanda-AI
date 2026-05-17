package com.chhanda.ai.presentation.ui
import android.content.Intent
import android.util.Log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chhanda.ai.presentation.ui.components.ChhandaSectionHeader
import com.chhanda.ai.presentation.ui.components.ChhandaCard
import com.chhanda.ai.presentation.ui.components.ChhandaLogo

import androidx.navigation.NavController
import com.chhanda.ai.Screen
import com.chhanda.ai.util.Localization
import androidx.compose.ui.platform.LocalContext


import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    navController: NavController,
    viewModel: SystemViewModel = hiltViewModel(),
    healthViewModel: com.chhanda.ai.presentation.viewmodel.SystemHealthViewModel = hiltViewModel()
) {
    val isDark by viewModel.darkMode.collectAsStateWithLifecycle()
    val port by viewModel.serverPort.collectAsStateWithLifecycle()
    val ctxLength by viewModel.contextLength.collectAsStateWithLifecycle()
    val hfToken by viewModel.hfToken.collectAsStateWithLifecycle()
    val thinkingModeEnabled by viewModel.thinkingModeEnabled.collectAsStateWithLifecycle()
    val isThinkingSupported by viewModel.isThinkingSupported.collectAsStateWithLifecycle()
    val finalThinkingEnabled = thinkingModeEnabled && isThinkingSupported
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val vectorDbCapacityBytes by viewModel.vectorDbCapacityBytes.collectAsStateWithLifecycle()
    val showRestart by viewModel.showRestartDialog.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val maxDevices by viewModel.maxDevices.collectAsStateWithLifecycle()
    val turboQuantEnabled by viewModel.turboQuantEnabled.collectAsStateWithLifecycle()
    val ragEnabled by viewModel.ragEnabled.collectAsStateWithLifecycle()
    val privacyShieldEnabled by viewModel.privacyShieldEnabled.collectAsStateWithLifecycle()
    val appSecurityEnabled by viewModel.appSecurityEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val hapticManager = remember { com.chhanda.ai.util.HapticManager(context) }

    var tempPort by remember(port) { mutableStateOf(port) }
    var tempHfToken by remember(hfToken) { mutableStateOf(hfToken) }
    var tempApiKey by remember(apiKey) { mutableStateOf(apiKey) }
    var showSaveConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navController.navigate(Screen.Dashboard.route) }) {
                            ChhandaLogo(size = 28)
                        }

                        Spacer(Modifier.width(10.dp))
                        Text(Localization.getString("settings", appLanguage), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
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
                            Switch(checked = isDark, onCheckedChange = {
                                hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                viewModel.toggleDarkMode(it)
                            })
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(Localization.getString("thinking_mode", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    if (isThinkingSupported) Localization.getString("thinking_mode_desc", appLanguage)
                                    else "This model does not support native reasoning steps.", 
                                    fontSize = 11.sp, 
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Switch(
                                checked = finalThinkingEnabled, 
                                onCheckedChange = {
                                    hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                    viewModel.setThinkingModeEnabled(it)
                                },
                                enabled = isThinkingSupported
                            )
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
                                            hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.HEAVY_CLICK)
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
                        
                        val availableVoices by viewModel.availableVoices.collectAsStateWithLifecycle()
                        val selectedVoice by viewModel.selectedVoice.collectAsStateWithLifecycle()
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
                                for (voice in availableVoices) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(voice, modifier = Modifier.weight(1f))
                                                IconButton(
                                                    onClick = {
                                                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.HEAVY_CLICK)
                                                        viewModel.playSample(voice, appLanguage)
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Play $voice sample",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.HEAVY_CLICK)
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
                            onValueChange = {
                                val newLength = (it * 32768).toInt()
                                val currentInt = ctxLength.toIntOrNull() ?: 2048
                                if (newLength / 1000 != currentInt / 1000) {
                                    hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                }
                                viewModel.setContextLength(newLength)
                            }
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
                                onCheckedChange = {
                                    hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                    viewModel.toggleTurboQuant(it)
                                }
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        Text(Localization.getString("max_devices", appLanguage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(Localization.getString("max_devices_desc", appLanguage), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Slider(
                            value = (maxDevices.toFloat() / 20f).coerceIn(0f, 1f),
                            onValueChange = {
                                val newDevices = (it * 20).toInt().coerceAtLeast(1)
                                if (newDevices != maxDevices) {
                                    hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                }
                                viewModel.setMaxDevices(newDevices)
                            }
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Vector Database (RAG)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("Enable long-term memory via localized vector storage. Disable to save memory/RAM.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Switch(
                                checked = ragEnabled,
                                onCheckedChange = {
                                    hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                    viewModel.toggleRag(it)
                                }
                            )
                        }

                        if (ragEnabled) {
                            Spacer(Modifier.height(12.dp))
                            Text("Automatically managed (1GB minimum, up to 15% of remaining storage).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Spacer(Modifier.height(8.dp))
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                                Text(String.format(java.util.Locale.US, "Current Limit: %.1f GB", capacityGb), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
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
                    ChhandaSectionHeader(icon = Icons.Default.CloudSync, title = "Cloud Sync & Backup")
                    Spacer(Modifier.height(12.dp))
                    CloudSyncCard(viewModel = viewModel)
                }
            }

            item {
                Column {
                    var isKeyVisible by remember { mutableStateOf(false) }

                    ChhandaSectionHeader(icon = Icons.Default.Lock, title = Localization.getString("security", appLanguage))
                    Spacer(Modifier.height(12.dp))
                    ChhandaCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Privacy Shield", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("Automatically redact Emails, Phone numbers, and Credit Cards from AI responses.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Switch(
                                checked = privacyShieldEnabled,
                                onCheckedChange = {
                                    hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                    viewModel.setPrivacyShieldEnabled(it)
                                }
                            )
                        }

                        Spacer(Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("App Authentication", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("Require biometric fingerprint or face lock when opening the app.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Switch(
                                checked = appSecurityEnabled,
                                onCheckedChange = {
                                    hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                    viewModel.setAppSecurityEnabled(it)
                                }
                            )
                        }
                        
                        Spacer(Modifier.height(24.dp))
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
                                    IconButton(onClick = {
                                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                        isKeyVisible = !isKeyVisible
                                    }) {
                                        Icon(if (isKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = if (isKeyVisible) "Hide API Key" else "Show API Key")
                                    }
                                    IconButton(onClick = { 
                                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.HEAVY_CLICK)
                                        val newKey = "CH-${java.util.UUID.randomUUID().toString().take(8).uppercase()}"
                                        tempApiKey = newKey
                                    }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Regenerate API Key")
                                    }
                                    IconButton(onClick = { 
                                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(tempApiKey))
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy API Key")
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
                                    IconButton(onClick = {
                                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(tempHfToken))
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy HF Token")
                                    }
                                    IconButton(onClick = { 
                                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                                        clipboardManager.getText()?.text?.let { tempHfToken = it }
                                    }) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste HF Token")
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )

                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = {
                                hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.ERROR_PULSE)
                                viewModel.revokeAllSessions()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Icon(Icons.Default.Security, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Revoke All Sessions", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Emergency: Rotate API key and disconnect all active remote clients.",
                            modifier = Modifier.padding(top = 8.dp),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            
            item {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.HEAVY_CLICK)
                        showSaveConfirmDialog = true
                    },
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
                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.SUCCESS_DOUBLE_TAP)
                        viewModel.setServerPort(tempPort)
                        viewModel.setHfToken(tempHfToken)
                        viewModel.setApiKey(tempApiKey)
                        showSaveConfirmDialog = false
                    }) {
                        Text("Confirm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                        showSaveConfirmDialog = false
                    }) {
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
                        (context as? android.app.Activity)?.recreate()
                    }) {
                        Text("OK")
                    }
                }
            )
        }

        val showServerWarning by viewModel.showServerRunningWarning.collectAsStateWithLifecycle()
        if (showServerWarning) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissServerRunningWarning() },
                title = { Text("Server Running") },
                text = { Text("Please stop the server before changing the language to ensure all components restart correctly.") },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissServerRunningWarning() }) {
                        Text("OK")
                    }
                }
            )
        }
    }

@Composable
fun AutoDeleteSettingsCard(viewModel: com.chhanda.ai.presentation.viewmodel.SystemViewModel, appLanguage: String) {
    val autoDeleteDays by viewModel.autoDeleteDays.collectAsStateWithLifecycle()
    val autoDeleteEnabled by viewModel.autoDeleteEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hapticManager = remember { com.chhanda.ai.util.HapticManager(context) }
    
    ChhandaCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto Delete", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Automatically clear old vector data", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Switch(
                checked = autoDeleteEnabled,
                onCheckedChange = {
                    hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                    viewModel.setAutoDeleteEnabled(it)
                }
            )
        }
        
        if (autoDeleteEnabled) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Retention Period", 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer, 
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "$autoDeleteDays days", 
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), 
                        color = MaterialTheme.colorScheme.onPrimaryContainer, 
                        fontWeight = FontWeight.Bold, 
                        fontSize = 11.sp
                    )
                }
            }
            Text(
                "Delete files older than the specified number of days", 
                fontSize = 11.sp, 
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
            Slider(
                value = (autoDeleteDays.toFloat() / 30f).coerceIn(0f, 1f),
                onValueChange = {
                    val newDays = (it * 30).toInt().coerceAtLeast(1)
                    if (newDays != autoDeleteDays) {
                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)
                    }
                    viewModel.setAutoDeleteDays(newDays)
                }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1 day", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Text("30 days", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun CloudSyncCard(viewModel: com.chhanda.ai.presentation.viewmodel.SystemViewModel) {
    val context = LocalContext.current
    val hapticManager = remember { com.chhanda.ai.util.HapticManager(context) }
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSyncTime.collectAsStateWithLifecycle()
    
    var googleAccount by remember { mutableStateOf<com.google.android.gms.auth.api.signin.GoogleSignInAccount?>(null) }
    
    val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(com.google.android.gms.common.api.Scope(com.google.api.services.drive.DriveScopes.DRIVE_FILE))
        .requestScopes(com.google.android.gms.common.api.Scope(com.google.api.services.drive.DriveScopes.DRIVE_APPDATA))
        .build()
        
    val googleSignInClient = remember { com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso) }
    
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            googleAccount = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
        } catch (e: Exception) {
            Log.e("ConfigScreen", "Google Sign In failed", e)
        }
    }

    LaunchedEffect(Unit) {
        googleAccount = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
    }

    ChhandaCard {
        if (googleAccount == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                Text("Connect to Google Drive", fontWeight = FontWeight.Bold)
                Text("Sync your chat history securely across devices.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.HEAVY_CLICK)
                        launcher.launch(googleSignInClient.signInIntent)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Sign in with Google")
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountCircle, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(googleAccount?.displayName ?: "Google User", fontWeight = FontWeight.Bold)
                    Text(googleAccount?.email ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { 
                    hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.HEAVY_CLICK)
                    googleSignInClient.signOut().addOnCompleteListener { googleAccount = null }
                }) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            
            Spacer(Modifier.height(24.dp))
            
            if (isSyncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
                Spacer(Modifier.height(8.dp))
                Text("Synchronizing data...", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.HEAVY_CLICK)
                            googleAccount?.let { viewModel.backupToCloud(it) }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Backup")
                    }
                    
                    OutlinedButton(
                        onClick = {
                            hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.HEAVY_CLICK)
                            googleAccount?.let { viewModel.restoreFromCloud(it) }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Restore")
                    }
                }
            }
            
            if (lastSync > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Last backup: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastSync))}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(14.dp), tint = Color(0xFF10B981))
                    Spacer(Modifier.width(8.dp))
                    Text("E2E Encrypted via Hardware Keystore", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
