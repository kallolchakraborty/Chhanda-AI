package com.chhanda.ai.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Description
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
import android.speech.tts.UtteranceProgressListener
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.chhanda.ai.presentation.viewmodel.SystemViewModel
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(
    navController: NavController, 
    viewModel: ChatViewModel, 
    isReadOnly: Boolean = false,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val systemViewModel: SystemViewModel = hiltViewModel()
    val selectedVoice by systemViewModel.selectedVoice.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var showCloseConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    
    val ttsLocale = when (appLanguage) {
        "Bengali" -> java.util.Locale("bn", "BD")
        "Hindi" -> java.util.Locale("hi", "IN")
        else -> java.util.Locale.ENGLISH
    }

    var activeTtsMessageId by remember { mutableStateOf<String?>(null) }
    var activeTtsText by remember { mutableStateOf("") }
    var ttsProgress by remember { mutableFloatStateOf(0f) }
    var isTtsPlaying by remember { mutableStateOf(false) }
    var ttsOffset by remember { mutableIntStateOf(0) }

    DisposableEffect(ttsLocale, selectedVoice) {
        var ttsInstance: TextToSpeech? = null
        try {
            ttsInstance = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsInstance?.let { engine ->
                        engine.language = ttsLocale
                        engine.setSpeechRate(0.9f)
                        engine.setPitch(1.0f)
                        
                        // Voice selection logic: Match by Persona Type (Male/Female) and Locale
                        if (selectedVoice != "Default") {
                            val isMale = selectedVoice.contains("Male")
                            val systemVoices = engine.voices?.toList() ?: emptyList()
                            
                            val pool = systemVoices.filter { v -> v.locale.language == ttsLocale.language }
                                .filter { v ->
                                    val name = v.name.lowercase()
                                    if (isMale) {
                                        name.contains("male") || name.contains("-m-") || name.contains("_m_") || 
                                        name.contains("ahp") || name.contains("hie") || name.contains("baq") ||
                                        name.contains("guy") || name.contains("man") || name.contains("boy") ||
                                        (v.locale.country == "IN" && (name.contains("en-in-x-ahp") || name.contains("hi-in-x-hie")))
                                    } else {
                                        name.contains("female") || name.contains("-f-") || name.contains("_f_") || 
                                        name.contains("ahi") || name.contains("hif") || name.contains("ban") ||
                                        name.contains("girl") || name.contains("woman") || name.contains("lady") ||
                                        (v.locale.country == "IN" && (name.contains("en-in-x-ahi") || name.contains("hi-in-x-hif")))
                                    }
                                }
                                .sortedByDescending { v ->
                                    val name = v.name.lowercase()
                                    var score = 0
                                    if (!v.isNetworkConnectionRequired) score += 100
                                    if (name.contains("network") || name.contains("neural")) score += 50
                                    score
                                }
                            
                            val targetVoice = pool.firstOrNull() ?: run {
                                val localeVoices = systemVoices.filter { it.locale.language == ttsLocale.language }
                                if (isMale && localeVoices.size > 1) localeVoices.getOrNull(1) else localeVoices.firstOrNull()
                            }
                            
                            targetVoice?.let { engine.voice = it }
                        }
                        
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                scope.launch { isTtsPlaying = true }
                            }
                            override fun onDone(utteranceId: String?) {
                                scope.launch {
                                    if (activeTtsMessageId == utteranceId) {
                                        activeTtsMessageId = null
                                        isTtsPlaying = false
                                        ttsProgress = 0f
                                        ttsOffset = 0
                                    }
                                }
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                scope.launch { isTtsPlaying = false }
                            }
                            override fun onError(utteranceId: String?, errorCode: Int) {
                                scope.launch { isTtsPlaying = false }
                            }
                            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                                scope.launch {
                                    if (activeTtsText.isNotEmpty()) {
                                        ttsProgress = (ttsOffset + start).toFloat() / activeTtsText.length.coerceAtLeast(1)
                                    }
                                }
                            }
                        })
                        // Only set the state when successfully initialized
                        tts = engine
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatScreen", "Failed to init TTS", e)
        }
        
        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
            tts = null
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
                        with(sharedTransitionScope) {
                            ChhandaLogo(
                                size = 32,
                                modifier = Modifier.sharedElement(
                                    sharedTransitionScope.rememberSharedContentState(key = "model_logo_${viewModel.modelName}"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        with(sharedTransitionScope) {
                            Text(
                                viewModel.modelName, 
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.sharedElement(
                                    sharedTransitionScope.rememberSharedContentState(key = "model_name_${viewModel.modelName}"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            ) 
                        }
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
                if (uiState.messages.isEmpty() && !uiState.isGenerating) {
                    item {
                        PersonaSelectionHeader(appLanguage)
                    }
                    item {
                        PersonaSelectionGrid(
                            selectedPersona = uiState.selectedPersona,
                            onPersonaSelect = { viewModel.setPersona(it) },
                            appLanguage = appLanguage
                        )
                    }
                }

                itemsIndexed(
                    items = uiState.messages,
                    key = { index, item -> item.id }
                ) { index, message ->
                    val isLastMessage = index == uiState.messages.lastIndex
                    MessageBubble(
                        message, 
                        tts,
                        isActiveTts = activeTtsMessageId == message.id.toString(),
                        isGenerating = isLastMessage && uiState.isGenerating,
                        onTtsToggle = {
                            val msgIdStr = message.id.toString()
                            if (activeTtsMessageId == msgIdStr) {
                                // Stop if clicking the same message
                                tts?.stop()
                                activeTtsMessageId = null
                                isTtsPlaying = false
                            } else {
                                tts?.stop()
                                // Prioritize the dedicated thinking field if present
                                val textToSpeak = if (message.thinking != null) {
                                    message.text // In the new format, 'text' is the clean response
                                } else {
                                    // Legacy support: parse from raw text
                                    parseMessageContent(message.text).second
                                }
                                
                                val cleanText = cleanTextForTts(textToSpeak)
                                if (cleanText.isNotEmpty()) {
                                    activeTtsMessageId = msgIdStr
                                    activeTtsText = cleanText
                                    ttsOffset = 0
                                    ttsProgress = 0f
                                    tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, msgIdStr)
                                }
                            }
                        }
                    )
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
            
            // TTS Player Bar
            if (activeTtsMessageId != null && activeTtsText.isNotEmpty()) {
                GlobalTtsPlayer(
                    progress = ttsProgress,
                    isPlaying = isTtsPlaying,
                    onStop = {
                        tts?.stop()
                        activeTtsMessageId = null
                        isTtsPlaying = false
                    },
                    onTogglePlay = {
                        if (isTtsPlaying) {
                            tts?.stop()
                            isTtsPlaying = false
                        } else {
                            val textToSpeak = activeTtsText.substring(ttsOffset)
                            if (textToSpeak.isNotEmpty()) {
                                tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, activeTtsMessageId)
                                isTtsPlaying = true
                            }
                        }
                    },
                    onSeek = { pos ->
                        val targetIndex = (pos * activeTtsText.length).toInt().coerceIn(0, activeTtsText.length)
                        tts?.stop()
                        ttsOffset = targetIndex
                        val textToSpeak = activeTtsText.substring(targetIndex)
                        if (textToSpeak.isNotEmpty()) {
                            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, activeTtsMessageId)
                            isTtsPlaying = true
                        } else {
                            isTtsPlaying = false
                        }
                    },
                    onForward = {
                        val currentIdx = (ttsProgress * activeTtsText.length).toInt()
                        val targetIndex = (currentIdx + 100).coerceAtMost(activeTtsText.length)
                        tts?.stop()
                        ttsOffset = targetIndex
                        if (targetIndex < activeTtsText.length) {
                            tts?.speak(activeTtsText.substring(targetIndex), TextToSpeech.QUEUE_FLUSH, null, activeTtsMessageId)
                            isTtsPlaying = true
                        } else {
                            isTtsPlaying = false
                        }
                    },
                    onBackward = {
                        val currentIdx = (ttsProgress * activeTtsText.length).toInt()
                        val targetIndex = (currentIdx - 100).coerceAtLeast(0)
                        tts?.stop()
                        ttsOffset = targetIndex
                        tts?.speak(activeTtsText.substring(targetIndex), TextToSpeech.QUEUE_FLUSH, null, activeTtsMessageId)
                        isTtsPlaying = true
                    }
                )
            }

            // Attachment Preview
            val selectedFiles by viewModel.selectedFiles.collectAsStateWithLifecycle()
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
        .replace("\ufeff", "")
        .replace("\uFEFF", "")
        .replace("[UTF-8]", "")
        .replace("(UTF-8)", "")
        .replace("UTF-8: ", "")
        .replace("\u00A0", " ")
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

fun cleanTextForTts(text: String): String {
    return text
        // Remove Markdown bold
        .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
        // Remove Markdown italic
        .replace(Regex("\\*(.*?)\\*"), "$1")
        // Remove Markdown inline code
        .replace(Regex("`(.*?)`"), "$1")
        // Remove Markdown code blocks and replace with cue
        .replace(Regex("```[\\s\\S]*?```"), " Please check the code block for details. ")
        // Remove custom tags [CREATE_FILE]...[/CREATE_FILE]
        .replace(Regex("\\[CREATE_FILE.*?\\][\\s\\S]*?\\[/CREATE_FILE\\]"), "")
        // Remove custom tags [GENERATE_FILE]...[/GENERATE_FILE]
        .replace(Regex("\\[GENERATE_FILE.*?\\][\\s\\S]*?\\[/GENERATE_FILE\\]"), "")
        // Remove Markdown headings
        .replace(Regex("#+\\s+"), "")
        // Remove Markdown links [text](url) -> text
        .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
        // Remove special characters that might sound weird
        .replace(Regex("[_~>]"), " ")
        // Normalize whitespace
        .replace(Regex("\\s+"), " ")
        .trim()
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
fun GlobalTtsPlayer(
    progress: Float,
    isPlaying: Boolean,
    onStop: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onForward: () -> Unit,
    onBackward: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Speaking Response...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onStop, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                }
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                IconButton(onClick = onBackward) {
                    Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(20.dp))
                }

                Slider(
                    value = progress,
                    onValueChange = onSeek,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
                )

                IconButton(onClick = onForward) {
                    Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(20.dp))
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Spacer(Modifier.width(24.dp))
                
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: MessageEntity, 
    tts: TextToSpeech?,
    isActiveTts: Boolean = false,
    isGenerating: Boolean = false,
    onTtsToggle: () -> Unit = {}
) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isUser) {
        RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp)
    } else {
        RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp)
    }

    // Local state to track if thinking process is visible
    var isThinkingExpanded by remember { mutableStateOf(false) }
    
    // Prioritize the dedicated thinking field from the database, fall back to parsing for legacy messages
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
            shadowElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (!finalThinking.isNullOrBlank()) {
                    // Show Thinking Toggle Button
                    Surface(
                        onClick = { isThinkingExpanded = !isThinkingExpanded },
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

                    // Thinking Content (Expandable)
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
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }
                    }
                }
                
                if (finalResponse.isNotEmpty()) {
                    MarkdownText(
                        text = finalResponse,
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
                    val pathLower = path.lowercase()
                    val icon = when {
                        pathLower.contains("pdf") -> Icons.Default.PictureAsPdf
                        pathLower.contains("word") || pathLower.contains(".doc") -> Icons.Default.Description
                        pathLower.contains("excel") || pathLower.contains(".xls") -> Icons.Default.TableChart
                        pathLower.contains("image") || pathLower.contains(".png") || pathLower.contains(".jpg") || pathLower.contains(".jpeg") -> Icons.Default.Image
                        pathLower.contains("audio") || pathLower.contains(".mp3") || pathLower.contains(".wav") -> Icons.Default.Mic
                        else -> Icons.Default.AttachFile
                    }
                    val label = when {
                        pathLower.contains("pdf") -> "PDF"
                        pathLower.contains("word") || pathLower.contains(".doc") -> "WORD"
                        pathLower.contains("excel") || pathLower.contains(".xls") -> "EXCEL"
                        pathLower.contains("image") || pathLower.contains(".png") || pathLower.contains(".jpg") || pathLower.contains(".jpeg") -> "IMAGE"
                        pathLower.contains("audio") || pathLower.contains(".mp3") || pathLower.contains(".wav") -> "AUDIO"
                        else -> "FILE"
                    }
                    val color = when {
                        pathLower.contains("pdf") -> Color(0xFFEF4444)
                        pathLower.contains("word") || pathLower.contains(".doc") -> Color(0xFF6366F1)
                        pathLower.contains("excel") || pathLower.contains(".xls") -> Color(0xFF22C55E)
                        pathLower.contains("image") || pathLower.contains(".png") || pathLower.contains(".jpg") || pathLower.contains(".jpeg") -> Color(0xFF06B6D4)
                        pathLower.contains("audio") || pathLower.contains(".mp3") || pathLower.contains(".wav") -> Color(0xFF3B82F6)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    
                    Surface(
                        color = color.copy(alpha = 0.1f),
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
                                tint = color.copy(alpha = 0.7f)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = color.copy(alpha = 0.7f)
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
        if (!isUser && finalResponse.isNotEmpty() && !isGenerating) {
            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
            val context = androidx.compose.ui.platform.LocalContext.current
            
            Row(
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(finalResponse)) },
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
                            putExtra(Intent.EXTRA_TEXT, finalResponse)
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
                    onClick = onTtsToggle,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (isActiveTts) Icons.Default.Stop else Icons.Default.VolumeUp, 
                        contentDescription = "Speak", 
                        modifier = Modifier.size(14.dp),
                        tint = if (isActiveTts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                if (message.isRagUsed) {
                    val sourceLabel = when (message.contextSource) {
                        "Attachment" -> "DOCS ACTIVE"
                        "Knowledge Base" -> "KB ACTIVE"
                        "Multi-Source" -> "MULTI-RAG"
                        else -> "RAG ACTIVE"
                    }
                    val sourceColor = when (message.contextSource) {
                        "Attachment" -> Color(0xFF3B82F6) // Blue
                        "Knowledge Base" -> Color(0xFF10B981) // Green
                        else -> Color(0xFF8B5CF6) // Purple
                    }
                    
                    Surface(
                        color = sourceColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, sourceColor.copy(alpha = 0.3f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (message.contextSource == "Attachment") Icons.Default.AttachFile else Icons.Default.AutoMode,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = sourceColor
                            )
                            Text(
                                text = sourceLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = sourceColor
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
        var inTable = false
        val tableBuffer = mutableListOf<String>()

        lines.forEach { line ->
            val trimmed = line.trim()
            
            if (trimmed.startsWith("|") && trimmed.contains("|")) {
                inTable = true
                tableBuffer.add(line)
                return@forEach
            } else if (inTable) {
                renderTable(tableBuffer, color, style)
                tableBuffer.clear()
                inTable = false
            }

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
                trimmedStart.startsWith("|") && trimmedStart.endsWith("|") && !trimmedStart.contains("---") -> {
                    // Start of a table or table content
                    // We need to look ahead or buffer, but for simplicity in this inline renderer,
                    // we'll render each row. A better way is to buffer the whole table.
                    // Let's implement a basic row-based table view.
                    val cells = trimmedStart.split("|").filter { it.isNotBlank() }.map { it.trim() }
                    if (cells.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            color = color.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.1f))
                        ) {
                            Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                cells.forEach { cell ->
                                    Text(
                                        text = buildInlineAnnotated(cell, color),
                                        modifier = Modifier.weight(1f),
                                        style = style.copy(fontSize = 12.sp, fontWeight = if (lines.getOrNull(lines.indexOf(line) + 1)?.contains("---") == true) FontWeight.Bold else FontWeight.Normal),
                                        color = color
                                    )
                                }
                            }
                        }
                    }
                }
                trimmedStart.startsWith("|") && trimmedStart.contains("---") -> {
                    // Table separator, skip
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
        
        // Safety: if table was at the end
        if (inTable && tableBuffer.isNotEmpty()) {
            renderTable(tableBuffer, color, style)
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
fun renderTable(rows: List<String>, color: Color, style: androidx.compose.ui.text.TextStyle) {
    val tableData = rows.filter { !it.contains("---") }.map { row ->
        row.split("|").filter { it.isNotBlank() }.map { it.trim() }
    }
    
    if (tableData.isEmpty()) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = color.copy(alpha = 0.05f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            tableData.forEachIndexed { rowIndex, cells ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (rowIndex == 0) color.copy(alpha = 0.1f) else Color.Transparent)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    cells.forEach { cell ->
                        Text(
                            text = buildInlineAnnotated(cell, color),
                            modifier = Modifier.weight(1f),
                            style = style.copy(
                                fontSize = 12.sp, 
                                fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = color
                        )
                    }
                }
                if (rowIndex < tableData.size - 1) {
                    HorizontalDivider(color = color.copy(alpha = 0.05f), thickness = 0.5.dp)
                }
            }
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
                text = code.trimNewlines(),
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
                .padding(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp)
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
                    maxLines = 10
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
                    "word" -> Icons.Default.Description
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

fun String.trimNewlines(): String {
    return this.dropWhile { it == '\n' || it == '\r' }.dropLastWhile { it == '\n' || it == '\r' }
}

@Composable
fun PersonaSelectionHeader(appLanguage: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ChhandaLogo(size = 64)
        Spacer(Modifier.height(16.dp))
        Text(
            text = Localization.getString("welcome_to_chhanda", appLanguage),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Select a persona to start your conversation",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PersonaSelectionGrid(
    selectedPersona: String?,
    onPersonaSelect: (String) -> Unit,
    appLanguage: String
) {
    val personas = listOf(
        Triple("Senior Teacher", Icons.Default.School, Color(0xFF6366F1)),
        Triple("Senior Software Engineer", Icons.Default.Code, Color(0xFF10B981)),
        Triple("General Companion", Icons.Default.Face, Color(0xFFF59E0B)),
        Triple("Friend", Icons.Default.Favorite, Color(0xFFEC4899))
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        personas.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                if (row.size < 2) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        
        if (selectedPersona != null) {
            TextButton(
                onClick = { onPersonaSelect("") },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Clear Persona Selection", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaCard(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val borderColor = if (isSelected) color else Color.Transparent
    
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(100.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(2.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = name,
                tint = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
