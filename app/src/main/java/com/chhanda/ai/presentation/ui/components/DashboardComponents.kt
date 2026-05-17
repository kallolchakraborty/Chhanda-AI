package com.chhanda.ai.presentation.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.*
import com.chhanda.ai.util.Localization
import kotlinx.coroutines.flow.StateFlow
import com.chhanda.ai.domain.model.HardwareStatus
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * ChhandaSectionHeader: A unified header component for dashboard sections.
 */
@Composable
fun ChhandaSectionHeader(
    icon: ImageVector, 
    title: String, 
    badge: String = "",
    badgeColor: Color = MaterialTheme.colorScheme.primary
) {
    Column(modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() }) {
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
    val displayValue by value.collectAsStateWithLifecycle()
    
    GlassBox(
        modifier = modifier
            .height(80.dp)
            .semantics(mergeDescendants = true) {}
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        blurRadius = 20f,
        cornerRadius = 20.dp
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
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun ActiveModelCard(
    modelName: String, 
    isRunning: Boolean,
    isLocalModelPresent: Boolean,
    port: String, 
    onStop: () -> Unit, 
    onStart: () -> Unit,
    onTryIt: () -> Unit,
    onSelectModel: () -> Unit,
    isLoading: Boolean = false,
    loadingProgress: Float = 0f,
    temperature: Double,
    thermalStatus: HardwareStatus = HardwareStatus.Normal,
    ramUsage: String = "0 MB / 0 MB",
    vectorMemory: String = "0 MB / 1 GB",
    appLanguage: String = "English",
    ipAddress: String = "Detecting...",
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
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

    val cardModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(32.dp))
        .semantics(mergeDescendants = true) {}

    if (!isRunning) {
        GlassBox(
            modifier = cardModifier,
            blurRadius = 60f,
            cornerRadius = 32.dp
        ) {
            ActiveModelContent(
                isRunning = isRunning,
                isLoading = isLoading,
                loadingProgress = loadingProgress,
                modelName = modelName,
                ipAddress = ipAddress,
                port = port,
                temperature = temperature,
                thermalStatus = thermalStatus,
                ramUsage = ramUsage,
                appLanguage = appLanguage,
                isLocalModelPresent = isLocalModelPresent,
                onStop = onStop,
                onTryIt = onTryIt,
                onStart = onStart,
                onSelectModel = onSelectModel,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else {
        Box(
            modifier = cardModifier.background(Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = shimmerAlpha),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            ))
        ) {
            ActiveModelContent(
                isRunning = isRunning,
                isLoading = isLoading,
                loadingProgress = loadingProgress,
                modelName = modelName,
                ipAddress = ipAddress,
                port = port,
                temperature = temperature,
                thermalStatus = thermalStatus,
                ramUsage = ramUsage,
                appLanguage = appLanguage,
                isLocalModelPresent = isLocalModelPresent,
                onStop = onStop,
                onTryIt = onTryIt,
                onStart = onStart,
                onSelectModel = onSelectModel,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ActiveModelContent(
    isRunning: Boolean,
    isLoading: Boolean,
    loadingProgress: Float,
    modelName: String,
    ipAddress: String,
    port: String,
    temperature: Double,
    thermalStatus: HardwareStatus,
    ramUsage: String,
    appLanguage: String,
    isLocalModelPresent: Boolean,
    onStop: () -> Unit,
    onTryIt: () -> Unit,
    onStart: () -> Unit,
    onSelectModel: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val animatedProgress by animateFloatAsState(
        targetValue = loadingProgress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "modelLoadingProgress"
    )

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background((if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.2f))
                ) {
                    val progressWidthFraction = if (animatedProgress > 0f) animatedProgress else 1f
                    
                    val infiniteTransition = rememberInfiniteTransition(label = "modelLoadShimmer")
                    val shimmerOffset by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "modelLoadShimmerOffset"
                    )

                    val brush = if (animatedProgress == 0f) {
                        Brush.linearGradient(
                            colors = listOf(
                                (if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha = 0.3f),
                                if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                (if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha = 0.3f)
                            ),
                            start = androidx.compose.ui.geometry.Offset(-200f + (shimmerOffset * 800f), 0f),
                            end = androidx.compose.ui.geometry.Offset(shimmerOffset * 800f, 0f)
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(
                                if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                (if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha = 0.6f)
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressWidthFraction)
                            .clip(CircleShape)
                            .background(brush)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "${(animatedProgress * 100).toInt()}%", 
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        
        // Model Info
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = isLocalModelPresent) { onSelectModel() }
                .padding(vertical = 4.dp)
        ) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    with(sharedTransitionScope) {
                        Text(
                            if(modelName == "No Active Model") Localization.getString("no_active_model", appLanguage) else formatModelDisplayName(modelName), 
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
                    if (isLocalModelPresent) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Switch Model",
                            tint = (if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        // Unified Telemetry Chip
        val thermalColor = when (thermalStatus) {
            is HardwareStatus.Normal -> if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
            is HardwareStatus.Throttled -> Color(0xFFFACC15)
            is HardwareStatus.Critical -> Color(0xFFF87171)
        }

        Surface(
            color = (if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.15f), 
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // IP Address
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Link, null, tint = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(12.dp))
                    Text(" $ipAddress", color = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                
                // Port
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, null, tint = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(12.dp))
                    Text(" $port", color = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                
                // Temperature
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Thermostat, null, tint = thermalColor, modifier = Modifier.size(12.dp))
                    Text(" ${temperature.toInt()}°C", color = thermalColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                
                // RAM
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, null, tint = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(12.dp))
                    Text(" $ramUsage", color = if(isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary, 
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                    ),
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
                    enabled = isLocalModelPresent && !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isLoading) Localization.getString("loading_model", appLanguage) 
                        else Localization.getString("start_server", appLanguage), 
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * AnalyticsDashboardSection: Provides professional observability and session summaries.
 */
@Composable
fun AnalyticsDashboardSection(
    tpsHistory: List<Double>,
    ramHistory: List<Double>,
    sessionTokens: Long,
    sessionCostSaved: Double,
    appLanguage: String = "English"
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        // Session Summary
        GlassBox(
            modifier = Modifier.fillMaxWidth(),
            blurRadius = 30f,
            cornerRadius = 24.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Session Summary", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${sessionTokens} Tokens Generated", 
                        style = MaterialTheme.typography.bodyMedium, 
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.End) {
                        Text("EST. SAVINGS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(
                            String.format("$%.2f", sessionCostSaved), 
                            style = MaterialTheme.typography.bodyLarge, 
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryCard(
    modifier: Modifier = Modifier,
    label: String,
    currentValue: String,
    data: List<Double>,
    color: Color
) {
    GlassBox(
        modifier = modifier.height(140.dp),
        blurRadius = 40f,
        cornerRadius = 24.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(currentValue, fontSize = 20.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
            
            Spacer(Modifier.weight(1f))
            
            TelemetryGraph(
                data = data,
                color = color,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            )
        }
    }
}

@Composable
fun TelemetryGraph(
    data: List<Double>,
    color: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas
        
        val width = size.width
        val height = size.height
        val maxVal = (data.maxOrNull() ?: 1.0).coerceAtLeast(0.1).toFloat()
        val stepX = width / (data.size - 1).coerceAtLeast(1)
        
        val path = androidx.compose.ui.graphics.Path()
        val fillPath = androidx.compose.ui.graphics.Path()
        
        data.forEachIndexed { i, value ->
            val x = i * stepX
            val y = height - (value.toFloat() / maxVal * height)
            
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                // Use cubic bezier for smooth curves
                val prevX = (i - 1) * stepX
                val prevY = height - (data[i - 1].toFloat() / maxVal * height)
                path.cubicTo(
                    prevX + stepX / 2, prevY,
                    x - stepX / 2, y,
                    x, y
                )
                fillPath.cubicTo(
                    prevX + stepX / 2, prevY,
                    x - stepX / 2, y,
                    x, y
                )
            }
            
            if (i == data.size - 1) {
                fillPath.lineTo(x, height)
                fillPath.close()
            }
        }
        
        // Draw fill gradient
        drawPath(
            path = fillPath,
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0f))
            )
        )
        
        // Draw line
        drawPath(
            path = path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
        
        // Draw soft glow under the line
        drawPath(
            path = path,
            color = color.copy(alpha = 0.2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 8.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AnalyticsDashboardPreview() {
    MaterialTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            AnalyticsDashboardSection(
                tpsHistory = listOf(2.0, 4.5, 3.8, 5.2, 6.1, 5.5, 7.2),
                ramHistory = listOf(1.2, 1.3, 1.5, 1.4, 1.6, 1.8, 1.7),
                sessionTokens = 1450,
                sessionCostSaved = 12.45
            )
        }
    }
}
