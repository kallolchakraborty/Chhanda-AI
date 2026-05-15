package com.chhanda.ai.presentation.ui.chat.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chhanda.ai.util.Localization

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    onStop: () -> Unit,
    onAttach: (android.net.Uri) -> Unit,
    onRefine: () -> Unit,
    appLanguage: String = "English",
    isReadOnly: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { onAttach(it) } }

    if (isReadOnly) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp)
        ) {
            Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Read Only Chat", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(28.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { fileLauncher.launch("*/*") }) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    
                    TextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(Localization.getString("type_message", appLanguage)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        maxLines = 4
                    )
                    
                    if (text.isNotEmpty() && !isGenerating) {
                        IconButton(onClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRefine() 
                        }) {
                            Icon(Icons.Default.AutoAwesome, "Refine", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (isGenerating) onStop() else if (text.isNotBlank()) onSend()
                },
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        if (isGenerating) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
            ) {
                Icon(
                    if (isGenerating) Icons.Default.Stop else Icons.Default.Send,
                    null,
                    tint = if (isGenerating) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
