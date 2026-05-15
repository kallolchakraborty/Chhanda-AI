package com.chhanda.ai.presentation.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chhanda.ai.presentation.ui.ModelInfo
import com.chhanda.ai.presentation.ui.DownloadModelInfo
import com.chhanda.ai.util.Localization

/**
 * LocalModelItem: UI representation of a model already resident on the device.
 * Senior Note: Uses state-hoisting for all interactions (Activate, Stop, Delete) to 
 * maintain a unidirectional data flow from the parent screen.
 */
@Composable
fun LocalModelItem(
    model: ModelInfo, 
    isServerRunning: Boolean, 
    onActivate: () -> Unit, 
    onStop: () -> Unit,
    onTryIt: () -> Unit,
    onDelete: () -> Unit,
    appLanguage: String = "English"
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (model.name.contains("gemma", ignoreCase = true)) Icons.Default.AutoAwesome
                        else if (model.name.contains("deepseek", ignoreCase = true)) Icons.Default.Science
                        else Icons.Default.SmartToy,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    model.size + " · " + Localization.getString("ready", appLanguage), 
                    fontSize = 11.sp, 
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Row {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onTryIt) {
                    Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                if (isServerRunning && model.name == "Current Model") { // Simplified logic for demo
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.StopCircle, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                } else {
                    IconButton(onClick = onActivate) {
                        Icon(Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/**
 * DownloadableModelItem: Represents a model available on the HuggingFace hub.
 * Features built-in progress tracking and pause/resume logic.
 */
@Composable
fun DownloadableModelItem(
    model: DownloadModelInfo, 
    progress: Float?,
    isPaused: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    appLanguage: String = "English"
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.CloudDownload, 
                            null, 
                            modifier = Modifier.size(20.dp), 
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (model.isRecommended) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    Localization.getString("recommended", appLanguage).uppercase(), 
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    fontSize = 8.sp, 
                                    fontWeight = FontWeight.Black, 
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Text(
                        Localization.getString("size", appLanguage) + ": ${model.size}", 
                        fontSize = 11.sp, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (progress != null) {
                    Row {
                        IconButton(onClick = if (isPaused) onResume else onPause) {
                            Icon(
                                if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, 
                                null, 
                                tint = MaterialTheme.colorScheme.primary, 
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        }
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(Localization.getString("downloadable_models", appLanguage).take(8), fontSize = 11.sp)
                    }
                }
            }
            
            if (progress != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (isPaused) Localization.getString("paused", appLanguage) 
                        else "${(progress * 100).toInt()}%", 
                        fontSize = 11.sp, 
                        fontWeight = FontWeight.Bold,
                        color = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
