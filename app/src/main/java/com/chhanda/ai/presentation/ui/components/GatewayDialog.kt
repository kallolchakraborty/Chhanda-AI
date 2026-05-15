package com.chhanda.ai.presentation.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chhanda.ai.presentation.viewmodel.SystemViewModel
import com.chhanda.ai.util.QRCodeGenerator

/**
 * GatewayDialog: The connectivity portal for external devices.
 * Senior Note: Provides both a QR code for direct mobile access and 
 * API credentials for IDE integration (e.g., Continue.dev).
 */
@Composable
fun GatewayDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    viewModel: SystemViewModel,
    displayPort: Int,
    tunnelUrl: String,
    activeModelName: String
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
                    Icon(Icons.Default.QrCode, null, tint = MaterialTheme.colorScheme.primary)
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
                                    isSetupConfirmed = true
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
                    
                    val modelId = when {
                        activeModelName.contains("E2B", ignoreCase = true) -> "gemma-4-e2b"
                        activeModelName.contains("E4B", ignoreCase = true) -> "gemma-4-e4b"
                        else -> "gemma-4"
                    }

                    val continueConfig = """
                    models:
                      - name: "Chhanda: $activeModelName"
                        provider: "openai"
                        model: "$modelId"
                        apiBase: "$baseServerUrl"
                        apiKey: "$apiKey"
                        template: "chatml"
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
