package com.chhanda.ai.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.speech.tts.TextToSpeech
import androidx.compose.material.icons.filled.VolumeUp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chhanda.ai.presentation.viewmodel.SystemViewModel
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Mic
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import com.chhanda.ai.data.repository.MessageEntity
import com.chhanda.ai.presentation.viewmodel.ChatUiState
import com.chhanda.ai.presentation.viewmodel.ChatViewModel
import com.chhanda.ai.presentation.ui.components.ChhandaLogo
import com.chhanda.ai.util.Localization
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController, viewModel: ChatViewModel, isReadOnly: Boolean = false) {
    val uiState by viewModel.uiState.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val systemViewModel: SystemViewModel = hiltViewModel()
    val selectedVoice by systemViewModel.selectedVoice.collectAsState()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var showCloseConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    
    val ttsLocale = when (appLanguage) {
        "Bengali" -> java.util.Locale("bn", "BD")
        "Hindi" -> java.util.Locale("hi", "IN")
        else -> java.util.Locale.ENGLISH
    }

    DisposableEffect(ttsLocale, selectedVoice) {
        var ttsInstance: TextToSpeech? = null
        try {
            ttsInstance = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsInstance?.language = ttsLocale
                    if (selectedVoice != "Default") {
                        val isMale = selectedVoice.contains("Male")
                        val voices = ttsInstance?.voices
                        val targetVoice = voices?.filter { it.locale.language == ttsLocale.language }
                            ?.find { v ->
                                val name = v.name.lowercase()
                                if (isMale) {
                                    name.contains("male") || name.contains("-m-") || name.contains("male_")
                                } else {
                                    name.contains("female") || name.contains("-f-") || name.contains("female_")
                                }
                            } ?: voices?.filter { it.locale.language == ttsLocale.language }?.firstOrNull()
                            
                        if (targetVoice != null) {
                            ttsInstance?.voice = targetVoice
                        }
                    }
                }
            }
            tts = ttsInstance
        } catch (e: Exception) {
            android.util.Log.e("ChatScreen", "Failed to initialize TTS", e)
        }
        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
        }
    }

    // Auto-scroll to bottom on new messages or partial tokens
    val totalItems = uiState.messages.size + (if (uiState.currentPartialResponse.isNotEmpty()) 1 else 0)
    LaunchedEffect(totalItems) {
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showCloseConfirm = true }) {
                            ChhandaLogo(size = 32)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Chhanda AI", 
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleLarge
                        ) 
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                ),
                actions = {
                    IconButton(onClick = { showCloseConfirm = true }) {
                        Icon(Icons.Default.Close, "Close Chat", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── No-Model Warning Banner ───────────────────────────────────────
            if (uiState.isModelLoading) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            Localization.getString("loading", appLanguage),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (!uiState.isModelLoaded) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            Localization.getString("no_active_model", appLanguage),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // ── Message List ───────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    MessageBubble(message, tts)
                }

                if (uiState.currentPartialResponse.isNotEmpty()) {
                    item(key = "streaming_node") {
                        StreamingBubble(uiState.currentPartialResponse, uiState.currentTps, uiState.currentRt, appLanguage)
                    }
                }

                uiState.error?.let { error ->
                    item(key = "error_node") {
                        ErrorBubble(error)
                    }
                }
            }
            
            // Attachment Preview
            val selectedFiles by viewModel.selectedFiles.collectAsState()
            if (selectedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedFiles.forEach { uri ->
                        AttachmentPreview(uri, onRemove = { viewModel.removeFile(uri) })
                    }
                }
            }

            // Input Area
            ChatInput(
                text = inputText,
                isReadOnly = isReadOnly,
                onTextChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                onRefine = {
                    viewModel.refineText(inputText)
                    inputText = ""
                },
                isGenerating = uiState.isGenerating,
                onStop = { viewModel.stopInference() },
                onAttach = { viewModel.addFile(it) },
                appLanguage = appLanguage
            )
        }

        if (showCloseConfirm) {
            AlertDialog(
                onDismissRequest = { showCloseConfirm = false },
                title = { Text("Close Chat") },
                text = { Text("Are you sure you want to close the chat? Your history will be preserved.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCloseConfirm = false
                            navController.popBackStack()
                        }
                    ) {
                        Text(Localization.getString("confirm", appLanguage))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCloseConfirm = false }) {
                        Text(Localization.getString("cancel", appLanguage))
                    }
                }
            )
        }
    }
}

