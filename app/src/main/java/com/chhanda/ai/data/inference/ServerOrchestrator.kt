package com.chhanda.ai.data.inference

import java.io.File

import android.content.Context
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.data.repository.ModelProvisioner
import com.chhanda.ai.data.repository.SettingsRepository
import com.chhanda.ai.service.ChhandaForegroundService
import com.chhanda.ai.util.AppLogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chhandaServer: ChhandaServer,
    private val llmEngineLazy: dagger.Lazy<LLMEngine>,
    private val modelProvisioner: ModelProvisioner,
    private val settingsRepository: SettingsRepository,
    private val appLogManager: AppLogManager
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        appLogManager.addLog("CRASH", "Orchestrator Critical: ${throwable.message}", "ERROR")
        _isServerRunning.value = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val llmEngine get() = llmEngineLazy.get()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _boundPort = MutableStateFlow(0)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    private val _isLocalLinkOk = MutableStateFlow(true)
    val isLocalLinkOk: StateFlow<Boolean> = _isLocalLinkOk.asStateFlow()

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError.asStateFlow()

    val tunnelUrl = MutableStateFlow("") 
    val isTunnelActive = MutableStateFlow(false)

    init {
        startHealthCheck()
    }

    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading.asStateFlow()

    fun startServer() {
        if (_isModelLoading.value) return
        
        scope.launch {
            try {
                _isModelLoading.value = true
                appLogManager.addLog("SERVER", "Initializing local AI engine...", "INFO")
                // Ensure we have scanned models before checking for active one
                if (modelProvisioner.ownedModels.value.isEmpty() && modelProvisioner.sharedModels.value.isEmpty()) {
                    appLogManager.addLog("SERVER", "Refreshing model list...", "INFO")
                    modelProvisioner.refreshModels()
                    // Wait up to 2 seconds for scan to complete
                    withTimeoutOrNull(2000) {
                        modelProvisioner.ownedModels.first { it.isNotEmpty() || !modelProvisioner.isScanning.value }
                    }
                }
                
                // Use the persisted active model from settings as the source of truth
                val selectedModelName = settingsRepository.activeModelFlow.first()
                val allModels = modelProvisioner.ownedModels.value + modelProvisioner.sharedModels.value
                val activeModel = allModels.find { it.name == selectedModelName } 
                    ?: allModels.find { it.isActive } // Fallback to whatever is marked active
                
                if (activeModel == null) {
                    _isModelLoading.value = false
                    appLogManager.addLog("SERVER", "Startup failed: No active model selected", "ERROR")
                    _serverError.value = "No active model"
                    return@launch
                }

                // UNBLOCKING CRASH: Ensure model engine is ready before server binds
                val modelDir = File(context.getExternalFilesDir(null), "models")
                val modelFile = File(modelDir, activeModel.name)
                llmEngine.initModel(modelFile.absolutePath)
                
                val port = settingsRepository.serverPortFlow.first().toIntOrNull() ?: 8888
                val maxDevices = settingsRepository.maxDevicesFlow.first()

                ChhandaForegroundService.start(context, port, maxDevices)
                _isServerRunning.value = true
                _boundPort.value = port
                _serverError.value = null
                _isModelLoading.value = false
                appLogManager.addLog("SERVER", "Web Gateway active on port $port", "SUCCESS")
            } catch (e: Exception) {
                _isModelLoading.value = false
                appLogManager.addLog("SERVER", "Startup error: ${e.message}", "ERROR")
                _serverError.value = e.message
                _isServerRunning.value = false
            }
        }
    }

    fun stopServer() {
        scope.launch {
            try {
                ChhandaForegroundService.stop(context)
                llmEngine.close()
                _isServerRunning.value = false
                _boundPort.value = 0
                modelProvisioner.refreshModels()
                appLogManager.addLog("SERVER", "Web Gateway stopped by user", "WARN")
            } catch (e: Exception) {
                appLogManager.addLog("SERVER", "Shutdown error: ${e.message}", "ERROR")
            }
        }
    }

    fun toggleTunnel() {
        if (chhandaServer.isTunnelActive) {
            chhandaServer.stopTunnel()
            appLogManager.addLog("TUNNEL", "Secured global tunnel closed", "SUCCESS")
        } else {
            chhandaServer.startTunnel()
            appLogManager.addLog("TUNNEL", "Opening failproof global tunnel...", "SUCCESS")
        }
        // Update local flows
        scope.launch {
            delay(2000) // Give jsch time to connect
            isTunnelActive.value = chhandaServer.isTunnelActive
            tunnelUrl.value = chhandaServer.publicUrl
        }
    }

    private fun startHealthCheck() {
        scope.launch {
            while (true) {
                if (_isServerRunning.value) {
                    val port = _boundPort.value
                    if (port > 0) {
                        val isOk = withContext(Dispatchers.IO) {
                            try {
                                val url = java.net.URL("http://127.0.0.1:$port/ping")
                                val connection = url.openConnection() as java.net.HttpURLConnection
                                connection.connectTimeout = 2000
                                connection.readText() == "pong"
                            } catch (e: Exception) { false }
                        }
                        _isLocalLinkOk.value = isOk
                    }
                    // Update tunnel status during health check
                    isTunnelActive.value = chhandaServer.isTunnelActive
                    tunnelUrl.value = chhandaServer.publicUrl
                }
                delay(10000)
            }
        }
    }

    private fun java.net.HttpURLConnection.readText(): String? {
        return try {
            inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) { null }
    }
}
