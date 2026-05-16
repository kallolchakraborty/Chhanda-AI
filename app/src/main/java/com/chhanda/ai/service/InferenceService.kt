package com.chhanda.ai.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class InferenceService : Service() {

    @Inject lateinit var engine: LLMEngine
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Cached telemetry values updated via Flow collection
    @Volatile private var cachedLoadingProgress = 0f
    @Volatile private var cachedPerformanceMetrics = 0.0

    override fun onCreate() {
        super.onCreate()
        // Observe telemetry flows and cache latest values for synchronous AIDL access
        serviceScope.launch {
            engine.loadingProgress.collect { cachedLoadingProgress = it }
        }
        serviceScope.launch {
            engine.performanceMetrics.collect { cachedPerformanceMetrics = it }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IInferenceService.Stub() {
        override fun initModel(path: String) {
            serviceScope.launch { try { engine.initModel(path) } catch (e: Exception) { Log.e("InferenceService", "initModel error: ${e.message}") } }
        }

        override fun generateResponse(
            prompt: String, roles: List<String>, texts: List<String>, 
            systemInstruction: String?, attachments: List<String>, callback: IInferenceCallback
        ) {
            val history = roles.zip(texts)
            val attachmentUris = attachments.map { android.net.Uri.parse(it) }
            serviceScope.launch {
                try {
                    engine.generateResponse(prompt, history, systemInstruction, attachmentUris).collect { update ->
                        when (update) {
                            is TokenUpdate.Partial -> try { callback.onToken(update.text, update.tps) } catch (e: Exception) {}
                            is TokenUpdate.Final -> try { callback.onToken("", update.tps) } catch (e: Exception) {}
                            is TokenUpdate.Error -> try { callback.onError(update.message) } catch (e: Exception) {}
                        }
                    }
                    try { callback.onComplete() } catch (e: Exception) {}
                } catch (e: Exception) { try { callback.onError(e.message ?: "Unknown error") } catch (ex: Exception) {} }
            }
        }

        override fun resetSession() { serviceScope.launch { try { engine.resetSession() } catch (e: Exception) {} } }
        override fun isSessionActive(): Boolean = engine.isSessionActive()
        override fun stopInference() { engine.stopInference() }
        override fun isModelLoaded(): Boolean = engine.isModelLoaded()
        override fun isModelLoading(): Boolean = engine.isModelLoading()
        override fun getCurrentModelName(): String = engine.getCurrentModelName()
        override fun closeEngine() { serviceScope.launch { try { engine.close() } catch (e: Exception) {} } }
        override fun getLoadingProgress(): Float = cachedLoadingProgress
        override fun getPerformanceMetrics(): Double = cachedPerformanceMetrics
    }

    override fun onDestroy() { super.onDestroy(); serviceScope.cancel() }
}
