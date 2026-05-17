package com.chhanda.ai.presentation.ui.chat.components

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.*
import com.chhanda.ai.data.repository.MessageEntity

@Composable
fun DocumentDownloadCard(file: java.io.File) {
    val context = LocalContext.current
    val fileName = file.name
    val fileType = when {
        fileName.endsWith(".xlsx") -> "EXCEL"
        fileName.endsWith(".docx") -> "WORD"
        fileName.endsWith(".pdf") -> "PDF"
        else -> "DOCUMENT"
    }
    
    val icon = when(fileType) {
        "EXCEL" -> Icons.Default.TableChart
        "WORD" -> Icons.Default.Description
        "PDF" -> Icons.Default.PictureAsPdf
        else -> Icons.Default.AttachFile
    }

    val iconColor = when(fileType) {
        "EXCEL" -> Color(0xFF22C55E)
        "WORD" -> Color(0xFF6366F1)
        "PDF" -> Color(0xFFEF4444)
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .padding(top = 8.dp)
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .semantics(mergeDescendants = true) {}
            .clickable {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.chhanda.ai.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Download $fileName"))
            },
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = iconColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
                }
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(text = fileName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(text = "$fileType Document", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
            }
            
            Icon(imageVector = Icons.Default.FileDownload, contentDescription = "Download", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun MessageBubble(
    message: MessageEntity,
    tts: android.speech.tts.TextToSpeech?,
    isActiveTts: Boolean = false,
    isGenerating: Boolean = false,
    onTtsToggle: () -> Unit = {},
    onSourceClick: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isUser) {
        RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp)
    } else {
        RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp)
    }

    var isThinkingExpanded by remember { mutableStateOf(false) }
    
    val (parsedThinking, parsedResponse) = parseMessageContent(message.text)
    val finalThinking = message.thinking ?: parsedThinking
    val finalResponse = if (message.thinking != null) message.text else parsedResponse

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = bubbleColor,
            shape = shape,
            shadowElevation = 0.dp,
            modifier = Modifier.semantics(mergeDescendants = true) {}
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (!finalThinking.isNullOrBlank()) {
                    Surface(
                        onClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isThinkingExpanded = !isThinkingExpanded 
                        },
                        color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isThinkingExpanded) Icons.Default.Visibility else Icons.Default.Lightbulb,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isThinkingExpanded) "Hide Thinking" else "Show Thinking",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = isThinkingExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .background(
                                    (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface).copy(alpha = 0.1f), 
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha = 0.7f)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Internal Reasoning",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha = 0.7f)
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = finalThinking,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.8f),
                                    fontStyle = FontStyle.Italic
                                )
                            }
                        }
                    }
                }
                
                var displayText = finalResponse
                val sourcesList = mutableListOf<Pair<String, String>>()
                val sourcesMatch = Regex("\\[Sources:\\s*(.*?)\\]", RegexOption.IGNORE_CASE).find(finalResponse)
                if (sourcesMatch != null) {
                    displayText = finalResponse.replace(sourcesMatch.value, "").trim()
                    val sourcesStr = sourcesMatch.groupValues[1]
                    sourcesStr.split("||").forEach { src ->
                        val trimmedSrc = src.trim()
                        if (trimmedSrc.isNotEmpty()) {
                            if (trimmedSrc.contains("|")) {
                                val parts = trimmedSrc.split("|", limit = 2)
                                if (parts.size == 2) {
                                    sourcesList.add(Pair(parts[0].trim(), parts[1].trim()))
                                }
                            } else {
                                sourcesList.add(Pair(trimmedSrc, trimmedSrc))
                            }
                        }
                    }
                }

                if (displayText.isNotEmpty()) {
                    MarkdownText(
                        text = displayText,
                        color = textColor
                    )
                }
                
                if (sourcesList.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Sources:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        sourcesList.forEach { (title, url) ->
                            val isWeb = url.startsWith("http://") || url.startsWith("https://")
                            val icon = if (isWeb) Icons.Default.Public else Icons.Default.Description
                            val context = androidx.compose.ui.platform.LocalContext.current
                            Surface(
                                onClick = { 
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (isWeb) {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        context.startActivity(intent)
                                    } else {
                                        onSourceClick(url)
                                    }
                                },
                                color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(icon, null, modifier = Modifier.size(12.dp), tint = textColor)
                                    Spacer(Modifier.width(4.dp))
                                    Text(title, fontSize = 10.sp, color = textColor, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Attachment Indicator
        if (isUser && message.attachmentPaths != null) {
            Row(
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                message.attachmentPaths.split(",").forEach { path ->
                    val pathLower = path.lowercase()
                    val icon = when {
                        pathLower.endsWith(".pdf") -> Icons.Default.PictureAsPdf
                        pathLower.endsWith(".docx") || pathLower.endsWith(".doc") -> Icons.Default.Description
                        pathLower.endsWith(".xlsx") || pathLower.endsWith(".xls") -> Icons.Default.TableChart
                        pathLower.contains("image") || pathLower.endsWith(".png") || pathLower.endsWith(".jpg") -> Icons.Default.Image
                        else -> Icons.Default.AttachFile
                    }
                    val label = when {
                        pathLower.endsWith(".pdf") -> "PDF"
                        pathLower.endsWith(".docx") || pathLower.endsWith(".doc") -> "WORD"
                        pathLower.endsWith(".xlsx") || pathLower.endsWith(".xls") -> "EXCEL"
                        pathLower.contains("image") -> "IMAGE"
                        else -> "FILE"
                    }
                    val color = when {
                        pathLower.endsWith(".pdf") -> Color(0xFFEF4444)
                        pathLower.endsWith(".docx") || pathLower.endsWith(".doc") -> Color(0xFF6366F1)
                        pathLower.endsWith(".xlsx") || pathLower.endsWith(".xls") -> Color(0xFF22C55E)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    
                    Surface(
                        color = color.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(icon, null, modifier = Modifier.size(12.dp), tint = color)
                            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
                        }
                    }
                }
            }
        }
        
        if (message.generatedFilePath != null) {
            val file = java.io.File(message.generatedFilePath)
            if (file.exists()) {
                DocumentDownloadCard(file)
            }
        }
        
        if (!isUser && finalResponse.isNotEmpty() && !isGenerating) {
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current
            
            Row(
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        clipboard.setText(AnnotatedString(finalResponse)) 
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
                
                IconButton(
                    onClick = {
                        val intent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, finalResponse)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }

                IconButton(onClick = onTtsToggle, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (isActiveTts) Icons.Default.Stop else Icons.Default.VolumeUp, 
                        null, 
                        modifier = Modifier.size(14.dp),
                        tint = if (isActiveTts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                val sourceLabel = when (message.contextSource) {
                    "Knowledge Base", "Multi-Source", "Attachment" -> "RAG"
                    "Web Fallback" -> "WEB"
                    else -> "LLM"
                }
                val badgeColor = when (sourceLabel) {
                    "RAG" -> MaterialTheme.colorScheme.primary
                    "WEB" -> Color(0xFF10B981) // Emerald Green
                    else -> Color(0xFF6366F1) // Indigo/Blue for LLM
                }
                val badgeIcon = when (sourceLabel) {
                    "RAG" -> Icons.Default.AutoAwesome
                    "WEB" -> Icons.Default.Public
                    else -> Icons.Default.SmartToy
                }

                Surface(
                    color = badgeColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, badgeColor.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(badgeIcon, null, modifier = Modifier.size(10.dp), tint = badgeColor)
                        Text(sourceLabel, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = badgeColor)
                    }
                }

                if (message.tps > 0) {
                    val timeSpent = message.responseTimeMs / 1000.0
                    val tokens = (message.tps * timeSpent).toInt().coerceAtLeast(1)
                    Text(
                        String.format("%d tokens | %.2fs (%.1f t/s)", tokens, timeSpent, message.tps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AttachmentPreview(uri: android.net.Uri, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                uri.lastPathSegment?.take(12) ?: "File", 
                fontSize = 11.sp, 
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(18.dp)) {
                Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
fun ErrorBubble(error: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
        ) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun PersonaSelectionHeader(appLanguage: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        com.chhanda.ai.presentation.ui.components.ChhandaLogo(size = 72)
        Spacer(Modifier.height(20.dp))
        Text(
            text = com.chhanda.ai.util.Localization.getString("welcome_to_chhanda", appLanguage),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Select an expert persona to begin",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun PersonaSelectionGrid(selectedPersona: String?, onPersonaSelect: (String) -> Unit, appLanguage: String) {
    val personas = listOf(
        Triple("Senior Teacher", Icons.Default.School, Color(0xFF6366F1)),
        Triple("Senior Software Engineer", Icons.Default.Code, Color(0xFF10B981)),
        Triple("General Companion", Icons.Default.Face, Color(0xFFF59E0B)),
        Triple("Friend", Icons.Default.Favorite, Color(0xFFEC4899))
    )

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        personas.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (name, icon, color) ->
                    PersonaCard(
                        name = name,
                        icon = icon,
                        color = color,
                        isSelected = selectedPersona == name,
                        onClick = { onPersonaSelect(name) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaCard(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(110.dp).semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(24.dp),
        color = if (isSelected) color.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(2.dp, if (isSelected) color else Color.Transparent)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(imageVector = icon, contentDescription = name, tint = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text(text = name, style = MaterialTheme.typography.labelMedium, fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold, color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
fun StreamingBubble(text: String, tps: Double = 0.0, rt: Long = 0L) {
    val (thinkingText, responseText) = parseMessageContent(text)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp),
            modifier = Modifier.semantics(mergeDescendants = true) {}
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (thinkingText != null) {
                    var expanded by remember { mutableStateOf(false) }
                    Column(modifier = Modifier.animateContentSize()) {
                        Row(
                            modifier = Modifier.clickable { expanded = !expanded },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                                null,
                                tint = textColor.copy(alpha = 0.7f)
                            )
                            Text("Thinking Process", style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                        }
                        if (expanded) {
                            MarkdownText(text = thinkingText, color = textColor.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                
                if (responseText.isNotEmpty()) {
                    MarkdownText(text = responseText, color = textColor)
                } else if (thinkingText == null) {
                    Text("● ● ●", color = textColor.copy(alpha = 0.5f))
                }
            }
        }
        if (tps > 0) {
            Text(
                String.format("%.1f t/s", tps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 4.dp, start = 8.dp)
            )
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge
) {
    val lines = text.lines()
    var inCodeBlock = false
    var codeBlockContent = StringBuilder()
    var codeLanguage = ""

    Column(modifier = modifier) {
        lines.forEach { line ->
            val trimmed = line.trim()
            
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    CodeBlock(codeBlockContent.toString(), codeLanguage)
                    codeBlockContent = StringBuilder()
                    codeLanguage = ""
                    inCodeBlock = false
                } else {
                    codeLanguage = trimmed.removePrefix("```").trim()
                    inCodeBlock = true
                }
                return@forEach
            }

            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n")
                return@forEach
            }

            val trimmedStart = line.trimStart()
            when {
                trimmedStart.startsWith("- ") || trimmedStart.startsWith("* ") -> {
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("•  ", color = color, style = style)
                        Text(text = buildInlineAnnotated(trimmedStart.drop(2), color), color = color, style = style)
                    }
                }
                trimmedStart.startsWith("### ") -> {
                    Text(trimmedStart.removePrefix("### "), color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                }
                line.isBlank() -> {
                    Spacer(Modifier.height(8.dp))
                }
                else -> {
                    Text(text = buildInlineAnnotated(line, color), color = color, style = style, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }
    }
}

@Composable
fun CodeBlock(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    Surface(
        color = Color.Black.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {}
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color.Gray.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(language.ifEmpty { "code" }.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                IconButton(onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    clipboard.setText(AnnotatedString(code.trim())) 
                }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                text = code.trim(),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

fun buildInlineAnnotated(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = baseColor.copy(alpha = 0.15f))) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
