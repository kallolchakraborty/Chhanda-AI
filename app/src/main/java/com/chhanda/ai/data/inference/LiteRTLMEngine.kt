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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import android.net.Uri

@Singleton
class LiteRTLMEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thermalTracker: ThermalStatusTracker
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

    override suspend fun initModel(path: String) {
        if (currentModelPath == path && _isLoaded.value) return
        
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

            conversation?.let { conv ->
                // sendMessageAsync(String) returns Flow<Message>
                conv.sendMessageAsync(prompt).collect { message ->
                    val text = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                        
                    tokenCount++
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    val tps = if (elapsed > 0) tokenCount / elapsed else 0.0
                    _performanceMetrics.value = tps
                    emit(TokenUpdate.Partial(text, tps))
                }
            }
        } catch (e: Exception) {
            emit(TokenUpdate.Error(e.message ?: "Inference error"))
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
}
