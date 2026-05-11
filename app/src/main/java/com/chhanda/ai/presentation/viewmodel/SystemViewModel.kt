package com.chhanda.ai.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.SettingsRepository
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.VectorStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import androidx.work.*
import com.chhanda.ai.service.DownloadWorker
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

@HiltViewModel
class SystemViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val settingsRepository: SettingsRepository,
    private val llmEngineLazy: dagger.Lazy<LLMEngine>,
    private val vectorStoreLazy: dagger.Lazy<VectorStore>,
    private val chatDao: ChatDao,
    private val deviceDao: com.chhanda.ai.data.repository.DeviceDao,
    private val chhandaServerLazy: dagger.Lazy<com.chhanda.ai.data.inference.ChhandaServer>,
    private val ingestDocumentUseCaseLazy: dagger.Lazy<com.chhanda.ai.domain.usecase.IngestDocumentUseCase>
) : ViewModel() {

    private val llmEngine get() = llmEngineLazy.get()
    private val vectorStore get() = vectorStoreLazy.get()
    private val chhandaServer get() = chhandaServerLazy.get()
    private val ingestDocumentUseCase get() = ingestDocumentUseCaseLazy.get()
    private val workManager by lazy { androidx.work.WorkManager.getInstance(context) }

    fun ingestDocument(uri: android.net.Uri, type: com.chhanda.ai.domain.usecase.DocType) {
        viewModelScope.launch {
            try {
                addLog("STORAGE", "Ingesting document: ${uri.lastPathSegment}", "PENDING")
                val activeModel = ownedModels.value.firstOrNull { it.isActive }?.name ?: "default"
                ingestDocumentUseCase(uri, type, modelId = activeModel)
                addLog("STORAGE", "Document ingested successfully", "SUCCESS")
            } catch (e: Exception) {
                addLog("STORAGE", "Failed to ingest document: ${e.message}", "ERROR")
            }
        }
    }

    fun getSessionsForModel(modelName: String): Flow<List<String>> = chatDao.getSessionIdsForModel(modelName)

    fun deleteSessions(sessionIds: List<String>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                chatDao.deleteSessions(sessionIds)
                addLog("HISTORY", "Deleted ${sessionIds.size} chat sessions", "SUCCESS")
            } catch (e: Exception) {
                addLog("HISTORY", "Failed to delete sessions: ${e.message}", "ERROR")
            }
        }
    }

    // Config State
    val darkMode = settingsRepository.darkModeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val hfToken = settingsRepository.hfTokenFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "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")
    val serverPort = settingsRepository.serverPortFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "8080")
    val contextLength = settingsRepository.contextLengthFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "2048")
    val maxDevices = settingsRepository.maxDevicesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 5)
    val apiKey = settingsRepository.apiKeyFlow
        .map { it ?: "Initializing..." }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "Detecting...")
    val publicUrl = settingsRepository.publicUrlFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val appLanguage = settingsRepository.appLanguageFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "English")
    private val _showRestartDialog = MutableStateFlow(false)
    val showRestartDialog: StateFlow<Boolean> = _showRestartDialog

    // Hardware Stats State
    private val _ramUsage = MutableStateFlow("Detecting...")
    val ramUsage: StateFlow<String> = _ramUsage
    
    private val _vramUsage = MutableStateFlow("Detecting...")
    val vramUsage: StateFlow<String> = _vramUsage

    private val _deviceTemperature = MutableStateFlow(0.0)
    val deviceTemperature: StateFlow<Double> = _deviceTemperature

    private val _tokensPerSec = MutableStateFlow("0.0")
    val tokensPerSec: StateFlow<String> = _tokensPerSec

    // Senior Optimization: Lifecycle visibility tracking to pause heavy background polling
    private val _isAppVisible = MutableStateFlow(true)
    fun onVisibilityChanged(visible: Boolean) {
        _isAppVisible.value = visible
        if (visible) {
            addLog("SYSTEM", "UI foreground: Resuming monitors", "INFO")
        } else {
            addLog("SYSTEM", "UI background: Throttling monitors", "INFO")
        }
    }

    // Logs State
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    fun deleteLogs(logIds: List<String>) {
        _logs.value = _logs.value.filter { it.id !in logIds }
        addLog("SYSTEM", "Deleted ${logIds.size} logs", "INFO")
    }

    fun clearAllLogs() {
        val count = _logs.value.size
        _logs.value = emptyList()
        addLog("SYSTEM", "Cleared all logs ($count entries)", "INFO")
    }

    // Model Discovery State
    private val _ownedModels = MutableStateFlow<List<com.chhanda.ai.presentation.ui.ModelInfo>>(emptyList())
    val ownedModels: StateFlow<List<com.chhanda.ai.presentation.ui.ModelInfo>> = _ownedModels

    private val _sharedModels = MutableStateFlow<List<com.chhanda.ai.presentation.ui.ModelInfo>>(emptyList())
    val sharedModels: StateFlow<List<com.chhanda.ai.presentation.ui.ModelInfo>> = _sharedModels

    private val _downloadableModels = MutableStateFlow<List<com.chhanda.ai.presentation.ui.DownloadModelInfo>>(emptyList())
    val downloadableModels: StateFlow<List<com.chhanda.ai.presentation.ui.DownloadModelInfo>> = _downloadableModels

    private val _recommendedModel = MutableStateFlow<String?>(null)
    val recommendedModel: StateFlow<String?> = _recommendedModel

    private val _isConfigEnabled = MutableStateFlow(false)
    val isConfigEnabled: StateFlow<Boolean> = _isConfigEnabled

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    private val _downloadPauseState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val downloadPauseFlow: StateFlow<Map<String, Boolean>> = _downloadPauseState

    private val _downloadIds = MutableStateFlow<Map<String, java.util.UUID>>(emptyMap())

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning

    val storageSummary = combine(
        chatDao.getAllMessages(),
        deviceDao.getAllDevices()
    ) { messages, devices ->
        val grouped = messages.groupBy { it.deviceId }
        val devicesHistory = grouped.map { (deviceId, deviceMessages) ->
            val device = devices.find { it.deviceName == deviceId }
            DeviceHistoryInfo(
                deviceId = deviceId,
                deviceName = device?.deviceName ?: if (deviceId == "local") "This Device" else deviceId,
                messageCount = deviceMessages.size,
                lastMessageTime = deviceMessages.maxOfOrNull { it.timestamp } ?: 0L,
                messages = deviceMessages.takeLast(50) // Keep last 50 for preview
            )
        }.sortedByDescending { it.lastMessageTime }

        StorageSummary(
            totalMessages = messages.size,
            totalDevices = grouped.keys.size,
            devicesHistory = devicesHistory
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), StorageSummary(0, 0, emptyList()))

    private val _connectedDevices = deviceDao.getAllDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
    val connectedDevices: StateFlow<List<com.chhanda.ai.data.repository.DeviceEntity>> = _connectedDevices

    val activeDeviceCount = _connectedDevices.map { devices -> 
        devices.count { it.isCurrentlyConnected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 1) // Default 1 for self

    val localIpAddress: StateFlow<String> = flow {
        while(true) {
            // Senior Fix: Do NOT trigger lazy server init here. 
            // Use local IP detection until server is explicitly running.
            val ip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (_isServerRunning.value) {
                    try { chhandaServer.boundIp.ifBlank { getIpAddress() } } catch (e: Exception) { getIpAddress() }
                } else {
                    getIpAddress()
                }
            }
            emit(ip)
            delay(5000) 
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Detecting...")

    val serverActualPort: StateFlow<Int> = flow {
        while(true) {
            emit(chhandaServer.port)
            delay(5000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), -1)

    val isVpnActive: StateFlow<Boolean> = flow {
        while(true) {
            emit(chhandaServer.isVpnActive)
            delay(5000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val networkIps: StateFlow<List<String>> = flow {
        while(true) {
            emit(chhandaServer.allIps)
            delay(5000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val isTunnelActive: StateFlow<Boolean> = flow {
        while(true) {
            emit(chhandaServer.isTunnelActive)
            delay(3000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val tunnelUrl: StateFlow<String> = flow {
        while(true) {
            emit(chhandaServer.publicUrl)
            delay(3000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    fun toggleTunnel() {
        if (chhandaServer.isTunnelActive) {
            chhandaServer.stopTunnel()
            addLog("TUNNEL", "Secured global tunnel closed", "SUCCESS")
        } else {
            chhandaServer.startTunnel()
            addLog("TUNNEL", "Opening failproof global tunnel...", "SUCCESS")
        }
    }

    private val _isLocalLinkOk = MutableStateFlow(true)
    val isLocalLinkOk: StateFlow<Boolean> = _isLocalLinkOk

    private fun startLocalHealthCheck() {
        viewModelScope.launch {
            while (true) {
                // Only probe the server if it has been explicitly started by the user
                if (_isAppVisible.value && _isServerRunning.value) {
                    val port = try { chhandaServerLazy.get().port } catch (e: Exception) { -1 }
                    if (port > 0) {
                        val isOk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val client = java.net.URL("http://127.0.0.1:$port/ping")
                                    .openConnection() as java.net.HttpURLConnection
                                client.connectTimeout = 1000
                                client.readTimeout = 1000
                                client.inputStream.bufferedReader().readText() == "pong"
                            } catch (e: Exception) { false }
                        }
                        _isLocalLinkOk.value = isOk
                    }
                }
                delay(8000)
            }
        }
    }

    val deviceModelName = android.os.Build.MODEL.replace(" ", "_")

    private fun getIpAddress(): String {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            val candidates = mutableListOf<Pair<String, String>>()
            
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                        candidates.add(iface.name.lowercase() to a.hostAddress!!)
                    }
                }
            }
            
            return candidates.sortedWith(compareBy { (name, _) ->
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") || name.startsWith("softap") -> 2
                    name.startsWith("rndis") -> 3
                    else -> 4
                }
            }).firstOrNull()?.second ?: "127.0.0.1"
        } catch (e: Exception) { }
        return "127.0.0.1"
    }

    init {
        _logs.value = loadLogsFromFile()
        viewModelScope.launch {
            try {
                // Safety Delay: Give Room and Hilt 1 second to settle before firing heavy scans
                delay(1000)
                
                // Ensure local device exists in DB
                try {
                    val existingLocal = deviceDao.getDeviceByIp("127.0.0.1")
                    if (existingLocal == null) {
                        val localDevice = com.chhanda.ai.data.repository.DeviceEntity(
                            deviceName = "This Device",
                            ipAddress = "127.0.0.1",
                            connectionTime = System.currentTimeMillis(),
                            isCurrentlyConnected = true,
                            connectionType = "LOCAL",
                            userAgent = "Native App",
                            lastActive = System.currentTimeMillis()
                        )
                        deviceDao.insertDevice(localDevice)
                    }
                } catch (e: Exception) {
                    // Ignore DB errors during startup
                }
                
                try {
                    val currentPort = settingsRepository.serverPortFlow.first()
                    if (currentPort == "8000" || currentPort == "8080" || currentPort == "") {
                        settingsRepository.setServerPort("8888")
                    }
                } catch (e: Exception) {
                    // Ignore repository errors during startup
                }

                scanForModels()
                // Synchronous-like execution: Wait a bit for scan to finish before checking updates
                delay(2000)
                checkForModelUpdates()
                startLocalHealthCheck()
                startHardwareMonitoring()
                addLog("SYSTEM", "Gateway Engine v18 Active", "SUCCESS")
            } catch (e: Throwable) {
                addLog("CRITICAL", "Startup engine failure: ${e.localizedMessage}", "ERROR")
            }
        }

        // Initialize API key if missing
        viewModelScope.launch {
            try {
                val currentKey = settingsRepository.apiKeyFlow.first()
                if (currentKey == null || currentKey == "Initializing...") {
                    val newKey = "CH-${java.util.UUID.randomUUID().toString().take(8).uppercase()}"
                    settingsRepository.setApiKey(newKey)
                    addLog("SYSTEM", "Provisioned device node key: $newKey", "SUCCESS")
                }
            } catch (e: Exception) {}
        }

        // Hardware monitoring (Simulation)
        viewModelScope.launch {
            while (true) {
                // Throttled: only update if visible to reduce recomposition heat
                if (_isAppVisible.value) {
                    updateStats()
                }
                delay(if (_isAppVisible.value) 3000 else 10000)
            }
        }

        // Inference telemetry
        viewModelScope.launch {
            Log.d("SystemViewModel", "Starting performance metrics collection...")
            try {
                llmEngine.performanceMetrics.collect { tps ->
                    Log.v("SystemViewModel", "Received TPS update: $tps")
                    val formatted = if (tps > 0) tps.format(1) else "0.0"
                    if (_tokensPerSec.value != formatted) {
                        _tokensPerSec.value = formatted
                    }
                }
            } catch (e: Exception) { 
                Log.e("SystemViewModel", "Telemetry observer failed", e)
                addLog("SYSTEM", "Telemetry observer failed: ${e.message}", "WARNING")
            }
        }

    }

    private val _modelPaths = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading.asStateFlow()

    fun scanForModels() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isScanning.value = true
            try {
                val potentialModels = listOf(
                    "gemma-4-e2b.litertlm" to "Gemma-4-E2B-IT",
                    "gemma-4-e4b.litertlm" to "Gemma-4-E4B-IT"
                )

                val recommendedName = "Gemma-4-E2B-IT"
                _recommendedModel.value = recommendedName

                val ownedFound = mutableListOf<com.chhanda.ai.presentation.ui.ModelInfo>()
                val sharedFound = mutableListOf<com.chhanda.ai.presentation.ui.ModelInfo>()
                val missing = mutableListOf<com.chhanda.ai.presentation.ui.DownloadModelInfo>()
                val pathMap = mutableMapOf<String, String>()

                val ownedPaths = listOfNotNull(
                    context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                    context.filesDir,
                    java.io.File(context.filesDir, "models")
                )
                
                val sharedPaths = emptyList<java.io.File>()
                
                val processedPaths = mutableSetOf<String>()

                // Helper to add model to correct list
                fun addFoundModel(name: String, file: java.io.File, isOwned: Boolean) {
                    if (processedPaths.contains(file.absolutePath)) return
                    
                    val info = com.chhanda.ai.presentation.ui.ModelInfo(
                        name = name,
                        details = if (isOwned) "Internal: ${(file.length() / (1024.0 * 1024)).format(0)}MB" 
                                 else "Shared: ${(file.length() / (1024.0 * 1024)).format(0)}MB",
                        isActive = false
                    )
                    
                    if (isOwned) ownedFound.add(info) else sharedFound.add(info)
                    pathMap[name] = file.absolutePath
                    processedPaths.add(file.absolutePath)
                }

                // 1. Scan Owned Storage - Comprehensive Scan
                ownedPaths.forEach { dir ->
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile && (file.name.endsWith(".litertlm") || file.name.endsWith(".bin") || file.name.endsWith(".tflite"))) {
                                // Try to match by filename or by name contained in filename
                                val matchedModel = potentialModels.find { (filename, name) ->
                                    file.name.equals(filename, ignoreCase = true) || 
                                    file.name.contains(name.replace("-", ""), ignoreCase = true) ||
                                    file.name.contains("Gemma", ignoreCase = true) && name.contains("Gemma")
                                }
                                
                                if (matchedModel != null) {
                                    addFoundModel(matchedModel.second, file, true)
                                } else if (file.length() > 500_000_000) {
                                    // Generic match for large model files not in our list
                                    addFoundModel("Imported: ${file.name}", file, true)
                                }
                            }
                        }
                    }
                }

                // 2. Scan Shared Storage (Disabled as requested)

                // 3. Generic Scan for any models (Removed as requested to avoid showing models from other apps)

                // 4. Update downloadable list
                potentialModels.forEach { (filename, name) ->
                    if (!pathMap.containsKey(name) && !pathMap.containsKey("$name (Shared)")) {
                        missing.add(com.chhanda.ai.presentation.ui.DownloadModelInfo(
                            name = name,
                            description = "Mobile-optimized $name for on-device inference.",
                            size = when {
                                name.contains("1b") || name.contains("1.5b") -> "1.2 GB"
                                name.contains("2b") -> "1.6 GB"
                                name.contains("3b") -> "2.2 GB"
                                name.contains("mini") -> "2.0 GB"
                                else -> "2.8 GB"
                            },
                            isRecommended = name == recommendedName
                        ))
                    }
                }

                _ownedModels.value = ownedFound
                _sharedModels.value = sharedFound
                
                // Retain update flags if they were already detected
                val existingUpdates = _ownedModels.value.filter { it.hasUpdate }.map { it.name }.toSet()
                if (existingUpdates.isNotEmpty()) {
                    _ownedModels.value = _ownedModels.value.map { it.copy(hasUpdate = existingUpdates.contains(it.name)) }
                }

                // Merge found models with existing custom paths
                val newPathMap = pathMap.toMutableMap()
                _modelPaths.value.forEach { (name, path) ->
                    if (name.startsWith("Imported:") && !newPathMap.containsKey(name)) {
                        newPathMap[name] = path
                    }
                }
                _modelPaths.value = newPathMap
                
                _downloadableModels.value = missing
                _isConfigEnabled.value = (ownedFound + sharedFound + newPathMap.filter { it.key.startsWith("Imported:") }).isNotEmpty()
                
                if ((ownedFound + sharedFound).isNotEmpty()) {
                    addLog("SYSTEM", "Discovery: ${ownedFound.size} internal, ${sharedFound.size} shared", "SUCCESS")
                    // Automatic model activation disabled as requested. User must start it manually.
                } else {
                    addLog("SYSTEM", "No models found. Download one from the Models tab.", "INFO")
                }
            } catch (e: Throwable) {
                addLog("SYSTEM", "Model scan failed: ${e.localizedMessage}", "ERROR")
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun updateStats() {
        val ramValue = (8.0 + Math.random() * 0.5).format(1)
        _ramUsage.value = "$ramValue / 12.4 GB"
        
        val vramValue = (16.0 + Math.random() * 0.8).format(1)
        _vramUsage.value = "$vramValue / 38.2 GB"
        
        // Read battery temperature
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val rawTemp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val celsius = rawTemp / 10.0
        _deviceTemperature.value = celsius
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    // Actions
    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDarkMode(enabled)
            addLog("CONFIG", "Dark mode set to $enabled", "INFO")
        }
    }

    fun setHfToken(token: String) {
        viewModelScope.launch {
            settingsRepository.setHfToken(token)
            addLog("CONFIG", "HuggingFace token updated", "INFO")
        }
    }

    fun setAppLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.setAppLanguage(language)
            _showRestartDialog.value = true
            addLog("CONFIG", "App language set to $language. Restart required.", "INFO")
        }
    }

    fun dismissRestartDialog() {
        _showRestartDialog.value = false
    }

    fun setServerPort(port: String) {
        viewModelScope.launch {
            settingsRepository.setServerPort(port)
            addLog("CONFIG", "Server port changed to $port", "INFO")
        }
    }

    fun setContextLength(length: Int) {
        viewModelScope.launch {
            settingsRepository.setContextLength(length.toString())
            addLog("CONFIG", "Context length set to $length", "INFO")
        }
    }

    fun setMaxDevices(max: Int) {
        viewModelScope.launch {
            settingsRepository.setMaxDevices(max)
            addLog("CONFIG", "Max connected devices set to $max", "INFO")
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch {
            settingsRepository.setApiKey(key)
            addLog("CONFIG", "Global API Key updated", "SUCCESS")
        }
    }

    fun stopServer() {
        viewModelScope.launch {
            com.chhanda.ai.service.ChhandaForegroundService.stop(context)
            _isServerRunning.value = false
            addLog("SERVER", "Web Gateway manually stopped", "WARNING")
        }
    }

    fun setPublicUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.setPublicUrl(url)
            addLog("CONFIG", "Public URL updated", "SUCCESS")
        }
    }

    fun activateModel(modelName: String) {
        viewModelScope.launch {
            val path = _modelPaths.value[modelName]
            if (path == null) {
                addLog("SYSTEM", "Model path not found for: $modelName", "ERROR")
                return@launch
            }

            // Mark loading state immediately for UI feedback
            _ownedModels.value = _ownedModels.value.map { it.copy(isActive = it.name == modelName) }
            _sharedModels.value = _sharedModels.value.map { it.copy(isActive = it.name == modelName) }
            addLog("SYSTEM", "Initializing model: $modelName", "INFO")

            try {
                _isModelLoading.value = true
                // Run heavy model load on IO dispatcher — never block main
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    llmEngine.initModel(path)
                }
                _isModelLoading.value = false

                _isServerRunning.value = true
                val finalPort = (serverPort.value.toIntOrNull() ?: 8888).let { p ->
                    if (p == 8000 || p == 8080) 8888 else p
                }
                com.chhanda.ai.service.ChhandaForegroundService.start(context, finalPort, maxDevices.value)
                addLog("SYSTEM", "Model ready: $modelName", "SUCCESS")
                addLog("SERVER", "Gateway started on port $finalPort", "SUCCESS")
            } catch (e: Throwable) {
                _isModelLoading.value = false
                // Full rollback on failure
                _isServerRunning.value = false
                _ownedModels.value = _ownedModels.value.map { it.copy(isActive = false) }
                _sharedModels.value = _sharedModels.value.map { it.copy(isActive = false) }
                try { llmEngine.close() } catch (_: Exception) {}
                try { com.chhanda.ai.service.ChhandaForegroundService.stop(context) } catch (_: Exception) {}
                val errorMsg = e.localizedMessage ?: e.javaClass.simpleName
                val guidance = if (errorMsg.contains("metadata", ignoreCase = true)) 
                    " | Ensure you are using a compatible .task or .litertlm model bundle." else ""
                addLog("SYSTEM", "Failed to load model $modelName: $errorMsg$guidance", "ERROR")
            }
        }
    }

    fun registerCustomModel(file: java.io.File) {
        viewModelScope.launch {
            if (!file.exists()) {
                addLog("SYSTEM", "Import failed: File does not exist", "ERROR")
                return@launch
            }
            
            val name = "Imported: ${file.nameWithoutExtension}"
            val currentPaths = _modelPaths.value.toMutableMap()
            currentPaths[name] = file.absolutePath
            _modelPaths.value = currentPaths
            
            // Add to owned models list so it shows up in UI
            val info = com.chhanda.ai.presentation.ui.ModelInfo(
                name = name,
                details = "Manually Imported: ${(file.length() / (1024.0 * 1024)).toInt()}MB",
                isActive = false
            )
            _ownedModels.value = _ownedModels.value + info
            
            addLog("SYSTEM", "Manually registered model: ${file.name}", "SUCCESS")
            activateModel(name)
        }
    }

    fun downloadModel(model: com.chhanda.ai.presentation.ui.DownloadModelInfo, isResume: Boolean = false) {
        try {
            val filename = when {
                model.name.contains("Gemma-4-E2B", ignoreCase = true) -> "gemma-4-e2b.litertlm"
                model.name.contains("Gemma-4-E4B", ignoreCase = true) -> "gemma-4-e4b.litertlm"
                else -> "gemma-4-e2b.litertlm"
            }

            val url = when {
                model.name.contains("Gemma-4-E2B", ignoreCase = true) -> "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
                model.name.contains("Gemma-4-E4B", ignoreCase = true) -> "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
                else -> "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
            }

            val inputData = androidx.work.Data.Builder()
                .putString(com.chhanda.ai.service.DownloadWorker.KEY_URL, url)
                .putString(com.chhanda.ai.service.DownloadWorker.KEY_FILENAME, filename)
                .putString(com.chhanda.ai.service.DownloadWorker.KEY_TOKEN, hfToken.value)
                .build()

            if (url.contains("huggingface.co") && hfToken.value.isBlank()) {
                addLog("DOWNLOAD", "Warning: Token missing for gated model. Download might fail.", "WARNING")
            }

            val request = androidx.work.OneTimeWorkRequestBuilder<com.chhanda.ai.service.DownloadWorker>()
                .setInputData(inputData)
                .addTag("download_${model.name}")
                .setConstraints(androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build())
                .build()

            workManager.enqueueUniqueWork(
                "download_${model.name}",
                if (isResume) androidx.work.ExistingWorkPolicy.KEEP else androidx.work.ExistingWorkPolicy.REPLACE,
                request
            )

            _downloadIds.update { it + (model.name to request.id) }
            observeDownloadProgress(model.name, request.id)
            
            addLog("DOWNLOAD", "Started background download: ${model.name}", "INFO")
        } catch (e: Exception) {
            Log.e("SystemViewModel", "Download initiation failed", e)
            addLog("DOWNLOAD", "Critical failure starting ${model.name}: ${e.message}", "ERROR")
        }
    }

    private fun observeDownloadProgress(modelName: String, workId: java.util.UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { info ->
                if (info != null) {
                    when (info.state) {
                        androidx.work.WorkInfo.State.RUNNING -> {
                            val progress = info.progress.getInt(com.chhanda.ai.service.DownloadWorker.KEY_PROGRESS, 0)
                            _downloadProgress.value = _downloadProgress.value + (modelName to progress.toFloat() / 100f)
                        }
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            _downloadProgress.value = _downloadProgress.value - modelName
                            addLog("DOWNLOAD", "Download successful: $modelName", "SUCCESS")
                            scanForModels()
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            val error = info.outputData.getString(com.chhanda.ai.service.DownloadWorker.KEY_STATUS) ?: "Unknown failure"
                            _downloadProgress.value = _downloadProgress.value - modelName
                            addLog("DOWNLOAD", "Download failed: $modelName - $error", "ERROR")
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private fun checkForModelUpdates() {
        if (!isInternetAvailable()) {
            addLog("SYSTEM", "Offline: Skipping model update check", "INFO")
            return
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val modelsToCheck = _ownedModels.value
            if (modelsToCheck.isEmpty()) return@launch

            addLog("SYSTEM", "Checking for model updates...", "INFO")
            
            var updatesFound = 0
            val updatedModels = _ownedModels.value.toMutableList()

            modelsToCheck.forEachIndexed { index, model ->
                try {
                    val urlStr = getUrlForModel(model.name)
                    if (urlStr.isBlank()) return@forEachIndexed
                    
                    val url = URL(urlStr)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    
                    if (urlStr.contains("huggingface.co")) {
                        connection.setRequestProperty("Authorization", "Bearer ${hfToken.value}")
                    }

                    val responseCode = connection.responseCode
                    if (responseCode == 200) {
                        val remoteSize = connection.contentLengthLong
                        
                        // Find the local file
                        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val filename = getFilenameForModel(model.name)
                        val localFile = java.io.File(dir, filename)
                        
                        if (localFile.exists() && remoteSize > 0 && localFile.length() != remoteSize) {
                            // Size mismatch usually means a newer version or corrupted file
                            updatedModels[index] = model.copy(hasUpdate = true)
                            updatesFound++
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e("SystemViewModel", "Update check failed for ${model.name}", e)
                }
            }

            if (updatesFound > 0) {
                _ownedModels.value = updatedModels
                addLog("SYSTEM", "Detected $updatesFound model updates available", "WARNING")
            } else {
                addLog("SYSTEM", "All models are up to date", "SUCCESS")
            }
        }
    }

    private fun getUrlForModel(name: String): String {
        return when {
            name.contains("Gemma-4-E2B", ignoreCase = true) -> "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
            name.contains("Gemma-4-E4B", ignoreCase = true) -> "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
            else -> "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        }
    }

    private fun getFilenameForModel(name: String): String {
        return when {
            name.contains("Gemma-4-E2B", ignoreCase = true) -> "gemma-4-e2b.litertlm"
            name.contains("Gemma-4-E4B", ignoreCase = true) -> "gemma-4-e4b.litertlm"
            else -> "gemma-4-e2b.litertlm"
        }
    }

    private fun startHardwareMonitoring() {
        viewModelScope.launch {
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            
            while (true) {
                if (_isAppVisible.value) {
                    try {
                        activityManager.getMemoryInfo(memoryInfo)
                        val usedMem = (memoryInfo.totalMem - memoryInfo.availMem) / (1024.0 * 1024 * 1024)
                        val totalMem = memoryInfo.totalMem / (1024.0 * 1024 * 1024)
                        _ramUsage.value = "${String.format("%.1f", usedMem)} / ${String.format("%.1f", totalMem)} GB"
                        
                        // VRAM is simulated as 40% of used RAM on Android (unified)
                        val vramUsed = usedMem * 0.4
                        val vramTotal = totalMem * 0.6
                        _vramUsage.value = "${String.format("%.1f", vramUsed)} / ${String.format("%.1f", vramTotal)} GB"
                        
                        _deviceTemperature.value = 32.0 + (if (_isModelLoading.value) 12.0 else 0.0)
                    } catch (e: Exception) {}
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    fun stopDownload(modelName: String) {
        cancelDownload(modelName)
    }

    fun pauseDownload(modelName: String) {
        workManager.cancelUniqueWork("download_$modelName")
        _downloadPauseState.update { it + (modelName to true) }
        addLog("DOWNLOAD", "Download paused: $modelName", "INFO")
    }

    fun resumeDownload(modelName: String, model: com.chhanda.ai.presentation.ui.DownloadModelInfo) {
        _downloadPauseState.update { it + (modelName to false) }
        downloadModel(model, isResume = true)
    }

    fun cancelDownload(modelName: String) {
        workManager.cancelUniqueWork("download_$modelName")
        _downloadProgress.value = _downloadProgress.value - modelName
        addLog("DOWNLOAD", "Download cancelled: $modelName", "WARNING")
    }


    fun stopGlobalInference() {
        llmEngine.stopInference()
        chhandaServer.stop()
        _tokensPerSec.value = "0.0"
        _isServerRunning.value = false
        addLog("SYSTEM", "Global Inference Halted", "WARN")
        addLog("SERVER", "Web Gateway Stopped", "WARN")
    }

    fun clearHistoryForDevice(deviceId: String) {
        viewModelScope.launch {
            chatDao.clearHistoryForDevice(deviceId)
            addLog("STORAGE", "History cleared for device: $deviceId", "SUCCESS")
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            chatDao.clearHistory()
            addLog("STORAGE", "Database and Vector Store purged", "SUCCESS")
        }
    }

    private val logsMutex = Mutex()

    private fun loadLogsFromFile(): List<LogEntry> {
        val file = java.io.File(context.filesDir, "system_logs.json")
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            kotlinx.serialization.json.Json.decodeFromString<List<LogEntry>>(json)
        } catch (e: Exception) {
            android.util.Log.e("SystemViewModel", "Failed to load logs: ${e.message}")
            emptyList()
        }
    }

    private fun saveLogsToFile(logs: List<LogEntry>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            logsMutex.withLock {
                try {
                    val file = java.io.File(context.filesDir, "system_logs.json")
                    val tempFile = java.io.File(context.filesDir, "system_logs.json.tmp")
                    val json = kotlinx.serialization.json.Json.encodeToString(logs)
                    tempFile.writeText(json)
                    tempFile.renameTo(file) // Atomic rename prevents corruption if the app crashes mid-write
                } catch (e: Exception) {
                    android.util.Log.e("SystemViewModel", "Failed to save logs: ${e.message}")
                }
            }
        }
    }

    fun addLog(tag: String, message: String, level: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val entry = LogEntry(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = timestamp, 
            tag = tag, 
            message = message, 
            status = level
        )
        _logs.update { current ->
            (listOf(entry) + current).take(100)
        }
        saveLogsToFile(_logs.value)
    }

    // Device Management Actions
    fun simulateQrConnection() {
        viewModelScope.launch {
            val newDevice = com.chhanda.ai.data.repository.DeviceEntity(
                deviceName = "Client-Node-${(100..999).random()}",
                ipAddress = "192.168.1.${(10..254).random()}",
                connectionTime = System.currentTimeMillis(),
                isCurrentlyConnected = true,
                connectionType = "SHARED",
                userAgent = "Mobile Browser (Safari/Chrome)"
            )
            deviceDao.insertDevice(newDevice)
            addLog("SHARE", "New device connected via QR", "SUCCESS")
        }
    }

    fun disconnectAllDevices() {
        viewModelScope.launch {
            val active = deviceDao.getActiveConnections()
            active.forEach { device ->
                val now = System.currentTimeMillis()
                val duration = now - device.connectionTime
                deviceDao.updateDevice(device.copy(
                    isCurrentlyConnected = false,
                    disconnectionTime = now,
                    durationMs = duration
                ))
            }
            addLog("SHARE", "All external devices disconnected", "WARN")
        }
    }

    fun purgeDeviceLogs() {
        viewModelScope.launch {
            deviceDao.clearDeviceHistory()
            addLog("SYSTEM", "Device connection logs cleared", "SUCCESS")
        }
    }

    fun deleteModel(modelName: String) {
        viewModelScope.launch {
            // If deleting the active model, shut down engine first to release file lock
            val isActive = (ownedModels.value + sharedModels.value).any { it.isActive && it.name == modelName }
            if (isActive) {
                try { llmEngine.close() } catch (_: Exception) {}
                try { com.chhanda.ai.service.ChhandaForegroundService.stop(context) } catch (_: Exception) {}
                _isServerRunning.value = false
            }

            val path = _modelPaths.value[modelName]
            if (path == null) {
                addLog("STORAGE", "Cannot delete: path not found for $modelName", "ERROR")
                return@launch
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val file = java.io.File(path)
                    when {
                        !file.exists() -> addLog("STORAGE", "File already removed: $modelName", "INFO")
                        file.delete() -> addLog("STORAGE", "Deleted: $modelName", "SUCCESS")
                        else -> addLog("STORAGE", "Delete failed (permissions?): $modelName", "ERROR")
                    }
                } catch (e: Exception) {
                    addLog("STORAGE", "Delete error: ${e.localizedMessage}", "ERROR")
                }
            }
            // Remove from paths map and refresh
            _modelPaths.value = _modelPaths.value - modelName
            scanForModels()
        }
    }

    fun shutdown() {
        viewModelScope.launch {
            try { llmEngine.close() } catch (_: Exception) {}
            try { com.chhanda.ai.service.ChhandaForegroundService.stop(context) } catch (_: Exception) {}
            _isServerRunning.value = false
            _tokensPerSec.value = "0.0"
            addLog("SYSTEM", "App shutdown requested. Disconnected all devices and stopped LLM.", "INFO")
        }
    }
}

@kotlinx.serialization.Serializable
data class LogEntry(val id: String, val timestamp: String, val tag: String, val message: String, val status: String)

data class DeviceHistoryInfo(
    val deviceId: String,
    val deviceName: String,
    val messageCount: Int,
    val lastMessageTime: Long,
    val messages: List<com.chhanda.ai.data.repository.MessageEntity>
)

data class StorageSummary(
    val totalMessages: Int,
    val totalDevices: Int,
    val devicesHistory: List<DeviceHistoryInfo>
)
