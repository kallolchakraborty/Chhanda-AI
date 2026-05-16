package com.chhanda.ai.data.inference

import android.content.Context
import android.util.Log
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.util.ThermalStatusTracker
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
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
    private val _isLoading = MutableStateFlow(false)

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
                    
                    val config = EngineConfig(modelPath = path)
                    engine = Engine(config)
                    
                    _loadingProgress.value = 0.6f
                    engine!!.initialize()
                    
                    currentModelPath = path
                    _isLoaded.value = true
                    _loadingProgress.value = 1.0f
                    Log.i(TAG, "Model loaded successfully: ${File(path).name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Model load failed: ${e.message}")
                    _isLoaded.value = false
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
            
            if (conversation == null) {
                conversation = engine?.createConversation()
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

            var emittedTextLength = 0
            conversation?.let { conv ->
                val request = if (contentList.size > 1) Contents.of(contentList) else Contents.of(listOf(Content.Text(prompt)))
                conv.sendMessageAsync(request).collect { message ->
                    val fullText = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    
                    if (fullText.length > emittedTextLength) {
                        val delta = fullText.substring(emittedTextLength)
                        emittedTextLength = fullText.length
                        
                        tokenCount++
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        val tps = if (elapsed > 0) tokenCount / elapsed else 0.0
                        _performanceMetrics.value = tps
                        emit(TokenUpdate.Partial(delta, tps))

                        // ACTIVE THERMAL THROTTLING:
                        // If system is throttled, insert a delay to let the SoC cool down
                        if (thermalTracker.thermalStatus.value.isThrottled) {
                            kotlinx.coroutines.delay(50) 
                        }
                    }
                }
            }
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
    
    override fun isModelLoaded(): Boolean = _isLoaded.value
    override fun isModelLoading(): Boolean = _isLoading.value
    override fun getCurrentModelName(): String = currentModelPath?.let { File(it).name } ?: "None"
    
    override fun isMultimodal(): Boolean {
        val name = currentModelPath?.lowercase() ?: return false
        return name.contains("e4b") || name.contains("multimodal") || name.contains("llava") || name.contains("moondream")
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
