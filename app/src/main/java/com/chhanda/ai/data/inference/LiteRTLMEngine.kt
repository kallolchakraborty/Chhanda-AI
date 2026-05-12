package com.chhanda.ai.data.inference

import android.content.Context
import android.util.Log
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.MultimodalIngestor
import com.chhanda.ai.domain.model.TokenUpdate
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-backed LLM engine.
 *
 * KEY ARCHITECTURE DECISION:
 * A fresh [LlmInferenceSession] is created for every generateResponse() call and closed
 * in awaitClose(). session.close() is the ONLY MediaPipe API that actually stops the
 * underlying native C++ inference thread. future.cancel() does NOT stop it.
 * This prevents:
 *   - "Previous invocation still processing" errors
 *   - Phone overheating from background native inference
 */
@Singleton
class LiteRTLMEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ingestor: MultimodalIngestor,
    private val settingsRepository: com.chhanda.ai.data.repository.SettingsRepository
) : LLMEngine {

    // ── State ──────────────────────────────────────────────────────────────────
    private val inferenceDispatcher by lazy { 
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "InferenceThread")
        }.asCoroutineDispatcher()
    }

    @Volatile private var llmInference: Engine? = null
    @Volatile private var currentModelPath: String? = null
    private var lastEngineCloseTime = 0L

    @Volatile var lastLoadError: String? = null
        private set
    @Volatile var isLoading: Boolean = false
        private set

    /** True only while a generateResponse() callbackFlow is active. */
    private val isGenerating = AtomicBoolean(false)

    @Volatile private var persistentSession: Conversation? = null

    private val engineLock = Mutex()
    
    private val _performanceMetrics = MutableStateFlow(0.0)
    override val performanceMetrics: Flow<Double> = _performanceMetrics.asStateFlow()

    companion object {
        private const val TAG = "LiteRTLMEngine"
        // Stop tokens the model emits to signal end of turn
        private val STOP_TOKENS = listOf("<end_of_turn>", "<start_of_turn>", "User:", "Human:", "###", "<eos>")
        private val MAX_STOP_TOKEN_LEN = STOP_TOKENS.maxOf { it.length }
    }

    // ── Model Init ─────────────────────────────────────────────────────────────

    override suspend fun initModel(path: String) {
        Log.d(TAG, "initModel: $path")
        if (currentModelPath == path && llmInference != null) {
            Log.d(TAG, "Already loaded. Skipping.")
            return
        }
        isLoading = true
        lastLoadError = null
        try {
            loadModel(path)
            Log.i(TAG, "Model ready: $path")
        } catch (e: Throwable) {
            lastLoadError = e.message ?: "Unknown load error"
            Log.e(TAG, "Model load FAILED: ${e.message}")
            throw e
        } finally {
            isLoading = false
        }
    }

    private suspend fun loadModel(path: String) = engineLock.withLock {
        // Tear down any existing engine
        close()
        
        // NEW: Mandatory Memory Flush
        System.gc()
        Runtime.getRuntime().gc()
        
        // CRITICAL: Mathematically guarantee C++ Garbage Collector has time to release 
        // the 2.5GB file from RAM. No matter how many times close() is called, 
        // we force at least a 2500ms gap before allocating new RAM.
        val timeSinceClose = System.currentTimeMillis() - lastEngineCloseTime
        if (timeSinceClose < 2500L) {
            val waitTime = 2500L - timeSinceClose
            Log.d(TAG, "Waiting ${waitTime}ms for native RAM flush...")
            delay(waitTime)
        }

        withContext(inferenceDispatcher) {
            val file = File(path)
            if (!file.exists()) throw Exception("Model file not found: $path")

            if (file.length() < 1_000_000L) {
                throw Exception("Model file too small. It might be an HTML error page from HuggingFace. Check your HF Token.")
            }

            // NEW: Corrupted-stub detection
            val firstBytes = try { file.inputStream().use { it.readNBytes(10) } } catch(_: Exception) { byteArrayOf() }
            if (firstBytes.decodeToString().contains("<!DOC", ignoreCase = true) || 
                firstBytes.decodeToString().contains("<html", ignoreCase = true)) {
                file.delete()
                throw Exception("Downloaded file was an HTML error page (likely 401 Unauthorized). Please check your HF Token in Settings.")
            }

            Log.d(TAG, "Loading ${file.name} (${file.length() / 1_048_576} MB)...")

            val contextLengthStr = settingsRepository.contextLengthFlow.first()
            val contextLength = contextLengthStr.toIntOrNull() ?: 2048
            Log.d(TAG, "Dynamic context length: $contextLength")

            var currentContextLength = contextLength
            var loaded = false
            var attempts = 0
            
            while (!loaded && attempts < 3) {
                try {
                    Log.d(TAG, "Attempting GPU initialization with context: $currentContextLength")
                    val gpuConfig = EngineConfig(
                        modelPath = path,
                        backend = Backend.GPU(),
                        maxNumTokens = currentContextLength,
                        cacheDir = context.cacheDir.absolutePath
                    )
                    val engine = Engine(gpuConfig)
                    engine.initialize()
                    llmInference = engine
                    loaded = true
                    Log.i(TAG, "Model loaded successfully on GPU")
                } catch (gpuError: Throwable) {
                    val gpuMsg = gpuError.localizedMessage ?: gpuError.javaClass.simpleName
                    Log.w(TAG, "GPU failed: $gpuMsg, trying CPU fallback...")
                    try {
                        val cpuConfig = EngineConfig(
                            modelPath = path,
                            backend = Backend.CPU(),
                            maxNumTokens = currentContextLength,
                            cacheDir = context.cacheDir.absolutePath
                        )
                        val engine = Engine(cpuConfig)
                        engine.initialize()
                        llmInference = engine
                        loaded = true
                        Log.i(TAG, "Model loaded successfully on CPU")
                    } catch (cpuError: Throwable) {
                        val msg = cpuError.localizedMessage ?: cpuError.javaClass.simpleName
                        Log.e(TAG, "CPU fallback also failed: $msg")
                        
                        if (msg.contains("OutOfMemory", ignoreCase = true) || msg.contains("OOM", ignoreCase = true)) {
                            currentContextLength /= 2
                            if (currentContextLength < 512) {
                                try {
                                    context.cacheDir.deleteRecursively()
                                    Log.w(TAG, "Cleared cacheDir after initialization failure")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to clear cacheDir", e)
                                }
                                throw Exception("Out of memory even with minimal context. Gemma needs ~1.5GB free RAM.")
                            }
                            Log.w(TAG, "OOM detected. Retrying with context length: $currentContextLength")
                            attempts++
                        } else {
                            try {
                                context.cacheDir.deleteRecursively()
                            } catch (_: Exception) {}
                            if (msg.contains("metadata", ignoreCase = true)) {
                                throw Exception("Model metadata mismatch. Ensure you downloaded the exact .litertlm file.")
                            }
                            throw Exception("Engine Init Failed: $msg. Please restart your device.")
                        }
                    }
                }
            }
            currentModelPath = path
            isGenerating.set(false)
            persistentSession = null
        }
    }

    /**
     * Resets the persistent session. Call this when starting a new chat.
     */
    override suspend fun resetSession() {
        Log.d(TAG, "resetSession: closing persistent session.")
        isGenerating.set(false) 
        withContext(inferenceDispatcher) {
            try {
                persistentSession?.close()
                persistentSession = null
                Log.d(TAG, "Persistent session closed successfully.")
            } catch (e: Exception) {
                Log.w(TAG, "Session reset error: ${e.message}")
            }
        }
    }

    override fun isSessionActive(): Boolean = persistentSession != null

    // ── Inference ──────────────────────────────────────────────────────────────

    override fun generateResponse(
        prompt: String,
        history: List<Pair<String, String>>,
        systemInstruction: String?,
        attachments: List<android.net.Uri>
    ): Flow<TokenUpdate> = callbackFlow {

        // ── Guard: model must be loaded ────────────────────────────────────────
        val engine = llmInference
            ?: run {
                trySend(TokenUpdate.Error(lastLoadError ?: "Model not loaded. Go to Dashboard and activate a model."))
                this@callbackFlow.close()
                return@callbackFlow
            }

        // ── Guard: prevent concurrent inference ────────────────────────────────
        if (!isGenerating.compareAndSet(false, true)) {
            trySend(TokenUpdate.Error("Model is already generating a response. Please wait."))
            this@callbackFlow.close()
            return@callbackFlow
        }

        val startTime = System.currentTimeMillis()

        // ── Build user content (plain text, NO Gemma tags — session adds them) ─
        val userContent = if (attachments.isNotEmpty()) {
            val texts = attachments.map { uri ->
                try {
                    when {
                        uri.toString().contains("image") ||
                        uri.toString().endsWith(".jpg") ||
                        uri.toString().endsWith(".png") -> ingestor.ingestImage(uri)
                        uri.toString().endsWith(".pdf")  -> ingestor.ingestPdf(uri).joinToString("\n")
                        uri.toString().contains("audio") ||
                        uri.toString().endsWith(".wav") ||
                        uri.toString().endsWith(".mp3") -> ingestor.ingestAudio(uri)
                        else -> "Attachment: ${uri.lastPathSegment}"
                    }
                } catch (e: Exception) {
                    "Error ingesting ${uri.lastPathSegment}: ${e.localizedMessage}"
                }
            }
            "Context from attachments:\n${texts.joinToString("\n")}\n\n$prompt"
        } else {
            prompt
        }

        // Limit to ~3000 chars (approx 750 tokens), leaving room for generation without OOM
        val sanitized = if (userContent.length > 3000) userContent.takeLast(3000) else userContent
        Log.d(TAG, "Inference start. Prompt length=${sanitized.length} chars")

        // ── Robust Initialization with Retry ───────────────────
        var timeoutJob: Job? = null
        var retryCount = 0
        var initSuccess = false
        var lastInitError: Throwable? = null

        // Patiently wait up to 12 seconds
        while (retryCount < 30 && !initSuccess) {
            try {
                var isClosedForSend = false

                withContext(inferenceDispatcher) {
                    // 1. Establish the session
                    try { persistentSession?.close() } catch (e: Exception) { Log.w(TAG, "Failed to close old session: ${e.message}") }
                    
                    val initialMessages = history.map { (role, text) ->
                        if (role.equals("user", ignoreCase = true)) {
                            Message.user(text)
                        } else {
                            Message.model(text)
                        }
                    }
                    
                    val session = llmInference!!.createConversation(
                        ConversationConfig(
                            samplerConfig = SamplerConfig(temperature = 0.8, topK = 40, topP = 0.95),
                            systemInstruction = systemInstruction?.let { Contents.of(it) },
                            initialMessages = initialMessages
                        )
                    ).also {
                        persistentSession = it
                        Log.d(TAG, "Created new Conversation with ${initialMessages.size} history messages.")
                    }

                    // 2. Clear any lingering generating flags just before starting
                    isGenerating.set(true)
                    val accumulated = StringBuilder()
                    _performanceMetrics.value = 0.0
                    val startTime = System.currentTimeMillis()
                    val tokenCount = java.util.concurrent.atomic.AtomicInteger(0)
                    
                    // 3. Start async generation
                    session.sendMessageAsync(Contents.of(listOf(Content.Text(sanitized))), object : MessageCallback {
                        override fun onMessage(message: Message) {
                            try {
                                if (isClosedForSend) return

                                val chunk = message.toString()
                                accumulated.append(chunk)
                                val currentCount = tokenCount.incrementAndGet()
                                
                                // Real-time TPS update (update more frequently for smoother UI)
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                                if (elapsed > 0.1) {
                                    _performanceMetrics.value = currentCount / elapsed
                                }
                                trySend(TokenUpdate.Partial(chunk, tps = _performanceMetrics.value))
                            } catch (err: Throwable) {
                                Log.e(TAG, "CRITICAL ERROR inside JNI callback", err)
                                trySend(TokenUpdate.Error("System crashed during generation: ${err.message}"))
                            }
                        }

                        override fun onDone() {
                            try {
                                if (isClosedForSend) return
                                val duration = (System.currentTimeMillis() - startTime) / 1000.0
                                val finalTps = if (duration > 0) tokenCount.get() / duration else 0.0
                                _performanceMetrics.value = finalTps
                                Log.i(TAG, "Generation complete. Final TPS: ${String.format("%.1f", finalTps)}")
                                timeoutJob?.cancel()
                                isGenerating.set(false)
                                trySend(TokenUpdate.Final(accumulated.toString().trimEnd(), tps = finalTps))
                                this@callbackFlow.close() 
                            } catch (err: Throwable) {
                                Log.e(TAG, "CRITICAL ERROR inside JNI onDone", err)
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            if (throwable is java.util.concurrent.CancellationException) {
                                Log.i(TAG, "The inference is cancelled.")
                                val duration = (System.currentTimeMillis() - startTime) / 1000.0
                                val finalTps = if (duration > 0) tokenCount.get() / duration else 0.0
                                trySend(TokenUpdate.Final(accumulated.toString().trimEnd(), tps = finalTps))
                                isGenerating.set(false)
                                this@callbackFlow.close()
                            } else {
                                Log.e(TAG, "onError", throwable)
                                trySend(TokenUpdate.Error("System crashed during generation: ${throwable.message}"))
                                isGenerating.set(false)
                                this@callbackFlow.close()
                            }
                        }
                    }, emptyMap())
                }
                initSuccess = true
                Log.d(TAG, "Inference successfully started on attempt ${retryCount + 1}")
                
            } catch (e: Throwable) {
                lastInitError = e
                val isBusy = e.message?.contains("busy", ignoreCase = true) == true || 
                             e.message?.contains("pending", ignoreCase = true) == true
                                
                if (isBusy) {
                    Log.w(TAG, "LiteRT engine busy. Retrying in 400ms... (Attempt ${retryCount + 1}/30)")
                    delay(400)
                    retryCount++
                } else {
                    break // Unrecoverable error (e.g. OOM)
                }
            }
        }

        // ── Graceful Failure ───────────────────────────────────────────────────
        if (!initSuccess) {
            Log.e(TAG, "Inference failed to start after $retryCount retries", lastInitError)
            timeoutJob?.cancel()
            isGenerating.set(false)
            val isBusy = lastInitError?.message?.contains("busy", ignoreCase = true) == true
            if (isBusy) {
                trySend(TokenUpdate.Error("The AI engine is taking too long to finish its previous thought. Please restart the app."))
            } else {
                trySend(TokenUpdate.Error("Inference failed: ${lastInitError?.localizedMessage}"))
            }
            this@callbackFlow.close()
            return@callbackFlow
        }

        // ── awaitClose: called when flow is cancelled, stops native inference ──
        awaitClose {
            Log.d(TAG, "awaitClose: flow cancelled. Requesting native cancellation.")
            timeoutJob?.cancel()
            // CRITICAL: Stop the native inference if the flow is cancelled (e.g. user leaves screen)
            stopInference()
        }
    }.flowOn(Dispatchers.IO)

    // ── Control ────────────────────────────────────────────────────────────────
    private val teardownLock = Any()

    override fun stopInference() {
        Log.d(TAG, "stopInference requested — gracefully cancelling generation.")
        
        synchronized(teardownLock) {
            persistentSession?.let { session ->
                CoroutineScope(inferenceDispatcher).launch {
                    try { session.cancelProcess() } catch (e: Exception) { Log.w(TAG, "Session cancel error: ${e.message}") }
                }
            }
        }
        isGenerating.set(false)
    }

    override fun isModelLoaded(): Boolean = llmInference != null
    override fun isModelLoading(): Boolean = isLoading

    override fun getCurrentModelName(): String {
        return currentModelPath?.let { java.io.File(it).nameWithoutExtension } ?: "unknown"
    }

    override suspend fun close() {
        Log.d(TAG, "close: forcefully freeing native Engine resources")
        _performanceMetrics.value = 0.0
        withContext(inferenceDispatcher) {
            try {
                persistentSession?.close()
                persistentSession = null
                llmInference?.close()
                llmInference = null
                currentModelPath = null
                lastEngineCloseTime = System.currentTimeMillis()
                Log.d(TAG, "Engine and session closed successfully on inference thread.")
            } catch (e: Exception) {
                Log.w(TAG, "Close error: ${e.message}")
            }
        }
        isGenerating.set(false)
    }
}
