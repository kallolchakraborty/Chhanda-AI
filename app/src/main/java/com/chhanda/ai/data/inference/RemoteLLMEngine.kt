package com.chhanda.ai.data.inference

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.service.IInferenceCallback
import com.chhanda.ai.service.IInferenceService
import com.chhanda.ai.service.InferenceService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteLLMEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : LLMEngine {

    private var remoteService: IInferenceService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionState = MutableStateFlow(false)
    private var isBound = false
    private var lastModelPath: String? = null
    
    private val _performanceMetrics = MutableStateFlow(0.0)
    override val performanceMetrics: Flow<Double> = _performanceMetrics.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0f)
    override val loadingProgress: Flow<Float> = _loadingProgress.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    override val isModelLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isModelLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    companion object { private const val TAG = "RemoteLLMEngine" }

    init {
        // Defer binding to avoid race conditions during Hilt initialization
        scope.launch {
            delay(500) // Let Application.onCreate complete first
            bindToService()
        }
        // Start telemetry polling
        scope.launch {
            while (isActive) {
                if (connectionState.value) {
                    try {
                        val progress = remoteService?.getLoadingProgress() ?: 0f
                        val tps = remoteService?.getPerformanceMetrics() ?: 0.0
                        val isLoaded = remoteService?.isModelLoaded() ?: false
                        val isLoading = remoteService?.isModelLoading() ?: false
                        
                        if (_isLoaded.value != isLoaded) {
                            Log.i(TAG, "Sync: isModelLoaded changed to $isLoaded")
                        }
                        
                        _loadingProgress.value = progress
                        _performanceMetrics.value = tps
                        _isLoaded.value = isLoaded
                        _isLoading.value = isLoading
                    } catch (e: Exception) { 
                        Log.w(TAG, "Telemetry poll failed: ${e.message}") 
                    }
                } else {
                    _loadingProgress.value = 0f
                    _performanceMetrics.value = 0.0
                    _isLoaded.value = false
                    _isLoading.value = false
                }
                delay(500)
            }
        }
    }

    private fun bindToService() {
        if (isBound) return
        try {
            val intent = Intent(context, InferenceService::class.java)
            isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!isBound) {
                Log.e(TAG, "Failed to bind to InferenceService")
            }
        } catch (e: Exception) {
            Log.e(TAG, "bindToService error: ${e.message}")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteService = IInferenceService.Stub.asInterface(service)
            connectionState.value = true
            Log.i(TAG, "Connected to InferenceService")
            
            // Automatic Recovery: Re-init model if one was previously loaded
            lastModelPath?.let { path ->
                scope.launch {
                    try {
                        Log.i(TAG, "Restoring model state after reconnect: $path")
                        remoteService?.initModel(path)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore model state: ${e.message}")
                    }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            connectionState.value = false
            isBound = false
            Log.w(TAG, "Disconnected from InferenceService, reconnecting...")
            _isLoaded.value = false
            _isLoading.value = false
            _loadingProgress.value = 0f
            _performanceMetrics.value = 0.0
            scope.launch {
                delay(1000) // Backoff before reconnect
                bindToService()
            }
        }
    }

    override suspend fun initModel(path: String) {
        lastModelPath = path
        ensureConnected()
        try { remoteService?.initModel(path) } catch (e: Exception) {
            Log.e(TAG, "initModel IPC error: ${e.message}")
        }
    }

    override fun generateResponse(
        prompt: String, history: List<Pair<String, String>>, systemInstruction: String?, attachments: List<Uri>
    ): Flow<TokenUpdate> = callbackFlow {
        ensureConnected()
        val callback = object : IInferenceCallback.Stub() {
            private val accumulatedText = StringBuilder()
            private var lastTps = 0.0

            override fun onToken(text: String, tps: Double) {
                accumulatedText.append(text)
                lastTps = tps
                trySend(TokenUpdate.Partial(text, tps))
            }
            override fun onError(error: String) { trySend(TokenUpdate.Error(error)); close() }
            override fun onComplete() {
                trySend(TokenUpdate.Final(accumulatedText.toString(), lastTps))
                close()
            }
        }
        val roles = history.map { it.first }; val texts = history.map { it.second }
        val attachmentUris = attachments.map { it.toString() }
        try { remoteService?.generateResponse(prompt, roles, texts, systemInstruction ?: "", attachmentUris, callback) }
        catch (e: Exception) { trySend(TokenUpdate.Error("IPC Error: ${e.message}")); close() }
        awaitClose { }
    }

    override suspend fun resetSession() { try { remoteService?.resetSession() } catch (e: Exception) {} }
    override fun isSessionActive(): Boolean = try { remoteService?.isSessionActive ?: false } catch (e: Exception) { false }
    override fun stopInference() { try { remoteService?.stopInference() } catch (e: Exception) {} }
    override fun getCurrentModelName(): String = try { remoteService?.currentModelName ?: "None" } catch (e: Exception) { "None" }
    override fun isMultimodal(): Boolean = try { remoteService?.isMultimodal ?: false } catch (e: Exception) { false }
    override suspend fun close() {
        try {
            remoteService?.closeEngine()
        } catch (e: Exception) {
            Log.e(TAG, "close error: ${e.message}")
        }
        _isLoaded.value = false
        _isLoading.value = false
        _loadingProgress.value = 0f
        _performanceMetrics.value = 0.0
        lastModelPath = null
    }

    private suspend fun ensureConnected() {
        if (!isBound) bindToService()
        withTimeout(5000) { connectionState.first { it } }
    }
}
