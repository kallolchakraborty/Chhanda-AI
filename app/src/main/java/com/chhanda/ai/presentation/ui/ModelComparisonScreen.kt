package com.chhanda.ai.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chhanda.ai.presentation.viewmodel.SystemViewModel
import com.chhanda.ai.util.Localization
import com.chhanda.ai.presentation.ui.ModelInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelComparisonScreen(
    navController: androidx.navigation.NavController,
    viewModel: SystemViewModel
) {
    val ownedModels by viewModel.ownedModels.collectAsState()
    val sharedModels by viewModel.sharedModels.collectAsState()
    val allModels = (ownedModels + sharedModels).distinctBy { it.name }
    val appLanguage by viewModel.appLanguage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Comparison", fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    "Compare performance and accuracy of your local models to find the best fit for your hardware.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (allModels.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("No models found. Download one to compare.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(allModels) { model ->
                    ComparisonCard(model, appLanguage)
                }
            }
        }
    }
}

@Composable
private fun ComparisonCard(
    model: ModelInfo,
    appLanguage: String
) {
    val modelSize = model.details
    val ramRequirement = when {
        model.name.contains("8B", true) -> "8GB+ RAM"
        model.name.contains("4B", true) -> "4GB+ RAM"
        model.name.contains("2B", true) -> "2GB+ RAM"
        else -> "Varies"
    }
    
    val speedRating = when {
        model.name.contains("2B", true) -> "Lightning Fast (40+ TPS)"
        model.name.contains("4B", true) -> "Balanced (15-25 TPS)"
        model.name.contains("8B", true) -> "Deep Thinking (5-10 TPS)"
        else -> "Moderate"
    }

    val accuracyLabel = when {
        model.name.contains("Gemma", true) -> "High Accuracy / Coding"
        model.name.contains("Llama", true) -> "Creative / Chat"
        model.name.contains("Phi", true) -> "Efficient Reasoning"
        else -> "General Purpose"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ModelTraining, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(model.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (model.isActive) {
                    Spacer(Modifier.weight(1f))
                    AssistChip(
                        onClick = {},
                        label = { Text("Active", fontSize = 10.sp) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    )
                }
            }
            
            Divider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            
            MetricRow("Estimated Speed", speedRating, Icons.Default.Speed, Color(0xFF10B981))
            MetricRow("Accuracy Focus", accuracyLabel, Icons.Default.FactCheck, Color(0xFF3B82F6))
            MetricRow("Disk Size", modelSize, Icons.Default.SdStorage, Color(0xFFF59E0B))
            MetricRow("RAM Requirement", ramRequirement, Icons.Default.Memory, Color(0xFFEC4899))
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}
