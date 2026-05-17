package com.chhanda.ai.presentation.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.compose.ui.semantics.*

/**
 * LocalModelItem: UI representation of a model already resident on the device.
 * Senior Note: Uses state-hoisting for all interactions (Activate, Stop, Delete) to 
 * maintain a unidirectional data flow from the parent screen.
 */
@Composable
fun LocalModelItem(
    model: ModelInfo, 
    isServerRunning: Boolean, 
    isModelLoaded: Boolean = false,
    isModelLoading: Boolean = false,
    onActivate: () -> Unit, 
    onStop: () -> Unit,
    onTryIt: () -> Unit,
    onDelete: () -> Unit,
    appLanguage: String = "English"
) {
    GlassBox(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                if (model.isActive) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(24.dp)
                    )
                } else Modifier
            )
            .clip(RoundedCornerShape(24.dp))
            .clickable {
                if (!model.isActive) {
                    onActivate()
                }
            }
            .semantics(mergeDescendants = true) {},
        cornerRadius = 24.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChhandaLogo(
                size = 40,
                modelName = model.name
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatModelDisplayName(model.name), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (model.isMultimodal) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = Color(0xFF673AB7).copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                                Icon(Icons.Default.Visibility, null, modifier = Modifier.size(10.dp), tint = Color(0xFF673AB7))
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    "VISION", 
                                    fontSize = 8.sp, 
                                    fontWeight = FontWeight.Black, 
                                    color = Color(0xFF673AB7)
                                )
                            }
                        }
                    }
                    if (model.isActive) {
                        Spacer(Modifier.width(8.dp))
                        if (isServerRunning && isModelLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            val tintColor = if (isServerRunning && isModelLoaded) {
                                Color(0xFF22C55E) // Bright green for running
                            } else {
                                MaterialTheme.colorScheme.primary // Primary/indigo for selected
                            }
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Active Model",
                                tint = tintColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Text(
                    model.details + " · " + Localization.getString("ready", appLanguage), 
                    fontSize = 11.sp, 
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Delete Button
                Surface(
                    onClick = onDelete,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DeleteOutline, 
                            null, 
                            tint = MaterialTheme.colorScheme.error, 
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Chat/Try It Button
                Surface(
                    onClick = onTryIt,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat, 
                            null, 
                            tint = MaterialTheme.colorScheme.primary, 
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Run/Activate or Stop Button
                val buttonColor = when {
                    isServerRunning && model.isActive -> MaterialTheme.colorScheme.error
                    model.isActive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                }
                val iconVector = when {
                    isServerRunning && model.isActive -> Icons.Default.Stop
                    else -> Icons.Default.PlayArrow
                }
                val iconTint = when {
                    model.isActive -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.primary
                }

                Surface(
                    onClick = if (isServerRunning && model.isActive) onStop else onActivate,
                    color = buttonColor,
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            iconVector, 
                            null, 
                            tint = iconTint, 
                            modifier = Modifier.size(18.dp)
                        )
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
    status: com.chhanda.ai.presentation.viewmodel.DownloadStatus? = null,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    appLanguage: String = "English"
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "DownloadProgress"
    )

    GlassBox(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
        cornerRadius = 24.dp
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
                        Text(formatModelDisplayName(model.name), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (model.isMultimodal) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = Color(0xFF673AB7).copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                                    Icon(Icons.Default.Visibility, null, modifier = Modifier.size(10.dp), tint = Color(0xFF673AB7))
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        "VISION", 
                                        fontSize = 8.sp, 
                                        fontWeight = FontWeight.Black, 
                                        color = Color(0xFF673AB7)
                                    )
                                }
                            }
                        }
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
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(Localization.getString("download", appLanguage), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            if (progress != null) {
                Spacer(Modifier.height(16.dp))
                
                // Progress Bar with smooth animation and pulse effect
                Box(modifier = Modifier.fillMaxWidth()) {
                    val infiniteTransition = rememberInfiniteTransition(label = "downloadShimmer")
                    val shimmerOffset by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "downloadShimmerOffset"
                    )
                    
                    val barColor = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                    val barColorContainer = if (isPaused) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primaryContainer
                    
                    val progressBrush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            barColor,
                            barColorContainer,
                            barColor
                        ),
                        start = androidx.compose.ui.geometry.Offset(-300f + (shimmerOffset * 1000f), 0f),
                        end = androidx.compose.ui.geometry.Offset(shimmerOffset * 1000f, 0f)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedProgress)
                                .clip(RoundedCornerShape(5.dp))
                                .background(progressBrush)
                        )
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    
                    val pct = (animatedProgress * 100).toInt()
                    val speedStr = if (status != null && status.speedBytesPerSec > 0) {
                        android.text.format.Formatter.formatFileSize(context, status.speedBytesPerSec) + "/s"
                    } else {
                        "-- B/s"
                    }
                    
                    val downloadedStr = if (status != null) {
                        android.text.format.Formatter.formatFileSize(context, status.downloadedBytes)
                    } else {
                        "0 B"
                    }
                    
                    val totalStr = if (status != null && status.totalBytes > 0) {
                        android.text.format.Formatter.formatFileSize(context, status.totalBytes)
                    } else {
                        model.size
                    }

                    // 1. Percentage
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.Pause else Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (isPaused) "Paused" else "$pct%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                    }

                    // 2. Speed (Only if not paused)
                    if (!isPaused) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlashOn,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFFFFB300) // Beautiful gold yellow
                            )
                            Text(
                                text = speedStr,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // 3. Storage / Progress Bytes
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "$downloadedStr / $totalStr",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

fun formatModelDisplayName(name: String): String {
    if (name.equals("No Active Model", ignoreCase = true)) return name
    
    // 1. Strip extensions (.litertlm, .bin, .gguf)
    val nameWithoutExt = name.substringBeforeLast('.')
    
    // 2. Beautiful default mappings for known models
    if (nameWithoutExt.contains("Gemma-4-E2B-IT", ignoreCase = true) || nameWithoutExt.contains("gemma-4-E2B-it", ignoreCase = true)) {
        return "Gemma-4 E2B IT"
    }
    if (nameWithoutExt.contains("Gemma-4-E4B-IT", ignoreCase = true) || nameWithoutExt.contains("gemma-4-E4B-it", ignoreCase = true)) {
        return "Gemma-4 E4B IT"
    }

    // 3. Otherwise clean up hyphens and underscores
    return nameWithoutExt
        .replace("-", " ")
        .replace("_", " ")
        .split(" ")
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }
}
