package com.chhanda.ai.presentation.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.chhanda.ai.R

@Composable
fun ChhandaSectionHeader(icon: ImageVector, title: String, badge: String = "") {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF2563EB), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (badge.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant, 
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    badge, 
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), 
                    fontSize = 10.sp, 
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ChhandaCard(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor ?: MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
@Composable
fun ChhandaLogo(modifier: Modifier = Modifier, size: Int = 36, modelName: String = "") {
    val logoRes = remember(modelName) {
        val name = modelName.lowercase()
        when {
            name.contains("gemma") || name.contains("google") -> R.drawable.logo_google
            name.contains("llama") || name.contains("meta") -> R.drawable.logo_meta
            name.contains("mistral") || name.contains("mixtral") -> R.drawable.logo_mistral
            name.contains("phi") || name.contains("microsoft") -> R.drawable.logo_microsoft
            name.contains("qwen") || name.contains("alibaba") -> R.drawable.logo_qwen
            name.contains("openai") || name.contains("gpt") -> R.drawable.logo_openai
            name.contains("deepseek") || name.contains("deep seek") -> R.drawable.logo_deepseek
            else -> null
        }
    }

    Surface(
        modifier = modifier.size(size.dp),
        color = Color.White,
        shape = RoundedCornerShape((size / 3.5).dp),
        shadowElevation = if (logoRes != null) 1.dp else 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = logoRes ?: R.drawable.ic_launcher_foreground),
                contentDescription = "Model Logo",
                modifier = Modifier.fillMaxSize(if (logoRes != null) 0.9f else 0.85f)
            )
        }
    }
}
