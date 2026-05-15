package com.chhanda.ai.presentation.ui.components

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chhanda.ai.util.Localization
import kotlinx.coroutines.flow.StateFlow

/**
 * ChhandaSectionHeader: A unified header component for dashboard sections.
 * Ensures consistent typography and iconography across the app.
 */
@Composable
fun ChhandaSectionHeader(
    icon: ImageVector, 
    title: String, 
    badge: String = "",
    badgeColor: Color = MaterialTheme.colorScheme.primary
) {
    Column(modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                title.uppercase(), 
                style = MaterialTheme.typography.labelLarge, 
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary
            )
            if (badge.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Surface(
                    color = badgeColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        badge, 
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 9.sp, 
                        fontWeight = FontWeight.Bold, 
                        color = badgeColor
                    )
                }
            }
        }
    }
}

/**
 * StatCard: A high-density data tile for displaying system metrics.
 */
@Composable
fun StatCard(
    modifier: Modifier = Modifier, 
    label: String, 
    value: StateFlow<String>, 
    icon: ImageVector,
    onClick: (() -> Unit)? = null
) {
    val displayValue by value.collectAsState()
    
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = modifier.height(80.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(displayValue, fontSize = 14.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}


/**
 * ActiveModelCard: The primary control surface for the local AI engine.
 * Senior Note: Implements experimental Shared Element Transitions for seamless 
 * navigation to the Chat interface.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun ActiveModelCard(
    modelName: String, 
    isRunning: Boolean,
    isLocalModelPresent: Boolean,
    port: String, 
    onStop: () -> Unit, 
    onStart: () -> Unit,
    onTryIt: () -> Unit,
    isLoading: Boolean = false,
    temperature: Double,
    thermalStatus: String = "Normal",
    ramUsage: String = "0 MB / 0 MB",
    vectorMemory: String = "0 MB / 1 GB",
    appLanguage: String = "English",
    ipAddress: String = "Detecting...",
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(
                if (isRunning) listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = shimmerAlpha),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
                else listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f))
            ))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Status Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(8.dp), 
                    color = if (isRunning) Color(0xFF4ADE80) else Color(0xFFE5E7EB), 
                    shape = CircleShape
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRunning) Localization.getString("connected", appLanguage).uppercase() 
                    else Localization.getString("stopped", appLanguage).uppercase(), 
                    color = if (isRunning) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f), 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold
                )
            }

            if (isLoading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    trackColor = (if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.2f)
                )
            }

            Spacer(Modifier.height(4.dp))
            
            // Model Info
            Row(verticalAlignment = Alignment.CenterVertically) {
                with(sharedTransitionScope) {
                    ChhandaLogo(
                        size = 32, 
                        modelName = modelName,
                        modifier = Modifier.sharedElement(
                            sharedTransitionScope.rememberSharedContentState(key = "model_logo_$modelName"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        Localization.getString("active_model", appLanguage),
                        color = (if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    with(sharedTransitionScope) {
                        Text(
                            if(modelName == "No Active Model") Localization.getString("no_active_model", appLanguage) else modelName, 
                            color = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, 
                            fontSize = 20.sp, 
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.sharedElement(
                                sharedTransitionScope.rememberSharedContentState(key = "model_name_$modelName"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Unified Telemetry Chip
            val thermalColor = when (thermalStatus) {
                "Normal" -> if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                "Fair", "Serious" -> Color(0xFFFACC15)
                "Critical", "Emergency", "Shutdown" -> Color(0xFFF87171)
                else -> if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
            }

            Surface(
                color = (if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.15f), 
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), 
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // IP Address
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, tint = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(12.dp))
                        Text(" $ipAddress", color = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    
                    // Port
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, null, tint = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(12.dp))
                        Text(" $port", color = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    
                    // Temperature
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Thermostat, null, tint = thermalColor, modifier = Modifier.size(12.dp))
                        Text(" ${temperature.toInt()}°C", color = thermalColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    
                    // RAM
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Memory, null, tint = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(12.dp))
                        Text(" $ramUsage", color = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }


                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Action Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isRunning) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Icon(Icons.Default.StopCircle, null, tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(Localization.getString("stop_server", appLanguage), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onTryIt,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary, contentColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Icon(Icons.Default.Bolt, null)
                        Spacer(Modifier.width(8.dp))
                        Text(Localization.getString("try_it", appLanguage), fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = isLocalModelPresent,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(Localization.getString("start_server", appLanguage), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
