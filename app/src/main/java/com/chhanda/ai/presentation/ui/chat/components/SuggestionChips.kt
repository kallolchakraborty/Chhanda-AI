package com.chhanda.ai.presentation.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SuggestionChips(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions = listOf(
        SuggestionItem("Summarize", Icons.Default.Description, "Summarize the previous context:"),
        SuggestionItem("Translate", Icons.Default.Translate, "Translate this to Bengali:"),
        SuggestionItem("Rewrite", Icons.Default.Edit, "Rewrite this professionally:"),
        SuggestionItem("Analyze", Icons.Default.Analytics, "Analyze this data:"),
        SuggestionItem("Explain", Icons.Default.Quiz, "Explain this simply:")
    )

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(suggestions) { item ->
            SuggestionChip(item, onSuggestionClick)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionChip(
    item: SuggestionItem,
    onClick: (String) -> Unit
) {
    InputChip(
        selected = false,
        onClick = { onClick(item.prompt) },
        label = { Text(item.label, fontSize = 12.sp) },
        leadingIcon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        shape = RoundedCornerShape(12.dp),
        colors = InputChipDefaults.inputChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = null
    )
}

private data class SuggestionItem(
    val label: String,
    val icon: ImageVector,
    val prompt: String
)
