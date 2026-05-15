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
    private val settingsRepository: com.chhanda.ai.data.repository.SettingsRepository,
    private val thermalStatusTracker: com.chhanda.ai.util.ThermalStatusTracker
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

    /**
     * Initializes the LLM engine with the specified model file path.
     * This is a heavy operation that handles RAM management and thermal safety.
     * @param path Absolute path to the .litertlm model file.
     */
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
        // Tear down any existing engine instance to free up memory before allocating new space.
        close()
        
        // MANDATORY MEMORY FLUSH: Manually triggering GC to ensure the JVM releases heap space.
        System.gc()
        Runtime.getRuntime().gc()
        
        // CRITICAL RESOURCE LOCK: We enforce a 2.5s delay to allow the Android OS and 
        // native C++ Garbage Collector to fully deallocate the massive (~2GB) model file from RAM.
        // This prevents "Resource Busy" errors or instant crashes on model switching.
        val timeSinceClose = System.currentTimeMillis() - lastEngineCloseTime
        if (timeSinceClose < 2500L) {
            val waitTime = 2500L - timeSinceClose
            Log.d(TAG, "Waiting ${waitTime}ms for native RAM flush...")
            delay(waitTime)
        }

        withContext(inferenceDispatcher) {
            val file = File(path)
            if (!file.exists()) throw Exception("Model file not found: $path")

            // Security Check: Verify the file isn't an HTML error stub from a failed HF download.
            if (file.length() < 1_000_000L) {
                throw Exception("Model file too small. It might be an HTML error page. Check your internet/Token.")
            }

            val thermalStatus = thermalStatusTracker.thermalStatus.value
            val isHot = thermalStatus == "Fair" || thermalStatus == "Serious" || thermalStatus == "Critical"
            val isCritical = thermalStatus == "Critical" || thermalStatus == "Emergency"

            val contextLengthStr = settingsRepository.contextLengthFlow.first()
            val baseContextLength = contextLengthStr.toIntOrNull() ?: 2048
            
            // Senior Throttling: If critical, cut context by 50% to save memory and reduce compute load
            val contextLength = if (isCritical) baseContextLength / 2 else baseContextLength
            Log.d(TAG, "Configured context window: $contextLength tokens (Base: $baseContextLength, Thermal: $thermalStatus)")
            
            val turboQuantEnabled = settingsRepository.turboQuantEnabledFlow.first() || isHot
            Log.d(TAG, "TurboQuant feature status: $turboQuantEnabled (Forced=$isHot due to thermal $thermalStatus)")

            var currentContextLength = contextLength
            var loaded = false
            var attempts = 0
            
            // SENIOR HARDENING: Detect if GPU initialization crashed the process last time
            val gpuFailureMarker = java.io.File(context.filesDir, "gpu_init_ongoing.marker")
            val forceCpu = gpuFailureMarker.exists()
            if (forceCpu) {
                Log.w(TAG, "Detected previous GPU crash! Forcing CPU fallback for this session.")
                try { gpuFailureMarker.delete() } catch(e: Exception) {}
            }

            while (!loaded && attempts < 3) {
                try {
                    if (!forceCpu && attempts == 0) {
                        Log.d(TAG, "Attempting GPU initialization with context: $currentContextLength")
                        try { gpuFailureMarker.createNewFile() } catch(e: Exception) {}
                        
                        val gpuConfig = EngineConfig(
                            modelPath = path,
                            backend = Backend.GPU(),
                            maxNumTokens = currentContextLength,
                            cacheDir = context.cacheDir.absolutePath
                        )
                        val engine = Engine(gpuConfig)
                        engine.initialize()
                        
                        // If we reached here, GPU init succeeded
                        try { gpuFailureMarker.delete() } catch(e: Exception) {}
                        llmInference = engine
                        loaded = true
                        Log.i(TAG, "Model loaded successfully on GPU")
                    } else {
                        throw Exception("Forcing CPU fallback")
                    }
                } catch (gpuError: Throwable) {
                    try { gpuFailureMarker.delete() } catch(e: Exception) {}
                    val gpuMsg = gpuError.localizedMessage ?: gpuError.javaClass.simpleName
                    Log.w(TAG, "GPU failed or bypassed: $gpuMsg, trying CPU fallback...")
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

    /**
     * Checks if a conversation session is currently active in the engine.
     */
    override fun isSessionActive(): Boolean = persistentSession != null

    /**
     * Core Inference Method: Generates a streaming response using LiteRT.
     * Implements concurrency control and stop-token filtering.
     */
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

        // ── Guardrails ────────────────────────────────────────────────────────
        val (auditedPrompt, isViolation) = com.chhanda.ai.util.SafetyGuardrails.auditInput(prompt)
        if (isViolation) {
            trySend(TokenUpdate.Error("Safety Guardrail: This prompt contains prohibited or sensitive content and cannot be processed."))
            isGenerating.set(false)
            this@callbackFlow.close()
            return@callbackFlow
        }
        
        val hardenedSystemPrompt = com.chhanda.ai.util.SafetyGuardrails.getHardenedSystemPrompt(systemInstruction)

        // ── Build user content (Increased limit to accommodate codebase context) ─
        val sanitized = if (auditedPrompt.length > 32000) auditedPrompt.takeLast(32000) else auditedPrompt
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
                    
                    val thermalStatus = thermalStatusTracker.thermalStatus.value
                    val isSerious = thermalStatus == "Serious" || thermalStatus == "Critical" || thermalStatus == "Emergency"
                    
                    val turboQuantEnabled = settingsRepository.turboQuantEnabledFlow.first() || isSerious
                    val extraContext = if (turboQuantEnabled) {
                        mapOf(
                            "enable_kv_cache_compression" to true,
                            "odml_turbo_quant" to true // Supporting both potential internal keys
                        )
                    } else {
                        emptyMap<String, Any>()
                    }

                    if (isSerious) {
                        Log.w(TAG, "Thermal Throttling Active during generation ($thermalStatus): Forcing TurboQuant=ON")
                    }

                    val session = llmInference!!.createConversation(
                        ConversationConfig(
                            samplerConfig = SamplerConfig(temperature = 0.8, topK = 40, topP = 0.95),
                            systemInstruction = com.google.ai.edge.litertlm.Contents.of(hardenedSystemPrompt),
                            initialMessages = initialMessages,
                            extraContext = extraContext
                        )
                    ).also {
                        persistentSession = it
                        Log.d(TAG, "Created new Conversation with ${initialMessages.size} history messages. TurboQuant=$turboQuantEnabled")
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
                                
                                // THERMAL PROTECTION: If device is heating up, inject small delays 
                                // into the native callback thread to slow down the generation loop.
                                val thermal = thermalStatusTracker.thermalStatus.value
                                when (thermal) {
                                    "Fair" -> Thread.sleep(5)
                                    "Serious" -> Thread.sleep(15)
                                    "Severe" -> Thread.sleep(30)
                                    "Critical" -> Thread.sleep(80)
                                    "Emergency" -> Thread.sleep(200)
                                }
                                
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
                                val responseTimeMs = System.currentTimeMillis() - startTime
                                val duration = responseTimeMs / 1000.0
                                val finalTps = if (duration > 0) tokenCount.get() / duration else 0.0
                                _performanceMetrics.value = finalTps
                                Log.i(TAG, "Generation complete. Final TPS: ${String.format("%.1f", finalTps)}")
                                timeoutJob?.cancel()
                                isGenerating.set(false)
                                trySend(TokenUpdate.Final(accumulated.toString().trimEnd(), tps = finalTps, responseTimeMs = responseTimeMs))
                                this@callbackFlow.close() 
                            } catch (err: Throwable) {
                                Log.e(TAG, "CRITICAL ERROR inside JNI onDone", err)
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            if (throwable is java.util.concurrent.CancellationException) {
                                Log.i(TAG, "The inference is cancelled.")
                                val responseTimeMs = System.currentTimeMillis() - startTime
                                val duration = responseTimeMs / 1000.0
                                val finalTps = if (duration > 0) tokenCount.get() / duration else 0.0
                                trySend(TokenUpdate.Final(accumulated.toString().trimEnd(), tps = finalTps, responseTimeMs = responseTimeMs))
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
