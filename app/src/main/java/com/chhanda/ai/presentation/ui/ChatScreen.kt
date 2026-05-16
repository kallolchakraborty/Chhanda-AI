package com.chhanda.ai.presentation.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
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
                        
                        if (selectedVoice != "Default") {
                            val isMale = selectedVoice.contains("Male")
                            val systemVoices = engine.voices?.toList() ?: emptyList()
                            val pool = systemVoices.filter { v -> v.locale.language == ttsLocale.language }
                                .filter { v ->
                                    val name = v.name.lowercase()
                                    if (isMale) name.contains("male") || name.contains("-m-") || name.contains("boy")
                                    else name.contains("female") || name.contains("-f-") || name.contains("girl")
                                }
                            pool.firstOrNull()?.let { engine.voice = it }
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

    val totalItems = uiState.messages.size + (if (uiState.currentPartialResponse.isNotEmpty() || uiState.agentStatus != null) 1 else 0)
    LaunchedEffect(totalItems) { if (totalItems > 0) listState.animateScrollToItem(totalItems - 1) }

    // Haptic feedback for streaming tokens
    LaunchedEffect(uiState.currentPartialResponse) {
        if (uiState.currentPartialResponse.isNotEmpty()) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        with(sharedTransitionScope) {
                            ChhandaLogo(size = 32, modifier = Modifier.sharedElement(sharedTransitionScope.rememberSharedContentState(key = "model_logo_${viewModel.modelName}"), animatedVisibilityScope = animatedVisibilityScope))
                        }
                        Spacer(Modifier.width(12.dp))
                        with(sharedTransitionScope) {
                            Text(viewModel.modelName, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge, modifier = Modifier.sharedElement(sharedTransitionScope.rememberSharedContentState(key = "model_name_${viewModel.modelName}"), animatedVisibilityScope = animatedVisibilityScope)) 
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showCloseConfirm = true }) { Icon(Icons.Default.Close, null) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Model Loading/Error Banner
            if (uiState.isModelLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            } else if (!uiState.isModelLoaded) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
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
                                    tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, msgIdStr)
                                }
                            }
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
            
            // TTS Bar
            if (activeTtsMessageId != null) {
                GlobalTtsPlayer(
                    progress = ttsProgress,
                    isPlaying = isTtsPlaying,
                    onStop = { tts?.stop(); activeTtsMessageId = null; isTtsPlaying = false },
                    onTogglePlay = {
                        if (isTtsPlaying) { tts?.stop(); isTtsPlaying = false }
                        else { tts?.speak(activeTtsText.substring(ttsOffset), TextToSpeech.QUEUE_FLUSH, null, activeTtsMessageId); isTtsPlaying = true }
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
                onTextChange = { inputText = it },
                onSend = { viewModel.sendMessage(inputText); inputText = "" },
                onStop = { viewModel.stopInference() },
                onAttach = { viewModel.addFile(it) },
                onRefine = { viewModel.refineText(inputText); inputText = "" },
                isGenerating = uiState.isGenerating,
                appLanguage = appLanguage,
                isReadOnly = isReadOnly
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
