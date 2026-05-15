package com.chhanda.ai.presentation.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
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
import com.chhanda.ai.domain.model.QRCodeGenerator

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
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().wrapContentHeight()
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
                        if (showSetup) "Network Setup" else "Connection Details", 
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
                    
                    val lowerName = activeModelName.lowercase()
                    val modelId = activeModelName.replace(" ", "-").lowercase()
                    
                    val template = when {
                        lowerName.contains("llama") -> "llama3"
                        lowerName.contains("mistral") || lowerName.contains("mixtral") -> "mistral"
                        lowerName.contains("phi") -> "phi3"
                        lowerName.contains("gemma") -> "gemma"
                        lowerName.contains("deepseek") -> "deepseek"
                        else -> "chatml"
                    }
                    
                    val useToolCalling = lowerName.contains("gemma") && 
                        (lowerName.contains("e2b") || lowerName.contains("e4b"))

                    val continueConfig = """
                    |name: Local Config
                    |version: 1.0.0
                    |schema: v1
                    |
                    |models:
                    |  - name: "Chhanda: $activeModelName"
                    |    provider: "openai"
                    |    model: "$modelId"
                    |    apiBase: "$baseServerUrl/v1"
                    |    apiKey: "$apiKey"
                    |    template: "$template"
                    |    useToolCalling: $useToolCalling
                    |
                    |tabAutocompleteModel:
                    |  name: "Chhanda Autocomplete"
                    |  provider: "openai"
                    |  model: "$modelId"
                    |  apiBase: "$baseServerUrl/v1"
                    |  apiKey: "$apiKey"
                    |
                    |contextProviders:
                    |  - name: "code"
                    |  - name: "docs"
                    |  - name: "diff"
                    |  - name: "terminal"
                    |
                    |slashCommands:
                    |  - name: "edit"
                    |    description: "Edit selected code"
                    |  - name: "explain"
                    |    description: "Explain selected code"
                    |  - name: "comment"
                    |    description: "Write comments for selected code"
                    """.trimMargin("|")

                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
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
                        
                        // API Credentials — with visible vertical color bar
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                        ) {
                            // Visible vertical bar
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .background(
                                        color = Color(0xFF4CAF50),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                            Spacer(Modifier.width(12.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // API URL row
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("API URL", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            Text(apiUrl, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                        }
                                        IconButton(onClick = {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("API URL", apiUrl))
                                        }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    
                                    Spacer(Modifier.height(12.dp))
                                    
                                    // API KEY row
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("API KEY", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.width(4.dp))
                                                IconButton(onClick = { isApiKeyMasked = !isApiKeyMasked }, modifier = Modifier.size(18.dp)) {
                                                    Icon(if (isApiKeyMasked) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, modifier = Modifier.size(12.dp))
                                                }
                                            }
                                            Text(
                                                if (isApiKeyMasked) "••••••••••••••••" else apiKey, 
                                                fontSize = 11.sp, 
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                modifier = Modifier.clickable { isApiKeyMasked = !isApiKeyMasked }
                                            )
                                        }
                                        IconButton(onClick = {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("API Key", apiKey))
                                        }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        
                        // Continue Config — with visible vertical color bar
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Continue Config", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Config", continueConfig))
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .background(
                                        color = Color(0xFF2196F3),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                color = Color.Black.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).heightIn(max = 140.dp)
                            ) {
                                Text(
                                    continueConfig,
                                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(8.dp),
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
