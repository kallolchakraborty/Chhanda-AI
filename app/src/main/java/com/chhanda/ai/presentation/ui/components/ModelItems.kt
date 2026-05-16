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
    onActivate: () -> Unit, 
    onStop: () -> Unit,
    onTryIt: () -> Unit,
    onDelete: () -> Unit,
    appLanguage: String = "English"
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(20.dp)
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
                    Text(model.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "ACTIVE", 
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                fontSize = 8.sp, 
                                fontWeight = FontWeight.Black, 
                                color = MaterialTheme.colorScheme.primary
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
            
            Row {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onTryIt) {
                    Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                if (isServerRunning && model.isActive) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.StopCircle, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                } else {
                    IconButton(onClick = onActivate) {
                        Icon(if (model.isActive) Icons.Default.CheckCircle else Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
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
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(Localization.getString("download", appLanguage), fontSize = 11.sp)
                    }
                }
            }
            
            if (progress != null) {
                Spacer(Modifier.height(16.dp))
                
                // Progress Bar with smooth animation and pulse effect
                Box(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
                
                Spacer(Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status Badge
                    Surface(
                        color = (if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary).copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            if (isPaused) Localization.getString("paused", appLanguage).uppercase()
                            else "${(progress * 100).toInt()}% " + Localization.getString("downloading", appLanguage).uppercase(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                    }

                    // Telemetry Row (Speed and Size)
                    if (!isPaused) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val context = androidx.compose.ui.platform.LocalContext.current
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

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    speedStr,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "$downloadedStr / $totalStr",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
