package com.chhanda.ai.presentation.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * AmbientBackground: A dynamic, animated mesh-gradient background that
 * creates a "breathing" atmosphere. It dynamically shifts its core hue
 * based on the active AI model to provide visual feedback and a premium feel.
 */
@Composable
fun AmbientBackground(
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.primary
) {
    // Smoothly animate between model colors
    val animatedBaseColor by animateColorAsState(
        targetValue = baseColor,
        animationSpec = tween(1500, easing = FastOutSlowInEasing),
        label = "baseColorAnimation"
    )

    val primaryColor = animatedBaseColor.copy(alpha = 0.15f)
    val secondaryColor = animatedBaseColor.copy(alpha = 0.08f)
    
    val infiniteTransition = rememberInfiniteTransition(label = "ambient")
    
    val offset1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset1"
    )

    val offset2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 150f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset2"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Top-Left Glow
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(primaryColor, Color.Transparent),
                center = Offset(width * 0.1f + offset1, height * 0.1f + offset2),
                radius = width * 1.2f
            )
        )

        // Bottom-Right Glow
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(secondaryColor, Color.Transparent),
                center = Offset(width * 0.9f - offset2, height * 0.9f - offset1),
                radius = width * 1.2f
            )
        )
        
        // Subtle Center Glow for Depth
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(primaryColor.copy(alpha = 0.05f), Color.Transparent),
                center = Offset(width * 0.5f, height * 0.5f),
                radius = width * 0.6f
            )
        )
    }
}