fun parseMessageContent(rawText: String): Pair<String?, String> {
    val cleanedText = rawText
        .replace(Regex("<turn\\|?>"), "")
        .replace(Regex("<\\|channel>thought"), "")
        .replace(Regex("<\\|channel>"), "")
        .replace(Regex("<start_of_turn>"), "")
        .replace(Regex("<end_of_turn>"), "")
        .trim()

    var thinkingText: String? = null
    var responseText = cleanedText

    val thinkStartIndex = cleanedText.indexOf("<think>")
    val thoughtStartIndex = cleanedText.indexOf("<thought>")
    
    if (thinkStartIndex != -1) {
        val thinkEndIndex = cleanedText.indexOf("</think>", thinkStartIndex)
        if (thinkEndIndex != -1) {
            thinkingText = cleanedText.substring(thinkStartIndex + 7, thinkEndIndex).trim()
            responseText = (cleanedText.substring(0, thinkStartIndex) + cleanedText.substring(thinkEndIndex + 8)).trim()
        } else {
            thinkingText = cleanedText.substring(thinkStartIndex + 7).trim()
            responseText = cleanedText.substring(0, thinkStartIndex).trim()
        }
        return Pair(thinkingText, responseText)
    } else if (thoughtStartIndex != -1) {
        val thoughtEndIndex = cleanedText.indexOf("</thought>", thoughtStartIndex)
        if (thoughtEndIndex != -1) {
            thinkingText = cleanedText.substring(thoughtStartIndex + 9, thoughtEndIndex).trim()
            responseText = (cleanedText.substring(0, thoughtStartIndex) + cleanedText.substring(thoughtEndIndex + 10)).trim()
        } else {
            thinkingText = cleanedText.substring(thoughtStartIndex + 9).trim()
            responseText = cleanedText.substring(0, thoughtStartIndex).trim()
        }
        return Pair(thinkingText, responseText)
    }

    val thinkingMarkers = listOf("Thinking Process:", "Thought:")
    for (marker in thinkingMarkers) {
        val index = responseText.indexOf(marker)
        if (index != -1) {
            val endOfThinking = responseText.indexOf("\n\n", index)
            if (endOfThinking != -1) {
                thinkingText = responseText.substring(index + marker.length, endOfThinking).trim()
                responseText = (responseText.substring(0, index) + responseText.substring(endOfThinking)).trim()
            } else {
                thinkingText = responseText.substring(index + marker.length).trim()
                responseText = responseText.substring(0, index).trim()
            }
            break
        }
    }
    
    return Pair(thinkingText, responseText)
}

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
        "EXCEL" -> Icons.Default.AutoAwesome // Placeholder for spreadsheet icon
        "WORD" -> Icons.Default.AttachFile
        "PDF" -> Icons.Default.Info
        else -> Icons.Default.AttachFile
    }

    Surface(
        modifier = Modifier
            .padding(top = 8.dp)
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                // Share/Open File
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "com.chhanda.ai.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Download $fileName"))
            },
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = "$fileType Document",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Download",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun MessageBubble(message: MessageEntity, tts: TextToSpeech?) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isUser) {
        RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp)
    } else {
        RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp)
    }

    val (thinkingText, responseText) = parseMessageContent(message.text)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = bubbleColor,
            shape = shape,
            shadowElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Thinking process is hidden in saved messages as per user request
                /*
                if (thinkingText != null) {
                    ...
                }
                */
                
                if (responseText.isNotEmpty()) {
                    MarkdownText(
                        text = responseText,
                        color = textColor
                    )
                }
            }
        }
        
        // Attachment Indicator (User only)
        if (isUser && message.attachmentPaths != null) {
            Row(
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                message.attachmentPaths.split(",").forEach { path ->
                    val icon = when {
                        path.contains(".pdf", true) -> Icons.Default.PictureAsPdf
                        path.contains(".doc", true) || path.contains(".docx", true) -> Icons.Default.Description
                        path.contains(".xls", true) || path.contains(".xlsx", true) -> Icons.Default.TableChart
                        path.contains(".png", true) || path.contains(".jpg", true) || path.contains(".jpeg", true) -> Icons.Default.Image
                        path.contains(".mp3", true) || path.contains(".wav", true) -> Icons.Default.Mic
                        else -> Icons.Default.AttachFile
                    }
                    val label = when {
                        path.contains(".pdf", true) -> "PDF"
                        path.contains(".doc", true) || path.contains(".docx", true) -> "DOC"
                        path.contains(".xls", true) || path.contains(".xlsx", true) -> "XLS"
                        path.contains(".png", true) || path.contains(".jpg", true) || path.contains(".jpeg", true) -> "IMG"
                        path.contains(".mp3", true) || path.contains(".wav", true) -> "AUD"
                        else -> "FILE"
                    }
                    
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
        
        // SENIOR FEATURE: Document Generation Preview
        if (message.generatedFilePath != null) {
            val file = java.io.File(message.generatedFilePath)
            if (file.exists()) {
                DocumentDownloadCard(file)
            }
        }
        
        // Actions and Stats Footer (Model only)
        if (!isUser && responseText.isNotEmpty()) {
            val clipboardManager = LocalClipboardManager.current
            val context = LocalContext.current
            
            Row(
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(responseText)) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy, 
                        contentDescription = "Copy", 
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                
                IconButton(
                    onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, responseText)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, null)
                        context.startActivity(shareIntent)
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Share, 
                        contentDescription = "Share", 
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                IconButton(
                    onClick = {
                        tts?.speak(responseText, TextToSpeech.QUEUE_FLUSH, null, null)
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.VolumeUp, 
                        contentDescription = "Speak", 
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                if (message.isRagUsed) {
                    Surface(
                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoMode,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = Color(0xFF059669)
                            )
                            Text(
                                text = "RAG ACTIVE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color(0xFF059669)
                            )
                        }
                    }
                }

                if (message.tps > 0) {
                    val timeStr = if (message.responseTimeMs > 0) {
                        String.format(" • %.1fs", message.responseTimeMs / 1000.0)
                    } else ""
                    Text(
                        text = String.format("%.1f t/s%s", message.tps, timeStr),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Lightweight inline Markdown renderer using AnnotatedString.
 * Handles: **bold**, *italic*, `code`, # headings, - bullet lists.
 * Does NOT require any extra library.
 */
@Composable
fun MarkdownText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge
) {
    val lines = text.lines()
    var inCodeBlock = false
    var inCreateBlock = false
    var inGenerateBlock = false
    
    var codeBlockContent = StringBuilder()
    var codeLanguage = ""
    var createPath = ""
    var genType = ""
    var genName = ""

    androidx.compose.foundation.layout.Column(modifier = modifier) {
        lines.forEach { line ->
            val trimmed = line.trim()
            
            if (trimmed.startsWith("[CREATE_FILE")) {
                val pathMatch = """path="([^"]+)"""".toRegex().find(trimmed)
                createPath = pathMatch?.groupValues?.get(1) ?: "file"
                inCreateBlock = true
                return@forEach
            }
            if (trimmed == "[/CREATE_FILE]") {
                CodeBlock(codeBlockContent.toString(), "CREATE: $createPath")
                codeBlockContent = StringBuilder()
                inCreateBlock = false
                return@forEach
            }
            if (inCreateBlock) {
                codeBlockContent.append(line).append("\n")
                return@forEach
            }

            if (trimmed.startsWith("[GENERATE_FILE")) {
                val typeMatch = """type="([^"]+)"""".toRegex().find(trimmed)
                val nameMatch = """name="([^"]+)"""".toRegex().find(trimmed)
                genType = typeMatch?.groupValues?.get(1) ?: "DOC"
                genName = nameMatch?.groupValues?.get(1) ?: "document"
                inGenerateBlock = true
                return@forEach
            }
            if (trimmed == "[/GENERATE_FILE]") {
                AttachmentDownload(genName, genType)
                inGenerateBlock = false
                return@forEach
            }
            if (inGenerateBlock) {
                // Just content inside the tag, not needed for the card
                return@forEach
            }

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // End of block
                    CodeBlock(codeBlockContent.toString(), codeLanguage)
                    codeBlockContent = StringBuilder()
                    codeLanguage = ""
                    inCodeBlock = false
                } else {
                    // Start of block
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
                trimmedStart.startsWith("> ") -> {
                    Row(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(2.dp))
                                .background(color.copy(alpha = 0.2f))
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = buildInlineAnnotated(trimmedStart.removePrefix("> "), color),
                            color = color.copy(alpha = 0.8f),
                            style = style.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
                trimmedStart == "***" || trimmedStart == "---" || trimmedStart == "___" -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        thickness = 1.dp,
                        color = color.copy(alpha = 0.1f)
                    )
                }
                trimmedStart.startsWith("### ") -> {
                    Text(
                        text = trimmedStart.removePrefix("### "),
                        color = color,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                trimmedStart.startsWith("## ") -> {
                    Text(
                        text = trimmedStart.removePrefix("## "),
                        color = color,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )
                }
                trimmedStart.startsWith("# ") -> {
                    Text(
                        text = trimmedStart.removePrefix("# "),
                        color = color,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                }
                trimmedStart.startsWith("- ") || trimmedStart.startsWith("* ") -> {
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("•  ", color = color, style = style)
                        Text(
                            text = buildInlineAnnotated(trimmedStart.drop(2), color),
                            color = color,
                            style = style
                        )
                    }
                }
                trimmedStart.getOrNull(0)?.isDigit() == true && trimmedStart.contains(". ") -> {
                    val dotIdx = trimmedStart.indexOf(". ")
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(trimmedStart.substring(0, dotIdx + 2), color = color, style = style, fontWeight = FontWeight.Bold)
                        Text(
                            text = buildInlineAnnotated(trimmedStart.substring(dotIdx + 2), color),
                            color = color,
                            style = style
                        )
                    }
                }
                line.isBlank() -> {
                    Spacer(Modifier.height(8.dp))
                }
                else -> {
                    Text(
                        text = buildInlineAnnotated(line, color),
                        color = color,
                        style = style,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
        
        // Safety: if blocks weren't closed
        if (inCodeBlock && codeBlockContent.isNotEmpty()) {
            CodeBlock(codeBlockContent.toString(), codeLanguage)
        }
        if (inCreateBlock && codeBlockContent.isNotEmpty()) {
            CodeBlock(codeBlockContent.toString(), "CREATE: $createPath")
        }
        if (inGenerateBlock) {
            AttachmentDownload(genName, genType)
        }
    }
}

@Composable
fun CodeBlock(code: String, language: String) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    Surface(
        color = Color.Black.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Gray.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifEmpty { "code" }.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(code.trim())) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy Code",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = code.trim(),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Parses **bold** and *italic* and `code` inline spans into an AnnotatedString.
 */
fun buildInlineAnnotated(
    text: String,
    baseColor: androidx.compose.ui.graphics.Color
): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text.startsWith("*", i) && !text.startsWith("**", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end != -1 && !text.startsWith("**", end)) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        background = baseColor.copy(alpha = 0.15f)
                    )) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
fun StreamingBubble(text: String, tps: Double = 0.0, rt: Long = 0L, appLanguage: String = "English") {
    val (thinkingText, responseText) = parseMessageContent(text)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp)
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
                                contentDescription = "Thinking",
                                tint = textColor.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Thinking Process", 
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (expanded) {
                            Spacer(Modifier.height(4.dp))
                            MarkdownText(
                                text = thinkingText,
                                color = textColor.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                
                if (responseText.isNotEmpty()) {
                    MarkdownText(
                        text = responseText,
                        color = textColor
                    )
                } else if (thinkingText == null) {
                    // Show a typing indicator if nothing is parsed yet
                    Text(
                        "● ● ●",
                        color = textColor.copy(alpha = 0.5f)
                    )
                }
            }
        }
        if (tps > 0 || rt > 0) {
            val label = if (rt > 0) {
                String.format("%.1f t/s | %.2fs", tps, rt / 1000f)
            } else {
                String.format("%.1f t/s", tps)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 4.dp, start = 8.dp)
            )
        }
    }
}

@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    onStop: () -> Unit,
    onAttach: (android.net.Uri) -> Unit,
    onRefine: () -> Unit,
    appLanguage: String = "English",
    isReadOnly: Boolean = false
) {
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onAttach(it) } }

    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { onAttach(it) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val uri = com.chhanda.ai.util.FileUtils.createImageUri(context)
                if (uri != null) {
                    cameraUri = uri
                    cameraLauncher.launch(uri)
                } else {
                    android.widget.Toast.makeText(context, "Could not create image file", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to launch camera", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(context, "Camera permission is required", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val locale = when (appLanguage) {
        "Bengali" -> java.util.Locale("bn", "BD")
        "Hindi" -> java.util.Locale("hi", "IN")
        else -> java.util.Locale.ENGLISH
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val spokenText = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                onTextChange(text + spokenText)
            }
        }
    }

    if (isReadOnly) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(32.dp)
        ) {
            Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Read Only Chat", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(32.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { fileLauncher.launch("*/*") }) {
                    Icon(Icons.Default.Add, null, tint = Color.Gray)
                }
                IconButton(onClick = { 
                    try {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            val uri = com.chhanda.ai.util.FileUtils.createImageUri(context)
                            if (uri != null) {
                                cameraUri = uri
                                cameraLauncher.launch(uri)
                            } else {
                                android.widget.Toast.makeText(context, "Could not create image file", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Camera error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.PhotoCamera, null, tint = Color.Gray)
                }
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text(Localization.getString("chat_hint", appLanguage)) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    singleLine = false,
                    maxLines = 4
                )
                
                if (text.isNotBlank() && !isGenerating) {
                    IconButton(onClick = onRefine) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Refine",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                val context = androidx.compose.ui.platform.LocalContext.current
                IconButton(onClick = {
                    try {
                        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, locale.toString())
                            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak now...")
                        }
                        voiceLauncher.launch(intent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Voice search not supported on this device", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.Mic, null, tint = Color.Gray)
                }

                IconButton(
                    onClick = if (isGenerating) onStop else onSend,
                    enabled = (text.isNotBlank() || isGenerating),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (text.isNotBlank() && !isGenerating) {
                                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)))
                            } else {
                                Brush.linearGradient(listOf(Color.Gray, Color.LightGray))
                            }
                        )
                ) {
                    if (isGenerating) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AttachmentPreview(uri: android.net.Uri, onRemove: () -> Unit) {
    Surface(
        color = Color(0xFFDBEAFE),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(14.dp), tint = Color(0xFF2563EB))
            Spacer(Modifier.width(4.dp))
            Text(
                uri.lastPathSegment?.take(10) ?: "File", 
                fontSize = 11.sp, 
                color = Color(0xFF2563EB),
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(16.dp)) {
                Icon(Icons.Default.Close, null, tint = Color(0xFF2563EB), modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
fun ErrorBubble(error: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp
        ) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun AttachmentDownload(name: String, type: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when(type.lowercase()) {
                    "excel" -> Icons.Default.TableChart
                    "word" -> Icons.AutoMirrored.Filled.Article
                    "pdf" -> Icons.Default.PictureAsPdf
                    else -> Icons.Default.AttachFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(type.uppercase(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
            }
            Button(
                onClick = {
                    val file = java.io.File(context.filesDir, "generated/$name")
                    if (file.exists()) {
                        try {
                            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            val destFile = java.io.File(downloadsDir, name)
                            file.inputStream().use { input ->
                                destFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            android.widget.Toast.makeText(context, "Saved to Downloads", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Failed to save: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        android.widget.Toast.makeText(context, "File not found locally", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Download")
            }
        }
    }
}
