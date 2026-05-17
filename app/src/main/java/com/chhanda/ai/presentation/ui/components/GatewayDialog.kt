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
    var showConfirmationPrompt by remember { mutableStateOf(false) }
    val showSetup = !hasNetworkState && !isSetupConfirmed

    androidx.compose.ui.window.Dialog(onDismissRequest = { 
        isSetupConfirmed = false
        showConfirmationPrompt = false
        onDismiss() 
    }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth().wrapContentHeight()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Unified Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.QrCode, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (showSetup) "Network Setup" else "Node Connectivity", 
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onDismiss() }) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(20.dp))
                    }
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
                        
                        Spacer(Modifier.height(24.dp))
                        
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
                        
                        Spacer(Modifier.height(12.dp))
                        
                        TextButton(
                            onClick = { 
                                viewModel.manualRefreshNetwork()
                                showConfirmationPrompt = true
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

                    val displayName = if (lowerName.contains("gemma")) "Gemma 4 (Local)" else "$activeModelName (Local)"
                    val modelIdToUse = if (lowerName.contains("gemma")) "gemma-4-e2b" else modelId
                    val templateToUse = if (lowerName.contains("gemma")) "chatml" else template
                    val useToolCallingToUse = useToolCalling || lowerName.contains("gemma")

                    val continueConfig = """
                    |name: Local Config
                    |version: 1.0.0
                    |schema: v1
                    |
                    |models:
                    |  - name: "Chhanda: $displayName"
                    |    provider: "openai"
                    |    model: "$modelIdToUse"
                    |    apiBase: "$baseServerUrl/v1"
                    |    apiKey: "$apiKey"
                    |    template: "$templateToUse"
                    |    useToolCalling: $useToolCallingToUse  # Enables file creation/edits
                    |
                    |tabAutocompleteModel:
                    |  name: "Chhanda Autocomplete"
                    |  provider: "openai"
                    |  model: "$modelIdToUse"
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
                        // QR Code Section
                        Surface(
                            modifier = Modifier.size(140.dp),
                            color = Color.White,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                                val qrIp = when {
                                    networkIps.any { it.startsWith("192.168.43.") || it.startsWith("192.168.44.") } -> {
                                        networkIps.find { it.endsWith(".1") } ?: networkIps.find { it.startsWith("192.168.43.") } ?: networkIps.first()
                                    }
                                    else -> currentIp
                                }
                                val chatUrl = remember(qrIp, displayPort, apiKey) {
                                    viewModel.getQrCodeUrl(qrIp, displayPort)
                                }
                                val qrBitmap = remember(chatUrl) { QRCodeGenerator.generate(chatUrl, 400) }
                                if (qrBitmap != null) {
                                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Chat QR", modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        
                        // API Credentials — Symmetrical Layout
                        CredentialSection(
                            title = "API URL",
                            value = apiUrl,
                            verticalBarColor = Color(0xFF4CAF50),
                            onCopy = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("API URL", apiUrl))
                            }
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        
                        CredentialSection(
                            title = "API KEY",
                            value = apiKey,
                            verticalBarColor = Color(0xFFFFC107),
                            isMaskable = true,
                            isMasked = isApiKeyMasked,
                            onToggleMask = { isApiKeyMasked = !isApiKeyMasked },
                            onCopy = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("API Key", apiKey))
                            }
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        
                        CredentialSection(
                            title = "CONTINUE CONFIG (YAML)",
                            value = continueConfig,
                            verticalBarColor = Color(0xFF2196F3),
                            isCode = true,
                            onCopy = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Config", continueConfig))
                            }
                        )
                    }
                    
                    Spacer(Modifier.height(24.dp))
                    
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }

    if (showConfirmationPrompt) {
        AlertDialog(
            onDismissRequest = { showConfirmationPrompt = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Wifi, 
                        contentDescription = null, 
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Confirm Hotspot Active", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                Text(
                    "Please confirm that the other user has successfully connected to your mobile hotspot.\n\n" +
                    "Once confirmed, the Node Connectivity window will open, displaying the QR code for them to scan and start chatting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmationPrompt = false
                        viewModel.manualRefreshNetwork()
                        isSetupConfirmed = true
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Confirm & Show QR")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmationPrompt = false }
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
fun CredentialSection(
    title: String,
    value: String,
    verticalBarColor: Color,
    onCopy: () -> Unit,
    isMaskable: Boolean = false,
    isMasked: Boolean = false,
    isCode: Boolean = false,
    onToggleMask: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title, 
                fontSize = 10.sp, 
                fontWeight = FontWeight.Black, 
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
            if (isMaskable) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (isMasked) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    null,
                    modifier = Modifier.size(12.dp).clickable { onToggleMask() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        
        Spacer(Modifier.height(6.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // High-visibility vertical bar for symmetry
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(color = verticalBarColor, shape = RoundedCornerShape(2.dp))
            )
            
            Spacer(Modifier.width(12.dp))
            
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp),
                modifier = if (isCode) {
                    Modifier.weight(1f).height(180.dp)
                } else {
                    Modifier.weight(1f).heightIn(min = 44.dp)
                }
            ) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        if (isMasked) "••••••••••••••••" else value,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        lineHeight = 15.sp,
                        maxLines = if (isCode) Int.MAX_VALUE else 2,
                        modifier = if (isCode) Modifier.verticalScroll(rememberScrollState()) else Modifier
                    )
                }
            }
            
            Spacer(Modifier.width(8.dp))
            
            // Vertically aligned copy icon
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy, 
                    null, 
                    modifier = Modifier.size(18.dp), 
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
