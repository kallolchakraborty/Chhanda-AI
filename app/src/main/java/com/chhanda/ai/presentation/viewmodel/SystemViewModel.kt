package com.chhanda.ai.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.SettingsRepository
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.VectorStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import androidx.work.*
import com.chhanda.ai.service.DownloadWorker
import com.chhanda.ai.service.IngestionWorker
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.io.File
import android.os.BatteryManager
import android.app.ActivityManager

@HiltViewModel
class SystemViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val settingsRepository: SettingsRepository,
    private val llmEngineLazy: dagger.Lazy<LLMEngine>,
    private val vectorStoreLazy: dagger.Lazy<VectorStore>,
    private val chatDao: ChatDao,
    private val deviceDao: com.chhanda.ai.data.repository.DeviceDao,
    private val chhandaServerLazy: dagger.Lazy<com.chhanda.ai.data.inference.ChhandaServer>,
    private val ingestDocumentUseCaseLazy: dagger.Lazy<com.chhanda.ai.domain.usecase.IngestDocumentUseCase>,
    private val uploadedFileDao: com.chhanda.ai.data.repository.UploadedFileDao,
    private val vectorChunkDao: com.chhanda.ai.data.repository.VectorChunkDao,
    private val scrapeUrlUseCaseLazy: dagger.Lazy<com.chhanda.ai.domain.usecase.ScrapeUrlUseCase>
) : ViewModel() {

    private val llmEngine get() = llmEngineLazy.get()
    private val vectorStore get() = vectorStoreLazy.get()
    private val chhandaServer get() = chhandaServerLazy.get()
    private val ingestDocumentUseCase get() = ingestDocumentUseCaseLazy.get()
    private val scrapeUrlUseCase get() = scrapeUrlUseCaseLazy.get()
    

    
    // Removed automated HotspotManager logic in favor of Manual System Hotspot.
    
    private var activeScrapeJob: kotlinx.coroutines.Job? = null

    private val _ramUsage = kotlinx.coroutines.flow.MutableStateFlow("0 / 0 GB")
    val ramUsage: kotlinx.coroutines.flow.StateFlow<String> = _ramUsage.asStateFlow()
    
    private val _appStorageUsage = kotlinx.coroutines.flow.MutableStateFlow("0 MB")
    val appStorageUsage: kotlinx.coroutines.flow.StateFlow<String> = _appStorageUsage.asStateFlow()

    private val _isIngesting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isIngesting: kotlinx.coroutines.flow.StateFlow<Boolean> = _isIngesting.asStateFlow()

    private val _ingestionError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val ingestionError = _ingestionError.asStateFlow()

    private val _showVectorStorageWarning = MutableStateFlow(false)
    val showVectorStorageWarning = _showVectorStorageWarning.asStateFlow()

    fun dismissVectorStorageWarning() {
        _showVectorStorageWarning.value = false
    }

    var pendingBackgroundPrompt by androidx.compose.runtime.mutableStateOf<IngestionTask?>(null)
        private set

    data class IngestionTask(
        val uris: List<android.net.Uri> = emptyList(),
        val url: String? = null,
        val label: String? = null
    )
    
    private val _ingestionProgress = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val ingestionProgress: kotlinx.coroutines.flow.StateFlow<Float> = _ingestionProgress.asStateFlow()
    
    private val _ingestionMessage = kotlinx.coroutines.flow.MutableStateFlow("Processing file for RAG...")
    val ingestionMessage: kotlinx.coroutines.flow.StateFlow<String> = _ingestionMessage.asStateFlow()

    fun dismissIngestionProgress() {
        _isIngesting.value = false
        _ingestionProgress.value = 0f
    }

    private val _showModelSelectionDialog = kotlinx.coroutines.flow.MutableStateFlow(false)
    val showModelSelectionDialog: kotlinx.coroutines.flow.StateFlow<Boolean> = _showModelSelectionDialog.asStateFlow()

    private val _pendingIngestion = kotlinx.coroutines.flow.MutableStateFlow<Pair<android.net.Uri, com.chhanda.ai.domain.usecase.DocType>?>(null)
    val pendingIngestion: kotlinx.coroutines.flow.StateFlow<Pair<android.net.Uri, com.chhanda.ai.domain.usecase.DocType>?> = _pendingIngestion.asStateFlow()

    fun dismissModelSelection() {
        _showModelSelectionDialog.value = false
        _pendingIngestion.value = null
    }
    
    private val _showAllFiles = kotlinx.coroutines.flow.MutableStateFlow(false)
    val showAllFiles: kotlinx.coroutines.flow.StateFlow<Boolean> = _showAllFiles.asStateFlow()

    val recentFiles: kotlinx.coroutines.flow.StateFlow<List<com.chhanda.ai.data.repository.UploadedFileEntity>> = 
        uploadedFileDao.getRecentFiles(10).stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    val allFiles: kotlinx.coroutines.flow.StateFlow<List<com.chhanda.ai.data.repository.UploadedFileEntity>> = 
        uploadedFileDao.getAllFiles().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    fun setShowAllFiles(show: Boolean) {
        _showAllFiles.value = show
    }

    private val _showInternetWarning = MutableStateFlow(false)
    val showInternetWarning = _showInternetWarning.asStateFlow()

    fun dismissInternetWarning() {
        _showInternetWarning.value = false
    }

    private val _showLlmServerWarning = MutableStateFlow(false)
    val showLlmServerWarning = _showLlmServerWarning.asStateFlow()

    fun dismissLlmServerWarning() {
        _showLlmServerWarning.value = false
    }



    fun deleteFiles(ids: List<String>) {
        viewModelScope.launch {
            uploadedFileDao.markMultipleAsDeleted(ids)
            // Delete physical files
            val filesToDelete = allFiles.value.filter { it.id in ids }
            filesToDelete.forEach { file ->
                try {
                    val path = file.path
                    if (path.startsWith("content://")) {
                        context.contentResolver.delete(android.net.Uri.parse(path), null, null)
                    } else {
                        // Assume raw file path
                        val physicalFile = java.io.File(path)
                        if (physicalFile.exists()) physicalFile.delete()
                    }
                    // Also delete from vector store
                    vectorChunkDao.deleteBySource(path)
                } catch (e: Exception) {
                    addLog("STORAGE", "Failed to delete file or chunks: ${file.name}", "WARNING")
                }
            }
            addLog("STORAGE", "Deleted ${ids.size} files from disk, DB and vector store", "SUCCESS")
        }
    }

    fun checkAndPerformCleanup() {
        viewModelScope.launch {
            val enabled = autoDeleteEnabled.first()
            if (!enabled) return@launch
            
            val days = autoDeleteDays.first()
            val threshold = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000L
            
            val filesToDelete = uploadedFileDao.getFilesOlderThan(threshold)
            if (filesToDelete.isNotEmpty()) {
                val ids = filesToDelete.map { it.id }
                deleteFiles(ids)
            }
        }
    }

    init {
        viewModelScope.launch {
            while(true) {
                try {
                    val fiveMinutesAgo = System.currentTimeMillis() - 5 * 60 * 1000
                    val activeDevices = deviceDao.getActiveConnections()
                    activeDevices.forEach { device ->
                        if (device.lastActive < fiveMinutesAgo) {
                            deviceDao.updateDeviceStatus(device.deviceName, false, device.lastActive)
                            addLog("NETWORK", "Device ${device.deviceName} timed out and marked as disconnected.", "INFO")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SystemViewModel", "Error in device monitor: ${e.message}")
                }
                delay(10000) // Check every 10 seconds for better responsiveness
            }
        }
    }

    private val workManager by lazy { androidx.work.WorkManager.getInstance(context) }

    fun ingestDocuments(uris: List<android.net.Uri>) {
        // Estimate time: > 1MB total usually takes > 30s
        var totalSize = 0L
        uris.forEach { uri ->
            try {
                totalSize += context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            } catch (e: Exception) {}
        }

        if (totalSize > 1024 * 1024) { // 1MB threshold
            pendingBackgroundPrompt = IngestionTask(uris = uris)
            return
        }

        processIngestDocuments(uris)
    }

    fun processIngestDocuments(uris: List<android.net.Uri>, inBackground: Boolean = false) {
        pendingBackgroundPrompt = null
        
        // Block ingestion if storage is full
        if (_vectorDbUsage.value >= _vectorDbCapacityBytes.value * 0.9) {
            _showVectorStorageWarning.value = true
            return
        }

        if (inBackground) {
            uris.forEach { uri ->
                val type = getDocType(uri)
                val fileDetails = com.chhanda.ai.util.FileUtils.getFileDetails(context, uri)
                val fileName = fileDetails.first
                val data = workDataOf(
                    IngestionWorker.KEY_URI to uri.toString(),
                    IngestionWorker.KEY_TYPE to type.name,
                    IngestionWorker.KEY_NAME to fileName
                )
                val request = OneTimeWorkRequestBuilder<IngestionWorker>()
                    .setInputData(data)
                    .build()
                WorkManager.getInstance(context).enqueue(request)
            }
            addLog("SYSTEM", "Large ingestion moved to background.", "SUCCESS")
            return
        }

        viewModelScope.launch {
            try {
                _isIngesting.value = true
                _ingestionError.value = null
                val totalFiles = uris.size
                var processedFiles = 0

                for (uri in uris) {
                    _ingestionProgress.value = processedFiles.toFloat() / totalFiles
                    val type = getDocType(uri)

                    // Extract file details
                    val fileDetails = com.chhanda.ai.util.FileUtils.getFileDetails(context, uri)
                    val name = fileDetails.first
                    val size = fileDetails.second
                    val format = type.name
                    val path = uri.toString()

                    _ingestionMessage.value = "Processing $name ($format)..."

                    // Check for duplicate
                    val existingFile = uploadedFileDao.findByNameAndSize(name, size)
                    if (existingFile != null) {
                        addLog("STORAGE", "Duplicate file skipped: $name", "SUCCESS")
                        processedFiles++
                        continue
                    }

                    val id = java.util.UUID.randomUUID().toString()
                    val fileEntity = com.chhanda.ai.data.repository.UploadedFileEntity(
                        id = id, name = name, format = format, size = size, path = path, isDeleted = false
                    )
                    uploadedFileDao.insertFile(fileEntity)
                    
                    addLog("STORAGE", "Ingesting document: $name", "PENDING")
                    val activeModel = "shared_rag_db"
                    
                    val baseProgress = processedFiles.toFloat() / totalFiles
                    val fileWeight = 1f / totalFiles

                    ingestDocumentUseCase(uri, type, modelId = "shared_rag_db") { fileProgress ->
                        _ingestionProgress.value = baseProgress + (fileProgress * fileWeight)
                    }
                    
                    processedFiles++
                    addLog("STORAGE", "Document $name ingested successfully", "SUCCESS")
                }
                
                _isIngesting.value = false
                _ingestionProgress.value = 1.0f
                _ingestionMessage.value = "All $totalFiles files processed successfully. Tap to close."
                updateStats()
            } catch (e: Exception) {
                _isIngesting.value = false
                _ingestionProgress.value = 1.0f
                _ingestionMessage.value = "Processing failed: ${e.localizedMessage}. Tap to close."
                addLog("STORAGE", "Failed to ingest documents: ${e.message}", "ERROR")
                _ingestionError.value = "Failed to process documents. Please try again."
            }
        }
    }

    private fun getDocType(uri: android.net.Uri): com.chhanda.ai.domain.usecase.DocType {
        val mimeType = context.contentResolver.getType(uri)
        return when {
            mimeType?.startsWith("image/") == true -> com.chhanda.ai.domain.usecase.DocType.IMAGE
            mimeType == "application/pdf" -> com.chhanda.ai.domain.usecase.DocType.PDF
            mimeType == "text/plain" -> com.chhanda.ai.domain.usecase.DocType.TXT
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> com.chhanda.ai.domain.usecase.DocType.WORD
            mimeType?.startsWith("audio/") == true -> com.chhanda.ai.domain.usecase.DocType.AUDIO
            else -> com.chhanda.ai.domain.usecase.DocType.TXT
        }
    }

    fun dismissIngestionPrompt() {
        pendingBackgroundPrompt = null
    }

    fun clearIngestionError() {
        _ingestionError.value = null
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
    val autoDeleteDays = settingsRepository.autoDeleteDaysFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 7)
    val autoDeleteEnabled = settingsRepository.autoDeleteEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    private val _vectorDbCapacityBytes = MutableStateFlow(1024L * 1024 * 1024)
    val vectorDbCapacityBytes: StateFlow<Long> = _vectorDbCapacityBytes.asStateFlow()
    private val _showRestartDialog = MutableStateFlow(false)
    val showRestartDialog: StateFlow<Boolean> = _showRestartDialog

    private val _deviceTemperature = MutableStateFlow(0.0)
    val deviceTemperature: StateFlow<Double> = _deviceTemperature

    private val _vectorDbUsage = MutableStateFlow(0L)
    val vectorDbUsage: StateFlow<Long> = _vectorDbUsage.asStateFlow()

    val vectorStorageMetrics: StateFlow<String> = combine(vectorDbUsage, vectorDbCapacityBytes) { usage, capacity ->
        val usageStr = if (usage > 1024 * 1024 * 1024) {
            String.format(Locale.US, "%.2f GB", usage.toDouble() / (1024 * 1024 * 1024))
        } else {
            String.format(Locale.US, "%.1f MB", usage.toDouble() / (1024 * 1024))
        }
        
        val capacityStr = if (capacity > 1024 * 1024 * 1024) {
            String.format(Locale.US, "%.2f GB", capacity.toDouble() / (1024 * 1024 * 1024))
        } else {
            String.format(Locale.US, "%.1f MB", capacity.toDouble() / (1024 * 1024))
        }
        
        "$usageStr / $capacityStr"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "Calculating...")

    private val _processorInfo = MutableStateFlow("Detecting...")
    val processorInfo: StateFlow<String> = _processorInfo.asStateFlow()

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
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

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
        val cutoff = System.currentTimeMillis() - 40000 // 40s reactive cutoff
        devices.count { it.isCurrentlyConnected && it.lastActive > cutoff }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 1) // Default 1 for self

    val localIpAddress: StateFlow<String> = flow {
        while(true) {
            // Senior Fix: Do NOT trigger lazy server init here. 
            // Use local IP detection until server is explicitly running.
            val ip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (_isServerRunning.value) {
                    try { 
                        val bound = chhandaServer.boundIp
                        if (bound.isBlank() || bound == "0.0.0.0" || bound == "127.0.0.1") getIpAddress() else bound
                    } catch (e: Exception) { getIpAddress() }
                } else {
                    getIpAddress()
                }
            }
            emit(ip)
            delay(5000) 
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Detecting...")

    val serverActualPort: StateFlow<Int> = chhandaServer.boundPortFlow

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
                                val client = java.net.URL("http://127.0.0.1:$port/ping").openConnection() as java.net.HttpURLConnection
                                client.connectTimeout = 1000
                                client.readTimeout = 1000
                                client.inputStream.bufferedReader().readText() == "pong"
                            } catch (e: Exception) {
                                false
                            }
                        }
                        _isLocalLinkOk.value = isOk
                    }
                }
                delay(if (_isLocalLinkOk.value) 30000 else 10000)
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
            
            // Senior IP Sorting: Prioritize Wi-Fi and Hotspot interfaces.

            return candidates.sortedWith(compareBy { (name, ip) ->
                when {
                    // Highest priority: Hotspot standard gateway IP
                    ip == "192.168.43.1" || ip == "192.168.44.1" || ip == "192.168.45.1" -> 0
                    // Second: Hotspot interfaces
                    name.contains("ap0") || name.contains("softap") || name.contains("swlan") -> 1
                    // Third: WLAN
                    name.startsWith("wlan") -> 2
                    // Fourth: Ethernet
                    name.startsWith("eth") -> 3
                    else -> 4
                }
            }).firstOrNull()?.second ?: "127.0.0.1"
        } catch (e: Exception) { }
        return "127.0.0.1"
    }

    init {
        _logs.value = loadLogsFromFile()
        checkAndPerformCleanup()
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
        // Server status and error observers
        val localServer = chhandaServer
        val pFlow: kotlinx.coroutines.flow.Flow<Int> = localServer.boundPortFlow
        val eFlow: kotlinx.coroutines.flow.Flow<String?> = localServer.serverErrorFlow
        
        viewModelScope.launch {
            pFlow.collect { port ->
                _isServerRunning.value = port > 0
            }
        }
        viewModelScope.launch {
            eFlow.collect { msg ->
                if (msg != null) {
                    addLog("SERVER", msg.toString(), "ERROR")
                }
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
                    
                    if (isOwned) {
                        if (ownedFound.any { it.name == name }) return
                        ownedFound.add(info)
                    } else {
                        if (sharedFound.any { it.name == name }) return
                        sharedFound.add(info)
                    }
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

    fun setVectorDbCapacity(gb: Int) {
        viewModelScope.launch {
            settingsRepository.setVectorDbCapacity(gb)
        }
    }

    fun setAutoDeleteDays(days: Int) {
        viewModelScope.launch {
            settingsRepository.setAutoDeleteDays(days)
        }
    }

    fun setAutoDeleteEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoDeleteEnabled(enabled)
        }
    }

    private fun updateStats() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // 1. App Storage (Recursive)
                val totalAppSize = getAppStorageSize()
                
                // 2. Vector DB Size (Database file + WAL + SHM)
                val vectorUsage = getVectorDbSize()
                
                // Processor Info
                val cpuName = getCpuName()
                val cores = Runtime.getRuntime().availableProcessors()
                _processorInfo.value = "$cpuName ($cores Cores)"
                
                // 3. Hardware Metrics
                val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memoryInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val usedMem = (memoryInfo.totalMem - memoryInfo.availMem) / (1024.0 * 1024 * 1024)
                val totalMem = memoryInfo.totalMem / (1024.0 * 1024 * 1024)
                
                // 4. Battery Temperature
                val intent = try {
                    context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                } catch (e: Exception) { null }
                val rawTemp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val celsius = rawTemp / 10.0

                // 5. Dynamic Vector Capacity (Max of 1GB and 15% of remaining storage)
                val statFs = android.os.StatFs(context.filesDir.path)
                val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
                val dynamicLimit = (availableBytes * 0.15).toLong()
                val finalCapacity = maxOf(1024L * 1024 * 1024, dynamicLimit)
                _vectorDbCapacityBytes.value = finalCapacity
                
                _ramUsage.value = String.format(Locale.US, "%.1f / %.1f GB", usedMem, totalMem)
                _appStorageUsage.value = if (totalAppSize > 1024 * 1024 * 1024) {
                    String.format(Locale.US, "%.2f GB", totalAppSize.toDouble() / (1024 * 1024 * 1024))
                } else {
                    String.format(Locale.US, "%.1f MB", totalAppSize.toDouble() / (1024 * 1024))
                }
                _deviceTemperature.value = celsius
                _vectorDbUsage.value = vectorUsage

                // Check for 90% capacity threshold
                if (finalCapacity > 0) {
                    val usageRatio = vectorUsage.toDouble() / finalCapacity
                    if (usageRatio >= 0.9) {
                        _showVectorStorageWarning.value = true
                        showStorageSystemNotification()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SystemViewModel", "Failed to update stats: ${e.message}")
            }
        }
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

    private fun showStorageSystemNotification() {
        try {
            val channelId = "system_alerts"
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(channelId, "System Alerts", android.app.NotificationManager.IMPORTANCE_HIGH)
                notificationManager.createNotificationChannel(channel)
            }
            
            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setContentTitle("Chhanda Storage Warning")
                .setContentText("Vector database is 90% full. Empty it or free phone space to continue.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(8008, notification)
        } catch (e: Exception) {
            Log.e("SystemViewModel", "Failed to show system notification", e)
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
            val oldPort = settingsRepository.serverPortFlow.first()
            if (oldPort != port) {
                settingsRepository.setServerPort(port)
                addLog("CONFIG", "Server port changed to $port. Restarting server...", "INFO")
                
                val portInt = port.toIntOrNull() ?: 8888
                val finalPort = if (portInt == 8000 || portInt == 8080) 8888 else portInt
                com.chhanda.ai.service.ChhandaForegroundService.start(context, finalPort, maxDevices.value)
            }
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

            // Automatic calculation of optimized context length based on available RAM
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val availableMegs = memoryInfo.availMem / (1024 * 1024)
            
            val optimizedContextLength = when {
                availableMegs < 1000 -> 512  // Very low memory
                availableMegs < 2000 -> 1024 // Low memory
                availableMegs < 3000 -> 2048 // Medium memory
                else -> 4096                 // High memory
            }
            
            addLog("SYSTEM", "Auto-calculated optimized context length: $optimizedContextLength based on ${availableMegs}MB free RAM", "INFO")
            setContextLength(optimizedContextLength)

            try {
                _isModelLoading.value = true
                // Run heavy model load on IO dispatcher — never block main
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    llmEngine.initModel(path)
                }
                _isModelLoading.value = false
                
                val finalPort = (serverPort.value.toIntOrNull() ?: 8888).let { p ->
                    if (p == 8000 || p == 8080) 8888 else p
                }

                com.chhanda.ai.service.ChhandaForegroundService.start(context, finalPort, maxDevices.value)
                addLog("SYSTEM", "Model ready: $modelName", "SUCCESS")
                addLog("SERVER", "Gateway start requested on port $finalPort", "INFO")
            } catch (e: Throwable) {
                _isModelLoading.value = false
                // Full rollback on failure
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
                model.name.contains("E4B", ignoreCase = true) -> "gemma-4-e4b.litertlm"
                model.name.contains("E2B", ignoreCase = true) -> "gemma-4-e2b.litertlm"
                else -> "gemma-4-e2b.litertlm"
            }

            val url = when {
                model.name.contains("E4B", ignoreCase = true) -> "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
                model.name.contains("E2B", ignoreCase = true) -> "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
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

    fun isInternetAvailable(): Boolean {
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


    fun clearVectorStore() {
        viewModelScope.launch {
            try {
                vectorChunkDao.clearAll()
                // Mark all files as deleted to reset the "Indexed Files" counter in UI
                val ids = allFiles.value.map { it.id }
                uploadedFileDao.markMultipleAsDeleted(ids)
                updateStats()
                addLog("STORAGE", "Vector database emptied successfully", "SUCCESS")
            } catch (e: Exception) {
                addLog("STORAGE", "Failed to clear vector store: ${e.message}", "ERROR")
            }
        }
    }

    fun scrapeAndIngestUrl(url: String, label: String) {
        // Estimation for URL: If label/URL contains "wikipedia" or long articles, it might take time.
        // For URLs we don't know size easily, so we usually check content length in background.
        // But for now, if it's not a tiny URL, we can prompt or just run foreground.
        // User asked for "If file size is big... more than 30 sec".
        // Let's just always offer background for URLs since network is unpredictable.
        pendingBackgroundPrompt = IngestionTask(url = url, label = label)
    }

    fun processScrapeUrl(url: String, label: String, inBackground: Boolean = false) {
        pendingBackgroundPrompt = null

        // Block if no internet
        if (!isInternetAvailable()) {
            _showInternetWarning.value = true
            return
        }

        // Kaggle detection
        val isKaggle = url.contains("kaggle.com", ignoreCase = true)
        
        // If it's a deep scraping or Kaggle, check if LLM server is needed/running
        if (isKaggle && !_isServerRunning.value) {
            _showLlmServerWarning.value = true
            return
        }

        if (inBackground) {
            activeScrapeJob?.cancel()
            val data = workDataOf(
                IngestionWorker.KEY_URL to url,
                IngestionWorker.KEY_NAME to label
            )
            val request = OneTimeWorkRequestBuilder<IngestionWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            addLog("SYSTEM", "URL scraping moved to background.", "SUCCESS")
            _isIngesting.value = false
            return
        }

        activeScrapeJob?.cancel()
        activeScrapeJob = viewModelScope.launch {
            _isIngesting.value = true
            _ingestionError.value = null
            _ingestionProgress.value = 0f 
            _ingestionMessage.value = "Scraping content from $label..."
            
            // Timer for background prompt
            val timerJob = launch {
                delay(30000)
                if (_isIngesting.value && _ingestionMessage.value.contains("Scraping")) {
                    pendingBackgroundPrompt = IngestionTask(url = url, label = label)
                }
            }

            try {
                // Size limit check (300MB)
                // Note: For URLs, we often don't know the size until we start downloading.
                // We'll add a check in the scraping logic to abort if content-length > 300MB.
                
                val scrapedText = if (isKaggle) {
                    _ingestionMessage.value = "AI-Assisted Kaggle Parsing..."
                    // Call the use case with a flag for AI-assisted parsing if needed
                    scrapeUrlUseCase(url, useAi = true, maxSizeMb = 300)
                } else {
                    scrapeUrlUseCase(url, maxSizeMb = 300)
                }
                timerJob.cancel()
                
                _ingestionMessage.value = "Ingesting web content into Vector DB..."
                
                ingestDocumentUseCase.ingestScrapedText(scrapedText, url, label) { progress ->
                    _ingestionProgress.value = 0.3f + (progress * 0.7f)
                }
                
                uploadedFileDao.insertFile(com.chhanda.ai.data.repository.UploadedFileEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    name = label,
                    format = "WEB_URL",
                    size = scrapedText.length.toLong(),
                    path = url,
                    timestamp = System.currentTimeMillis()
                ))
                
                addLog("STORAGE", "Successfully indexed: $label ($url)", "SUCCESS")
                showCompletionNotification(label)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                timerJob.cancel()
                addLog("STORAGE", "Web Scraping Failed: ${e.message}", "ERROR")
                _ingestionError.value = "Failed to scrape $label. Please try again."
            } finally {
                _isIngesting.value = false
                updateStats()
            }
        }
    }

    private suspend fun getVectorDbSize(): Long {
        // We use the high-precision DAO query for exact vector memory footprint
        val embeddingSize = try { vectorChunkDao.getTotalEmbeddingSize() ?: 0L } catch (e: Exception) { 0L }
        val count = try { vectorChunkDao.getCount() } catch (e: Exception) { 0 }
        
        // Estimate table overhead (text, metadata, indices)
        // Average chunk is ~800 chars. Indices/Metadata ~200 bytes.
        val estimatedTotal = embeddingSize + (count.toLong() * 1000L)
        return estimatedTotal
    }

    private fun getAppStorageSize(): Long {
        var totalSize = 0L
        
        // Internal storage
        totalSize += getDirSize(context.filesDir)
        totalSize += getDirSize(context.cacheDir)
        
        // External storage (scoped)
        context.getExternalFilesDir(null)?.let { totalSize += getDirSize(it) }
        context.externalCacheDir?.let { totalSize += getDirSize(it) }
        
        // Database
        val dbFile = context.getDatabasePath("chhanda_db")
        if (dbFile.exists()) totalSize += dbFile.length()
        
        return totalSize
    }

    private fun getDirSize(dir: java.io.File): Long {
        var size = 0L
        val files = try { dir.listFiles() } catch (e: Exception) { null }
        if (files != null) {
            for (file in files) {
                try {
                    if (file.isDirectory) {
                        size += getDirSize(file)
                    } else if (file.isFile) {
                        size += file.length()
                    }
                } catch (e: Exception) { /* skip unreadable */ }
            }
        }
        return size
    }

    private fun getCpuName(): String {
        try {
            val file = java.io.File("/proc/cpuinfo")
            if (file.exists()) {
                val reader = file.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("Hardware") || line!!.startsWith("model name")) {
                        val parts = line!!.split(":")
                        if (parts.size > 1) {
                            return parts[1].trim()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SystemViewModel", "Failed to read cpuinfo: ${e.message}")
        }
        return android.os.Build.HARDWARE ?: android.os.Build.BOARD ?: "Unknown"
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
            // Delete physical files first
            val ids = allFiles.value.map { it.id }
            val filesToDelete = allFiles.value
            filesToDelete.forEach { file ->
                try {
                    val path = file.path
                    if (path.startsWith("content://")) {
                        context.contentResolver.delete(android.net.Uri.parse(path), null, null)
                    } else if (path.startsWith("file://")) {
                        val physicalFile = java.io.File(path.removePrefix("file://"))
                        if (physicalFile.exists()) physicalFile.delete()
                    } else {
                        val physicalFile = java.io.File(path)
                        if (physicalFile.exists()) physicalFile.delete()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SystemViewModel", "Failed to delete file: ${e.message}")
                }
            }
            
            chatDao.clearHistory()
            uploadedFileDao.markMultipleAsDeleted(ids)
            vectorStore.clear()
            _vectorDbUsage.value = 0L
            updateStats()
            addLog("STORAGE", "Deep purge complete: Files and DB cleared", "SUCCESS")
        }
    }


    fun openFile(file: com.chhanda.ai.data.repository.UploadedFileEntity) {
        try {
            val uri = android.net.Uri.parse(file.path)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            addLog("STORAGE", "Could not open file: ${file.name}", "ERROR")
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
            _tokensPerSec.value = "0.0"
            addLog("SYSTEM", "App shutdown requested. Disconnected all devices and stopped LLM.", "INFO")
        }
    }

    private fun showCompletionNotification(fileName: String) {
        val channelId = "rag_ingestion"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Knowledge Base", android.app.NotificationManager.IMPORTANCE_HIGH)
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setContentTitle("Scraping Completed")
            .setContentText("$fileName has been successfully indexed and stored in the database.")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager?.notify(7008, notification)
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
