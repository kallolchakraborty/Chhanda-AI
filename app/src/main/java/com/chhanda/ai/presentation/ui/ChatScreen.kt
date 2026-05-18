package com.chhanda.ai.presentation.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.chhanda.ai.presentation.ui.chat.components.*
import com.chhanda.ai.presentation.ui.components.ChhandaLogo
import com.chhanda.ai.presentation.ui.components.formatModelDisplayName
import com.chhanda.ai.presentation.viewmodel.ChatViewModel
import com.chhanda.ai.presentation.viewmodel.SystemViewModel
import com.chhanda.ai.util.Localization
import kotlinx.coroutines.launch

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
    val hapticsEnabled by systemViewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var inputText by remember { mutableStateOf("") }
    var showCloseConfirm by remember { mutableStateOf(false) }
    var isContinuousVoiceActive by remember { mutableStateOf(false) }

    val voiceListening by viewModel.voiceListening.collectAsStateWithLifecycle()
    val voiceResult by viewModel.voiceResult.collectAsStateWithLifecycle()
    val voiceError by viewModel.voiceError.collectAsStateWithLifecycle()

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isContinuousVoiceActive = true
            viewModel.startVoiceInput()
        }
    }

    var baseText by remember { mutableStateOf("") }
    var wasListening by remember { mutableStateOf(false) }
    var wasSentViaVoice by remember { mutableStateOf(false) }

    // When listening starts, capture the current input text as the base
    LaunchedEffect(voiceListening) {
        if (voiceListening) {
            baseText = inputText
        } else {
            baseText = ""
        }
    }

    LaunchedEffect(voiceResult) {
        if (voiceResult.isNotEmpty()) {
            if (baseText.isEmpty()) {
                inputText = voiceResult
            } else {
                inputText = baseText + (if (baseText.endsWith(" ")) "" else " ") + voiceResult
            }
        }
    }

    val context = LocalContext.current
    LaunchedEffect(voiceError) {
        voiceError?.let { errorMsg ->
            if (hapticsEnabled) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
            android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearVoiceResult() // Clear error so we don't trigger the toast again on recomposition
        }
    }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    
    val ttsLocale = when (appLanguage) {
        "Bengali" -> java.util.Locale("bn", "BD")
        "Hindi" -> java.util.Locale("hi", "IN")
        else -> java.util.Locale("en", "IN")
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
                        var result = engine.setLanguage(ttsLocale)
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            val fallback = when (appLanguage) {
                                "Bengali" -> java.util.Locale("bn", "IN")
                                "Hindi" -> java.util.Locale("hi")
                                else -> java.util.Locale("en")
                            }
                            result = engine.setLanguage(fallback)
                            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                                val secFallback = when (appLanguage) {
                                    "Bengali" -> java.util.Locale("bn")
                                    else -> java.util.Locale.ENGLISH
                                }
                                engine.setLanguage(secFallback)
                            }
                        }
                        engine.setSpeechRate(0.9f)
                        
                        if (selectedVoice != "Default") {
                            val isMale = selectedVoice.contains("Male")
                            val systemVoices = engine.voices?.toList() ?: emptyList()
                            val voiceToUse = com.chhanda.ai.util.TtsVoiceFilter.findBestVoice(systemVoices, ttsLocale, isMale)
                            voiceToUse?.let { engine.voice = it }
                        }
                        
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) { scope.launch { isTtsPlaying = true } }
                            override fun onDone(utteranceId: String?) {
                                scope.launch {
                                    if (activeTtsMessageId == utteranceId) {
                                        activeTtsMessageId = null
                                        isTtsPlaying = false
                                        ttsProgress = 0f
                                        ttsOffset = 0
                                        
                                        if (isContinuousVoiceActive) {
                                            kotlinx.coroutines.delay(500)
                                            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                                context,
                                                android.Manifest.permission.RECORD_AUDIO
                                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                            if (hasPermission) {
                                                viewModel.startVoiceInput()
                                            } else {
                                                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    }
                                }
                            }
                            override fun onError(utteranceId: String?) { scope.launch { isTtsPlaying = false } }
                            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                                scope.launch {
                                    if (activeTtsText.isNotEmpty()) {
                                        ttsProgress = (ttsOffset + start).toFloat() / activeTtsText.length.coerceAtLeast(1)
                                    }
                                }
                            }
                        })
                        tts = engine
                    }
                }
            }
        } catch (e: Exception) { tts = null }
        
        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
            tts = null
        }
    }

    // Dynamic Persona-based Human Speech Adaptation Pipeline (Rate, Pitch, and Voice signature)
    LaunchedEffect(tts, uiState.selectedPersona, selectedVoice, ttsLocale) {
        tts?.let { engine ->
            val persona = uiState.selectedPersona
            
            // 1. Map Selected Persona to human-like voice characteristics (Speech Rate & Pitch)
            val (rate, pitch) = when (persona) {
                "Senior Teacher" -> Pair(0.85f, 1.0f)           // Warm, patient, and instructional
                "Senior Software Engineer" -> Pair(0.95f, 0.9f)  // Crisp, professional, analytical lower pitch
                "Friend" -> Pair(1.02f, 1.1f)                    // Warm, energetic, faster speech & upbeat pitch
                else -> Pair(0.92f, 1.0f)                        // General Companion / Default
            }
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
            
            // 2. Select corresponding optimal system voice signature dynamically
            val systemVoices = engine.voices?.toList() ?: emptyList()
            val isMale = if (selectedVoice != "Default") {
                selectedVoice.contains("Male")
            } else {
                persona == "Senior Software Engineer"
            }
            val voiceToUse = com.chhanda.ai.util.TtsVoiceFilter.findBestVoice(systemVoices, ttsLocale, isMale)
            voiceToUse?.let { engine.voice = it }
        }
    }

    // Auto-Submit prompt when user stops speaking (silence detected)
    LaunchedEffect(voiceListening) {
        if (voiceListening) {
            wasListening = true
        } else if (wasListening) {
            wasListening = false
            // Wait 200ms for final SpeechRecognizer results to safely stream and append
            kotlinx.coroutines.delay(200)
            val finalPrompt = inputText.trim()
            if (finalPrompt.isNotEmpty()) {
                wasSentViaVoice = true
                viewModel.sendMessage(finalPrompt)
                inputText = ""
                viewModel.clearVoiceResult()
            } else if (isContinuousVoiceActive) {
                // If the prompt is empty but continuous voice is active, restart listening!
                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    viewModel.startVoiceInput()
                }
            }
        }
    }

    // Auto-Speak response aloud when LLM finishes generating (if query was dictated)
    LaunchedEffect(uiState.isGenerating) {
        if (!uiState.isGenerating && wasSentViaVoice) {
            wasSentViaVoice = false
            val lastMessage = uiState.messages.lastOrNull()
            if (lastMessage != null && lastMessage.role == "model") {
                val msgIdStr = lastMessage.id.toString()
                tts?.stop()
                val rawTextToSpeak = if (lastMessage.thinking != null) lastMessage.text else parseMessageContent(lastMessage.text).second
                val textToSpeak = rawTextToSpeak.replace("""</?[a-zA-Z_][a-zA-Z0-9_\-:]*[^>]*>""".toRegex(), "").trim()
                val cleanText = cleanTextForTts(textToSpeak)
                if (cleanText.isNotEmpty()) {
                    activeTtsMessageId = msgIdStr
                    activeTtsText = cleanText
                    ttsOffset = 0
                    ttsProgress = 0f
                    
                    tts?.let { engine ->
                        val detectedLocale = when {
                            containsBengali(cleanText) -> java.util.Locale("bn", "IN")
                            containsDevanagari(cleanText) -> java.util.Locale("hi", "IN")
                            else -> when (appLanguage) {
                                "Bengali" -> java.util.Locale("bn", "BD")
                                "Hindi" -> java.util.Locale("hi", "IN")
                                else -> java.util.Locale("en", "IN")
                            }
                        }
                        
                        var result = engine.setLanguage(detectedLocale)
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            val fallback = when (detectedLocale.language) {
                                "bn" -> java.util.Locale("bn")
                                "hi" -> java.util.Locale("hi")
                                else -> java.util.Locale("en")
                            }
                            engine.setLanguage(fallback)
                        }
                        
                        val systemVoices = engine.voices?.toList() ?: emptyList()
                        val isMale = selectedVoice.contains("Male")
                        val voiceToUse = com.chhanda.ai.util.TtsVoiceFilter.findBestVoice(systemVoices, detectedLocale, isMale)
                        voiceToUse?.let { engine.voice = it }
                    }
                    
                    tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, msgIdStr)
                }
            }
        }
    }

    val totalItems = uiState.messages.size + (if (uiState.currentPartialResponse.isNotEmpty() || uiState.agentStatus != null) 1 else 0)
    val isAtBottom by remember { 
        derivedStateOf { 
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) true
            else {
                val lastVisibleItem = visibleItemsInfo.last()
                lastVisibleItem.index >= totalItems - 1
            }
        }
    }
    var lastSeenCount by remember { mutableIntStateOf(totalItems) }
    var unreadCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(totalItems) { 
        if (isAtBottom && totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
            lastSeenCount = totalItems
            unreadCount = 0
        } else {
            unreadCount = (totalItems - lastSeenCount).coerceAtLeast(0)
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            lastSeenCount = totalItems
            unreadCount = 0
        }
    }

    // Haptic feedback for streaming tokens
    LaunchedEffect(uiState.currentPartialResponse) {
        if (uiState.currentPartialResponse.isNotEmpty() && hapticsEnabled) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChhandaLogo(size = 28, modifier = Modifier)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (uiState.isModelLoaded) formatModelDisplayName(viewModel.modelName) else "No Active Model", 
                            fontWeight = FontWeight.ExtraBold, 
                            fontSize = 18.sp, 
                            modifier = Modifier
                        ) 
                    }
                },
                actions = {
                    IconButton(onClick = { showCloseConfirm = true }) { Icon(Icons.Default.Close, contentDescription = "Close Chat") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Model Loading/Error Banner
            if (uiState.isModelLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            } else if (!uiState.isModelLoaded && uiState.messages.isEmpty()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(Localization.getString("no_active_model", appLanguage), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Message List
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (uiState.messages.isEmpty() && !uiState.isGenerating) {
                    item { PersonaSelectionHeader(appLanguage) }
                    item { PersonaSelectionGrid(uiState.selectedPersona, { viewModel.setPersona(it) }, appLanguage) }
                }

                itemsIndexed(items = uiState.messages, key = { _, item -> item.id }) { index, message ->
                    MessageBubble(
                        message = message, 
                        tts = tts,
                        isActiveTts = activeTtsMessageId == message.id.toString(),
                        isGenerating = index == uiState.messages.lastIndex && uiState.isGenerating,
                        hapticsEnabled = hapticsEnabled,
                        onTtsToggle = {
                            val msgIdStr = message.id.toString()
                            if (activeTtsMessageId == msgIdStr) {
                                tts?.stop()
                                activeTtsMessageId = null
                                isTtsPlaying = false
                            } else {
                                tts?.stop()
                                val textToSpeak = if (message.thinking != null) message.text else parseMessageContent(message.text).second
                                val cleanText = cleanTextForTts(textToSpeak)
                                if (cleanText.isNotEmpty()) {
                                    activeTtsMessageId = msgIdStr
                                    activeTtsText = cleanText
                                    ttsOffset = 0
                                    ttsProgress = 0f
                                    
                                    tts?.let { engine ->
                                        val detectedLocale = when {
                                            containsBengali(cleanText) -> java.util.Locale("bn", "IN")
                                            containsDevanagari(cleanText) -> java.util.Locale("hi", "IN")
                                            else -> when (appLanguage) {
                                                "Bengali" -> java.util.Locale("bn", "BD")
                                                "Hindi" -> java.util.Locale("hi", "IN")
                                                else -> java.util.Locale("en", "IN")
                                            }
                                        }
                                        
                                        var result = engine.setLanguage(detectedLocale)
                                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                                            val fallback = when (detectedLocale.language) {
                                                "bn" -> java.util.Locale("bn")
                                                "hi" -> java.util.Locale("hi")
                                                else -> java.util.Locale("en")
                                            }
                                            engine.setLanguage(fallback)
                                        }
                                        
                                        val systemVoices = engine.voices?.toList() ?: emptyList()
                                        val isMale = selectedVoice.contains("Male")
                                        val voiceToUse = com.chhanda.ai.util.TtsVoiceFilter.findBestVoice(systemVoices, detectedLocale, isMale)
                                        voiceToUse?.let { engine.voice = it }
                                    }
                                    
                                    tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, msgIdStr)
                                }
                            }
                        },
                        onSourceClick = { sourceName ->
                            systemViewModel.openFileByName(sourceName)
                        },
                        onCopyClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                            if (hapticsEnabled) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onEditClick = { newText ->
                            viewModel.editAndRetryMessage(message.id, newText)
                        },
                        onRetryClick = {
                            viewModel.retryMessage(message.id)
                        },
                        onLikeClick = { isLiked ->
                            viewModel.updateMessageFeedback(message.id, isLiked)
                        }
                    )
                }

                if (uiState.agentStatus != null) {
                    item(key = "agent_status") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = uiState.agentStatus!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (uiState.currentPartialResponse.isNotEmpty()) {
                    item(key = "streaming") { StreamingBubble(uiState.currentPartialResponse, uiState.currentTps) }
                }

                uiState.error?.let { item(key = "error") { ErrorBubble(it) } }
            }
            
            // Floating Scroll to Bottom Button
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isAtBottom,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    ExtendedFloatingActionButton(
                        onClick = { 
                            scope.launch { listState.animateScrollToItem(totalItems - 1) }
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        elevation = FloatingActionButtonDefaults.elevation(4.dp),
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to Bottom", modifier = Modifier.size(18.dp))
                        if (unreadCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.error,
                                shape = CircleShape,
                                modifier = Modifier.size(18.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        unreadCount.toString(), 
                                        color = Color.White, 
                                        fontSize = 10.sp, 
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // TTS Bar
            if (activeTtsMessageId != null) {
                GlobalTtsPlayer(
                    progress = ttsProgress,
                    isPlaying = isTtsPlaying,
                    onStop = { 
                        tts?.stop()
                        activeTtsMessageId = null
                        isTtsPlaying = false
                        isContinuousVoiceActive = false
                    },
                    onTogglePlay = {
                        if (isTtsPlaying) { 
                            tts?.stop()
                            isTtsPlaying = false
                            isContinuousVoiceActive = false
                        } else { 
                            tts?.speak(activeTtsText.substring(ttsOffset), TextToSpeech.QUEUE_FLUSH, null, activeTtsMessageId)
                            isTtsPlaying = true 
                        }
                    },
                    onSeek = { pos ->
                        val idx = (pos * activeTtsText.length).toInt().coerceIn(0, activeTtsText.length)
                        tts?.stop(); ttsOffset = idx
                        tts?.speak(activeTtsText.substring(idx), TextToSpeech.QUEUE_FLUSH, null, activeTtsMessageId); isTtsPlaying = true
                    },
                    onForward = { /* ... */ },
                    onBackward = { /* ... */ }
                )
            }

            // Input
            val selectedFiles by viewModel.selectedFiles.collectAsStateWithLifecycle()
            if (selectedFiles.isNotEmpty()) {
                Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    selectedFiles.forEach { AttachmentPreview(it, { viewModel.removeFile(it) }) }
                }
            }

            ChatInput(
                text = inputText,
                onTextChange = {
                    inputText = it
                    if (it.isNotEmpty() && !voiceListening) {
                        isContinuousVoiceActive = false
                    }
                },
                onSend = { 
                    isContinuousVoiceActive = false
                    viewModel.sendMessage(inputText)
                    inputText = "" 
                },
                onStop = { viewModel.stopInference() },
                onAttach = { viewModel.addFile(it) },
                onRefine = { 
                    isContinuousVoiceActive = false
                    viewModel.refineText(inputText)
                    inputText = "" 
                },
                isGenerating = uiState.isGenerating,
                isListening = voiceListening,
                onVoiceClick = {
                    if (voiceListening) {
                        isContinuousVoiceActive = false
                        viewModel.stopVoiceInput()
                    } else {
                        isContinuousVoiceActive = true
                        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            viewModel.startVoiceInput()
                        } else {
                            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                appLanguage = appLanguage,
                isReadOnly = isReadOnly,
                hapticsEnabled = hapticsEnabled
            )
        }
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Close Chat", fontWeight = FontWeight.Black) },
            text = { Text("Are you sure you want to exit? Your history is saved.") },
            confirmButton = { TextButton(onClick = { navController.popBackStack() }) { Text("EXIT") } },
            dismissButton = { TextButton(onClick = { showCloseConfirm = false }) { Text("CANCEL") } }
        )
    }
}

private fun containsBengali(text: String): Boolean {
    for (char in text) {
        if (char in '\u0980'..'\u09FF') return true
    }
    return false
}

private fun containsDevanagari(text: String): Boolean {
    for (char in text) {
        if (char in '\u0900'..'\u097F') return true
    }
    return false
}
