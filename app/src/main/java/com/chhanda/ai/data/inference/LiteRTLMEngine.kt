package com.chhanda.ai.data.inference

import android.content.Context
import android.util.Log
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.util.ThermalStatusTracker
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import android.net.Uri

@Singleton
class LiteRTLMEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thermalTracker: com.chhanda.ai.util.ThermalStatusTracker,
    private val memoryMonitor: com.chhanda.ai.util.MemoryPressureMonitor
) : LLMEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModelPath: String? = null
    private val isGenerating = AtomicBoolean(false)
    
    private val inferenceDispatcher = Dispatchers.IO.limitedParallelism(1)
    
    private val _performanceMetrics = MutableStateFlow(0.0)
    override val performanceMetrics: Flow<Double> = _performanceMetrics.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0f)
    override val loadingProgress: Flow<Float> = _loadingProgress.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    override val isModelLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isModelLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    companion object { private const val TAG = "LiteRTLMEngine" }
    
    private val initMutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun initModel(path: String) {
        if (currentModelPath == path && _isLoaded.value) return
        
        if (!initMutex.tryLock()) {
            Log.w(TAG, "Initialization already in progress")
            return
        }
        
        try {
            _isLoading.value = true
            _loadingProgress.value = 0.1f
            
            withContext(inferenceDispatcher) {
                try {
                    _loadingProgress.value = 0.3f
                    // Close any existing engine
                    conversation?.close()
                    conversation = null
                    engine?.close()
                    
                    // PROACTIVE FIX: Delete stale/corrupted xnnpack_cache files to prevent silent token corruption (such as generating "Hiowpy" for "Hi").
                    try {
                        val modelDir = File(path).parentFile
                        modelDir?.listFiles()?.forEach { file ->
                            if (file.name.contains(".xnnpack_cache")) {
                                Log.i(TAG, "Proactively deleting stale cache: ${file.name}")
                                file.delete()
                            }
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to proactively delete cache: ${ex.message}")
                    }

                    val config = EngineConfig(modelPath = path, maxNumTokens = 4096)
                    engine = Engine(config)
                    
                    _loadingProgress.value = 0.6f
                    try {
                        engine!!.initialize()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to initialize engine, deleting cache and retrying: ${e.message}")
                        try {
                            val modelDir = File(path).parentFile
                            modelDir?.listFiles()?.forEach { file ->
                                if (file.name.contains(".xnnpack_cache")) {
                                    file.delete()
                                }
                            }
                            // Re-initialize engine
                            engine?.close()
                            engine = Engine(config)
                            engine!!.initialize()
                        } catch (retryEx: Throwable) {
                            Log.e(TAG, "Retry initialization failed", retryEx)
                            throw retryEx
                        }
                    }
                    
                    currentModelPath = path
                    _isLoaded.value = true
                    _loadingProgress.value = 1.0f
                    Log.i(TAG, "Model loaded successfully: ${File(path).name}")
                } catch (e: Throwable) {
                    Log.e(TAG, "Model load failed: ${e.message}", e)
                    _isLoaded.value = false
                    // IMMEDIATE LOG FOR CROSS-PROCESS CRASH VISIBILITY
                    try {
                        File(context.filesDir, "inference_crash.txt").writeText(
                            "TIMESTAMP: ${System.currentTimeMillis()}\n" +
                            "ERROR: ${e.message}\n" +
                            "STACKTRACE: ${Log.getStackTraceString(e)}"
                        )
                    } catch (ex: Exception) {}
                    throw e
                } finally {
                    _isLoading.value = false
                }
            }
        } finally {
            initMutex.unlock()
        }
    }

    override fun generateResponse(
        prompt: String, history: List<Pair<String, String>>, systemInstruction: String?, attachments: List<Uri>
    ): Flow<TokenUpdate> = flow {
        if (!_isLoaded.value) { emit(TokenUpdate.Error("Model not loaded")); return@flow }
        if (isGenerating.getAndSet(true)) { emit(TokenUpdate.Error("Engine busy")); return@flow }

        try {
            val startTime = System.currentTimeMillis()
            var tokenCount = 0
            
            // Thermal-aware adaptive sampling
            val thermal = thermalTracker.thermalStatus.value
            if (thermal.isThrottled) {
                Log.w(TAG, "Thermal throttling active ($thermal), reducing inference load")
            }
            
            // Reuse existing conversation for stateful on-device chat to preserve native multi-turn history.
            // Stateless API calls invoke resetSession() first, which sets conversation to null, ensuring clean sessions.
            if (conversation == null) {
                val samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = 0.7
                )
                val config = if (systemInstruction != null && systemInstruction.isNotBlank()) {
                    ConversationConfig(
                        systemInstruction = Contents.of(listOf(Content.Text(systemInstruction))),
                        samplerConfig = samplerConfig
                    )
                } else {
                    ConversationConfig(samplerConfig = samplerConfig)
                }
                conversation = engine?.createConversation(config)
            }

            // VISION SUPPORT: Package text and images into a multimodal request
            val contentList = mutableListOf<Content>()
            contentList.add(Content.Text(prompt))
            
            attachments.forEach { uri ->
                try {
                    if (isImageUri(uri)) {
                        val bitmap = loadBitmap(uri)
                        if (bitmap != null) {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
                            contentList.add(Content.ImageBytes(stream.toByteArray()))
                            Log.d(TAG, "Attached image to inference: $uri")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process image attachment $uri: ${e.message}")
                }
            }

            var finalFullText = ""
            var finalTps = 0.0
            conversation?.let { conv ->
                val request = if (contentList.size > 1) Contents.of(contentList) else Contents.of(listOf(Content.Text(prompt)))
                conv.sendMessageAsync(request).collect { message ->
                    val delta = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    
                    if (delta.isNotEmpty()) {
                        finalFullText += delta
                        Log.d("RAW_LLM", "Delta: '$delta', Full: '$finalFullText'")
                        
                        tokenCount++
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        val tps = if (elapsed > 0) tokenCount / elapsed else 0.0
                        _performanceMetrics.value = tps
                        finalTps = tps
                        emit(TokenUpdate.Partial(delta, tps))

                        // ACTIVE THERMAL THROTTLING:
                        // If system is throttled, insert a delay to let the SoC cool down
                        if (thermalTracker.thermalStatus.value.isThrottled) {
                            kotlinx.coroutines.delay(50) 
                        }
                    }
                }
            }
            val duration = System.currentTimeMillis() - startTime
            emit(TokenUpdate.Final(finalFullText, finalTps, duration))
        } catch (e: Throwable) {
            Log.e(TAG, "Inference crash: ${e.message}", e)
            emit(TokenUpdate.Error(e.localizedMessage ?: "Critical inference error"))
        } finally {
            isGenerating.set(false)
        }
    }.flowOn(inferenceDispatcher)

    override suspend fun resetSession() {
        withContext(inferenceDispatcher) {
            conversation?.close()
            conversation = null
        }
    }

    override fun isSessionActive(): Boolean = conversation != null
    
    override fun stopInference() {
        try {
            conversation?.cancelProcess()
        } catch (e: Exception) {
            Log.w(TAG, "Cancel failed: ${e.message}")
        }
    }
    
    override fun getCurrentModelName(): String = currentModelPath?.let { File(it).name } ?: "None"
    
    override fun isMultimodal(): Boolean {
        val name = currentModelPath?.lowercase() ?: return false
        return name.contains("multimodal") || name.contains("llava") || name.contains("moondream")
    }

    override suspend fun close() {
        withContext(inferenceDispatcher) {
            conversation?.close()
            conversation = null
            engine?.close()
            engine = null
            _isLoaded.value = false
            currentModelPath = null
        }
    }

    private fun isImageUri(uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri)
        if (type?.startsWith("image/") == true) return true
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        return ext in listOf("jpg", "jpeg", "png", "webp", "bmp")
    }

    private fun loadBitmap(uri: Uri): android.graphics.Bitmap? {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { 
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }
            
            // Scaled loading for memory efficiency
            // Senior Fix: Adaptive scaling based on memory pressure
            val isLowMem = memoryMonitor.memoryLevel.value >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            val targetW = if (isLowMem) 256 else 512
            val targetH = if (isLowMem) 256 else 512
            
            if (isLowMem) Log.w("LiteRTLMEngine", "Low memory detected: Scaling image down to ${targetW}px")
            
            var scale = 1
            while (options.outWidth / scale / 2 >= targetW && options.outHeight / scale / 2 >= targetH) {
                scale *= 2
            }
            
            val loadOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            context.contentResolver.openInputStream(uri)?.use { 
                android.graphics.BitmapFactory.decodeStream(it, null, loadOptions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap load failed: ${e.message}")
            null
        }
    }
}
