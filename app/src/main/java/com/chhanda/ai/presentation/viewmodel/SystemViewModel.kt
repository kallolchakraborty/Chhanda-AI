package com.chhanda.ai.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.SettingsRepository
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.VectorStore
import com.chhanda.ai.util.Localization
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
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

data class VirtualVoice(val name: String, val isMale: Boolean)

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
    private val scrapeUrlUseCaseLazy: dagger.Lazy<com.chhanda.ai.domain.usecase.ScrapeUrlUseCase>,
    private val metricsManagerLazy: dagger.Lazy<com.chhanda.ai.domain.model.RAGMetricsManager>,
    private val thermalStatusTracker: com.chhanda.ai.util.ThermalStatusTracker,
    private val hardwareMonitor: com.chhanda.ai.data.repository.HardwareMonitor,
    private val networkManager: com.chhanda.ai.data.repository.NetworkManager,
    private val serverOrchestrator: com.chhanda.ai.data.inference.ServerOrchestrator,
    private val modelProvisioner: com.chhanda.ai.data.repository.ModelProvisioner,
    private val securityRepository: com.chhanda.ai.data.repository.SecurityRepository,
) : ViewModel() {

    private val llmEngine get() = llmEngineLazy.get()
    private val vectorStore get() = vectorStoreLazy.get()
    private val chhandaServer get() = chhandaServerLazy.get()
    private val ingestDocumentUseCase get() = ingestDocumentUseCaseLazy.get()
    private val scrapeUrlUseCase get() = scrapeUrlUseCaseLazy.get()
    private val metricsManager get() = metricsManagerLazy.get()

    // Telemetry Delegation
    val latencyMetrics = hardwareMonitor.latencyMetrics
    val throughputMetrics = hardwareMonitor.throughputMetrics
    val memoryMetrics = hardwareMonitor.memoryMetrics
    val qualityMetrics = hardwareMonitor.qualityMetrics
    val costMetrics = hardwareMonitor.costMetrics
    val processorInfo = hardwareMonitor.processorInfo
    val tokensPerSec = hardwareMonitor.tokensPerSec
    val deviceTemperature = hardwareMonitor.batteryTemp

    // Model Delegation
    val ownedModels = modelProvisioner.ownedModels
    val sharedModels = modelProvisioner.sharedModels
    val downloadableModels = modelProvisioner.downloadableModels
    val isScanning = modelProvisioner.isScanning
    val downloadProgress = modelProvisioner.downloadProgress
    val downloadStatus = modelProvisioner.downloadStatus
    val modelLoadingProgress = llmEngine.loadingProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0f)

    val isModelLoading = modelLoadingProgress
        .map { it > 0f && it < 1.0f }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    // Network Delegation
    val isVpnActive = networkManager.isVpnActive
    val networkIps = networkManager.allIps
    val hasNetwork = networkManager.isConnected

    // Server Delegation
    val isServerRunning = serverOrchestrator.isServerRunning
    val serverActualPort = serverOrchestrator.boundPort
    val serverError = serverOrchestrator.serverError
    val tunnelUrl = serverOrchestrator.tunnelUrl

    fun manualRefreshNetwork() {
        networkManager.refreshNetwork()
    }
    val isTunnelActive = serverOrchestrator.isTunnelActive





    private var activeScrapeJob: kotlinx.coroutines.Job? = null

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

            val filesToDelete = allFiles.value.filter { it.id in ids }
            filesToDelete.forEach { file ->
                try {
                    val path = file.path
                    if (path.startsWith("content://")) {
                        context.contentResolver.delete(android.net.Uri.parse(path), null, null)
                    } else {

                        val physicalFile = java.io.File(path)
                        if (physicalFile.exists()) physicalFile.delete()
                    }

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



    // reAttachDownloads removed - handled by ModelProvisioner

    private val workManager by lazy { androidx.work.WorkManager.getInstance(context) }

    fun ingestDocuments(uris: List<android.net.Uri>) {

        var totalSize = 0L
        uris.forEach { uri ->
            try {
                totalSize += context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            } catch (e: Exception) {}
        }

        if (totalSize > 1024 * 1024) { 
            pendingBackgroundPrompt = IngestionTask(uris = uris)
            return
        }

        processIngestDocuments(uris)
    }

    fun processIngestDocuments(uris: List<android.net.Uri>, inBackground: Boolean = false) {
        pendingBackgroundPrompt = null

        if (hardwareMonitor.storageMetrics.value.vectorDbBytes >= vectorDbCapacityBytes.value * 0.9) {
            _showVectorStorageWarning.value = true
            return
        }

        if (inBackground) {
            uris.forEach { uri ->
                val type = getDocType(uri)

                viewModelScope.launch(Dispatchers.IO) {
                    val fileDetails = com.chhanda.ai.util.FileUtils.getFileDetails(context, uri)
                    val fileName = fileDetails.first
                    val existing = uploadedFileDao.findByNameAndSize(fileName, fileDetails.second)
                    if (existing != null) {
                        addLog("STORAGE", "Duplicate background file skipped: $fileName", "SUCCESS")
                    } else {
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
                }
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

                    val fileDetails = com.chhanda.ai.util.FileUtils.getFileDetails(context, uri)
                    val name = fileDetails.first
                    val size = fileDetails.second
                    val format = type.name
                    val path = uri.toString()

                    _ingestionMessage.value = "Processing $name ($format)..."

                    val existingFile = uploadedFileDao.findByNameAndSize(name, size)
                    if (existingFile != null) {
                        addLog("STORAGE", "Duplicate file skipped: $name", "SUCCESS")
                        processedFiles++
                        continue
                    }

                    addLog("STORAGE", "Ingesting document: $name", "PENDING")
                    val activeModel = "shared_rag_db"

                    val baseProgress = processedFiles.toFloat() / totalFiles
                    val fileWeight = 1f / totalFiles

                    ingestDocumentUseCase(uri, type, modelId = "shared_rag_db") { fileProgress ->
                        _ingestionProgress.value = baseProgress + (fileProgress * fileWeight)
                    }

                    processedFiles++

                    uploadedFileDao.insertFile(com.chhanda.ai.data.repository.UploadedFileEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        format = type.name,
                        size = size,
                        path = uri.toString(),
                        timestamp = System.currentTimeMillis()
                    ))

                    addLog("STORAGE", "Document $name ingested successfully", "SUCCESS")
                }

                _isIngesting.value = false
                _ingestionProgress.value = 1.0f
                _ingestionMessage.value = "All $totalFiles files processed successfully. Tap to close."
                hardwareMonitor.startMonitoring()
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
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || 
            mimeType == "application/msword" -> com.chhanda.ai.domain.usecase.DocType.WORD
            mimeType == "application/vnd.ms-excel" || 
            mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> com.chhanda.ai.domain.usecase.DocType.EXCEL
            mimeType == "application/json" -> com.chhanda.ai.domain.usecase.DocType.JSON
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

    val darkMode = settingsRepository.darkModeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val hfToken = securityRepository.hfToken.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val serverPort = settingsRepository.serverPortFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "8080")
    val contextLength = settingsRepository.contextLengthFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "2048")
    val maxDevices = settingsRepository.maxDevicesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 5)
    val apiKey = securityRepository.apiKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "Initializing...")
    val publicUrl = settingsRepository.publicUrlFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val appLanguage = settingsRepository.appLanguageFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "English")
    val autoDeleteDays = settingsRepository.autoDeleteDaysFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 7)
    val autoDeleteEnabled = settingsRepository.autoDeleteEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val turboQuantEnabled = settingsRepository.turboQuantEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val selectedVoice = settingsRepository.selectedVoiceFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "Kallol (Indian Male)")
    val ragEnabled = settingsRepository.ragEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val thinkingModeEnabled = settingsRepository.thinkingModeEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val privacyShieldEnabled = settingsRepository.privacyShieldEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val vectorDbCapacityBytes = hardwareMonitor.storageMetrics.map { 
        val dynamicLimit = (it.deviceAvailableBytes * 0.15).toLong()
        maxOf(1024L * 1024 * 1024, dynamicLimit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 1024L * 1024 * 1024)
    private val _showRestartDialog = MutableStateFlow(false)
    val showRestartDialog: StateFlow<Boolean> = _showRestartDialog

    private val _showServerRunningWarning = MutableStateFlow(false)
    val showServerRunningWarning: StateFlow<Boolean> = _showServerRunningWarning.asStateFlow()

    fun dismissServerRunningWarning() {
        _showServerRunningWarning.value = false
    }

    fun setThinkingModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setThinkingModeEnabled(enabled) }
    }

    fun setPrivacyShieldEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPrivacyShieldEnabled(enabled) }
    }

    fun exportMemory(onResult: (File?) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var file: File? = null
            try {
                val messages = chatDao.getAllMessagesSync()
                if (messages.isEmpty()) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) { 
                        _ingestionError.value = "No chat history to export."
                        onResult(null) 
                    }
                    return@launch
                }

                val fileName = "chhanda_memory_export_${System.currentTimeMillis()}.json"
                file = File(context.cacheDir, fileName)

                file.outputStream().use { outputStream ->
                    val writer = android.util.JsonWriter(outputStream.bufferedWriter())
                    writer.setIndent("  ")
                    writer.beginObject()
                    writer.name("export_date").value(System.currentTimeMillis())
                    writer.name("device_id").value("local")
                    writer.name("messages")
                    writer.beginArray()
                    for (msg in messages) {
                        writer.beginObject()
                        writer.name("text").value(msg.text)
                        writer.name("role").value(msg.role)
                        writer.name("timestamp").value(msg.timestamp)
                        writer.name("model").value(msg.modelName)
                        writer.name("session").value(msg.sessionId)
                        writer.endObject()
                    }
                    writer.endArray()
                    writer.endObject()
                    writer.close()
                }

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(file)
                    addLog("MEMORY", "Memory exported to ${file?.name}", "SUCCESS")
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _ingestionError.value = "Export failed: ${e.message}"
                    onResult(null)
                    addLog("MEMORY", "Export failed: ${e.message}", "ERROR")
                }
            }
        }
    }

    val vectorDbUsage = hardwareMonitor.storageMetrics.map { it.vectorDbBytes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0L)

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

    private val _isAppVisible = MutableStateFlow(true)
    fun onVisibilityChanged(visible: Boolean) {
        _isAppVisible.value = visible
        hardwareMonitor.setAppVisibility(visible)
        if (visible) {
            addLog("SYSTEM", "UI foreground: Resuming monitors", "INFO")
        } else {
            addLog("SYSTEM", "UI background: Throttling monitors", "INFO")
        }
    }

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    fun deleteLogs(logIds: List<String>) {
        _logs.value = _logs.value.filter { it.id !in logIds }
        addLog("SYSTEM", "Deleted ${logIds.size} logs", "INFO")
    }

    fun clearAllLogs() {
        _logs.value = emptyList()
        saveLogsToFile(emptyList()) 
    }

    val isThinkingSupported = combine(ownedModels, sharedModels) { owned, shared ->
        val activeModel = (owned + shared).find { it.isActive }
        activeModel?.name?.contains("deepseek", ignoreCase = true) == true || 
        activeModel?.name?.contains("r1", ignoreCase = true) == true ||
        activeModel?.name?.contains("gemma-4", ignoreCase = true) == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    private val _recommendedModel = MutableStateFlow<String?>(null)
    val recommendedModel: StateFlow<String?> = _recommendedModel

    private val _isConfigEnabled = MutableStateFlow(true)
    val isConfigEnabled: StateFlow<Boolean> = _isConfigEnabled

    val downloadPauseFlow = modelProvisioner.downloadPauseFlow

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
                messages = deviceMessages.takeLast(50) 
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

    val activeDeviceCount = kotlinx.coroutines.flow.flow {
        while(true) {
            emit(System.currentTimeMillis())
            kotlinx.coroutines.delay(2000)
        }
    }.combine(_connectedDevices) { now, devices ->
        val cutoff = now - 40000 
        devices.count { it.isCurrentlyConnected && it.lastActive > cutoff }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    val localIpAddress: StateFlow<String> = flow {
        while(true) {
            val ip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (serverOrchestrator.isServerRunning.value) {
                    try { 
                        val bound = chhandaServer.boundIp
                        if (bound.isBlank() || bound == "0.0.0.0" || bound == "127.0.0.1") networkManager.getBestIp() else bound
                    } catch (e: Exception) { networkManager.getBestIp() }
                } else {
                    networkManager.getBestIp()
                }
            }
            emit(ip)
            delay(5000) 
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Detecting...")

    fun toggleServer() {
        if (serverOrchestrator.isServerRunning.value) {
            serverOrchestrator.stopServer()
            viewModelScope.launch {
                try { llmEngine.close() } catch(_: Exception) {}
                modelProvisioner.refreshModels()
                addLog("SERVER", "Manual Stop Requested", "WARN")
            }
        } else {
            viewModelScope.launch {
                val portStr = settingsRepository.serverPortFlow.first()
                val maxDevices = settingsRepository.maxDevicesFlow.first()
                val port = portStr.toIntOrNull() ?: 8888
                serverOrchestrator.startServer(port, maxDevices)
                addLog("SERVER", "Local AI Gateway Online (Port $port)", "SUCCESS")
            }
        }
    }

    // Duplicate network states removed

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

                if (_isAppVisible.value && serverOrchestrator.isServerRunning.value) {
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

    private val _availableVoices = MutableStateFlow<List<String>>(
        listOf(
            "Kallol (Indian Male)", "Chhanda (Indian Female)"
        )
    )
    val availableVoices: StateFlow<List<String>> = _availableVoices.asStateFlow()

    private var sampleTts: android.speech.tts.TextToSpeech? = null

    fun playSample(virtualVoiceName: String, languageName: String) {
        val locale = when (languageName) {
            "Bengali" -> Locale("bn", "BD")
            "Hindi" -> Locale("hi", "IN")
            "French" -> Locale.FRENCH
            "German" -> Locale.GERMAN
            else -> Locale.ENGLISH
        }

        if (sampleTts == null) {
            sampleTts = android.speech.tts.TextToSpeech(context) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    speakSampleInternal(virtualVoiceName, locale)
                }
            }
        } else {
            speakSampleInternal(virtualVoiceName, locale)
        }
    }

    private fun speakSampleInternal(virtualVoiceName: String, locale: Locale) {
        sampleTts?.language = locale
        sampleTts?.setSpeechRate(0.9f)
        sampleTts?.setPitch(1.0f)

        val isMale = virtualVoiceName.contains("Male")

        val cleanName = virtualVoiceName.substringBefore(" (")

        val systemVoices = sampleTts?.voices?.toList() ?: emptyList()

        val pool = systemVoices.filter { v -> v.locale.language == locale.language }
            .filter { v ->
                val name = v.name.lowercase()
                if (isMale) {

                    name.contains("male") || name.contains("-m-") || name.contains("_m_") || 
                    name.contains("ahp") || name.contains("hie") || name.contains("baq") ||
                    name.contains("guy") || name.contains("man") || name.contains("boy") ||
                    (v.locale.country == "IN" && (name.contains("en-in-x-ahp") || name.contains("hi-in-x-hie")))
                } else {

                    name.contains("female") || name.contains("-f-") || name.contains("_f_") || 
                    name.contains("ahi") || name.contains("hif") || name.contains("ban") ||
                    name.contains("girl") || name.contains("woman") || name.contains("lady") ||
                    (v.locale.country == "IN" && (name.contains("en-in-x-ahi") || name.contains("hi-in-x-hif")))
                }
            }
            .sortedByDescending { v ->
                val name = v.name.lowercase()
                var score = 0

                if (!v.isNetworkConnectionRequired) score += 100 
                if (name.contains("network") || name.contains("neural")) score += 50
                if (name.contains("high") || name.contains("premium")) score += 30
                score
            }

        val voiceToUse = pool.firstOrNull() ?: run {
            val localeVoices = systemVoices.filter { it.locale.language == locale.language }
            if (isMale && localeVoices.size > 1) {

                localeVoices.getOrNull(1) ?: localeVoices.firstOrNull()
            } else {
                localeVoices.firstOrNull()
            }
        }

        if (voiceToUse != null) {
            sampleTts?.voice = voiceToUse
            Log.d("SystemViewModel", "Selected system voice ${voiceToUse.name} (${if(isMale) "M" else "F"} heuristic) for $virtualVoiceName")
        }

        val languageName = when (locale.language) {
            "bn" -> "Bengali"
            "hi" -> "Hindi"
            "fr" -> "French"
            "de" -> "German"
            else -> "English"
        }

        val template = Localization.getString("tts_intro_template", languageName)
        val sampleText = template.replace("{name}", cleanName)

        sampleTts?.speak(sampleText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    init {
        viewModelScope.launch {
            try {
                delay(500)
                setupLogs()
                setupSystem()
            } catch (e: Throwable) {
                Log.e("SystemViewModel", "CRITICAL: Initialization failure detected", e)
            }
        }
    }

    private fun setupLogs() {
        _logs.value = loadLogsFromFile()
        checkAndPerformCleanup()
    }

    private fun setupSystem() {
        viewModelScope.launch {
            try {
                val file = java.io.File(context.filesDir, "crash_log.txt")
                if (file.exists()) {
                    val crashLog = file.readText()
                    addLog("CRASH", crashLog, "ERROR")
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e("SystemViewModel", "Crash log reader failed", e)
            }
            
            // reAttachDownloads removed - handled by ModelProvisioner
            delay(1000)
            provisionDefaultDevice()
            ensureDefaultPort()
            addLog("SYSTEM", "Chhanda Gateway Refactored Active", "SUCCESS")
        }
        
        viewModelScope.launch {
            try {
                val currentKey = securityRepository.apiKey.value
                if (currentKey == "Initializing..." || currentKey == "000000000") {
                    val newKey = "CH-${java.util.UUID.randomUUID().toString().take(8).uppercase()}"
                    securityRepository.setApiKey(newKey)
                    addLog("SYSTEM", "Provisioned device node key: $newKey", "SUCCESS")
                }
            } catch (e: Exception) {
                Log.e("SystemViewModel", "API Key provision failed", e)
            }
        }
    }

    private fun updateTelemetry() {
        // Handled by HardwareMonitor
    }

    private suspend fun provisionDefaultDevice() {
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
            Log.e("SystemViewModel", "Default device provision failed", e)
        }
    }

    private suspend fun ensureDefaultPort() {
        try {
            val currentPort = settingsRepository.serverPortFlow.first()
            if (currentPort == "8000" || currentPort == "8080" || currentPort == "") {
                settingsRepository.setServerPort("8888")
            }
        } catch (e: Exception) {
            Log.e("SystemViewModel", "Port check failed", e)
        }
    }

    private suspend fun reapDevices() {
        try {
            val fiveMinutesAgo = System.currentTimeMillis() - 5 * 60 * 1000
            val activeDevices = deviceDao.getActiveConnections()
            activeDevices.forEach { device ->
                if (device.lastActive < fiveMinutesAgo) {
                    deviceDao.updateDeviceStatus(device.deviceName, false, device.lastActive)
                    addLog("NETWORK", "Device ${device.deviceName} timed out.", "INFO")
                }
            }
        } catch (e: Exception) {
            Log.e("SystemViewModel", "Device reaper failed", e)
        }
    }

    private suspend fun performHealthCheck() {
        if (!serverOrchestrator.isServerRunning.value) return
        val port = try { chhandaServer.port } catch (e: Exception) { -1 }
        if (port <= 0) return
        
        val isOk = kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val client = URL("http://127.0.0.1:$port/ping").openConnection() as HttpURLConnection
                client.connectTimeout = 1000
                client.readTimeout = 1000
                client.inputStream.bufferedReader().use { it.readText() } == "pong"
            } catch (e: Exception) { false }
        }
        _isLocalLinkOk.value = isOk
    }

    override fun onCleared() {
        super.onCleared()
        sampleTts?.stop()
        sampleTts?.shutdown()
    }

    fun getIpAddress(): String {
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

            return candidates.sortedWith(compareBy { (name, ip) ->
                when {

                    ip == "192.168.43.1" || ip == "192.168.44.1" || ip == "192.168.45.1" -> 0

                    name.contains("ap0") || name.contains("softap") || name.contains("swlan") -> 1

                    name.startsWith("wlan") -> 2

                    name.startsWith("eth") -> 3
                    else -> 4
                }
            }).firstOrNull()?.second ?: "127.0.0.1"
        } catch (e: Exception) { }
        return "127.0.0.1"
    }



    private val _modelPaths = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _isScanning = MutableStateFlow(false)
    // Duplicate model states removed

    fun scanForModels() {
        modelProvisioner.refreshModels()
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

    // updateStats removed - handled by HardwareMonitor

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDarkMode(enabled)
            addLog("CONFIG", "Dark mode set to $enabled", "INFO")
        }
    }

    fun setHfToken(token: String) {
        viewModelScope.launch {
            securityRepository.setHfToken(token)
            addLog("CONFIG", "HuggingFace token updated", "INFO")
        }
    }

    fun toggleTurboQuant(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTurboQuantEnabled(enabled)
            addLog("CONFIG", "TurboQuant set to $enabled", "INFO")
        }
    }

    fun toggleRag(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRagEnabled(enabled)
            addLog("ENGINE", "Long-term Vector Memory ${if (enabled) "Enabled" else "Disabled"}", "INFO")
        }
    }

    // Storage notifications removed - will be refactored into a separate manager

    fun setAppLanguage(language: String) {
        if (appLanguage.value == language) return

        if (serverOrchestrator.isServerRunning.value) {
            _showServerRunningWarning.value = true
            return
        }
        viewModelScope.launch {
            settingsRepository.setAppLanguage(language)
            _showRestartDialog.value = true
            addLog("CONFIG", "App language set to $language. Restart required.", "INFO")
        }
    }

    fun setSelectedVoice(voice: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedVoice(voice)
            addLog("CONFIG", "Selected voice set to $voice", "INFO")
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

    fun updateContextLength(length: String) {
        viewModelScope.launch {
            settingsRepository.setContextLength(length)
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
            securityRepository.setApiKey(key)
            addLog("CONFIG", "Global API Key updated", "SUCCESS")
        }
    }

    fun stopServer() {
        serverOrchestrator.stopServer()
        viewModelScope.launch {
            try { llmEngine.close() } catch(_: Exception) {}
            modelProvisioner.refreshModels()
            addLog("SERVER", "Web Gateway and Inference Engine stopped", "WARNING")
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
            addLog("SYSTEM", "Activating model: $modelName", "INFO")
            modelProvisioner.activateModel(modelName)
            addLog("SYSTEM", "Model activation requested", "SUCCESS")
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

            addLog("SYSTEM", "Manually registered model: ${file.name}", "SUCCESS")
            modelProvisioner.refreshModels()
            activateModel(name)
        }
    }

    fun downloadModel(model: com.chhanda.ai.presentation.ui.DownloadModelInfo) {
        viewModelScope.launch {
            addLog("DOWNLOAD", "Download request: ${model.name}", "INFO")
            modelProvisioner.startDownload(model)
        }
    }

    private fun observeDownloadProgress(modelName: String, workId: java.util.UUID) {
        // Now handled by ModelProvisioner
    }

    fun isInternetAvailable(): Boolean {
        return networkManager.isConnected.value
    }

    private fun checkForModelUpdates() {
        if (!isInternetAvailable()) {
            addLog("SYSTEM", "Offline: Skipping model update check", "INFO")
            return
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val modelsToCheck = modelProvisioner.ownedModels.value
            if (modelsToCheck.isEmpty()) return@launch

            addLog("SYSTEM", "Checking for model updates...", "INFO")

            var updatesFound = 0
            val updatedModels = modelProvisioner.ownedModels.value.toMutableList()

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

                        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val filename = getFilenameForModel(model.name)
                        val localFile = java.io.File(dir, filename)

                        if (localFile.exists() && remoteSize > 0 && localFile.length() != remoteSize) {

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
                modelProvisioner.refreshModels()
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

                val ids = allFiles.value.map { it.id }
                uploadedFileDao.markMultipleAsDeleted(ids)
                hardwareMonitor.startMonitoring()
                addLog("STORAGE", "Vector database emptied successfully", "SUCCESS")
            } catch (e: Exception) {
                addLog("STORAGE", "Failed to clear vector store: ${e.message}", "ERROR")
            }
        }
    }

    fun scrapeAndIngestUrl(url: String, label: String) {

        pendingBackgroundPrompt = IngestionTask(url = url, label = label)
    }

    fun processScrapeUrl(url: String, label: String, inBackground: Boolean = false) {
        pendingBackgroundPrompt = null

        if (!isInternetAvailable()) {
            _showInternetWarning.value = true
            addLog("SYSTEM", "Internet not present. Cannot proceed with the scraping of $label.", "ERROR")
            return
        }

        val isKaggle = url.contains("kaggle.com", ignoreCase = true)

        if (isKaggle && !serverOrchestrator.isServerRunning.value) {
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

            val timerJob = launch {
                delay(30000)
                if (_isIngesting.value && _ingestionMessage.value.contains("Scraping")) {
                    pendingBackgroundPrompt = IngestionTask(url = url, label = label)
                }
            }

            try {

                val scrapedText = if (isKaggle) {
                    _ingestionMessage.value = "AI-Assisted Kaggle Parsing..."

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
                hardwareMonitor.startMonitoring()
            }
        }
    }

    private suspend fun getVectorDbSize(): Long {

        val embeddingSize = try { vectorChunkDao.getTotalEmbeddingSize() ?: 0L } catch (e: Exception) { 0L }
        val count = try { vectorChunkDao.getCount() } catch (e: Exception) { 0 }

        val estimatedTotal = embeddingSize + (count.toLong() * 1000L)
        return estimatedTotal
    }

    private fun getAppStorageSize(): Long {
        var totalSize = 0L

        totalSize += getDirSize(context.filesDir)
        totalSize += getDirSize(context.cacheDir)

        context.getExternalFilesDir(null)?.let { totalSize += getDirSize(it) }
        context.externalCacheDir?.let { totalSize += getDirSize(it) }

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
                } catch (e: Exception) {  }
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
        modelProvisioner.pauseDownload(modelName)
        addLog("DOWNLOAD", "Download paused: $modelName", "INFO")
    }

    fun resumeDownload(modelName: String, model: com.chhanda.ai.presentation.ui.DownloadModelInfo) {
        modelProvisioner.resumeDownload(modelName)
        addLog("DOWNLOAD", "Download resumed: $modelName", "INFO")
    }

    fun cancelDownload(modelName: String) {
        modelProvisioner.cancelDownload(modelName)
        addLog("DOWNLOAD", "Download cancelled: $modelName", "WARNING")
    }

    fun stopGlobalInference() {
        llmEngine.stopInference()
        serverOrchestrator.stopServer()
        hardwareMonitor.setPerformanceMetrics(0.0)
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
                    tempFile.renameTo(file) 
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
        
        logSaveJob?.cancel()
        logSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(2000)
            saveLogsToFile(_logs.value)
        }
    }
    
    private var logSaveJob: Job? = null
    private var cachedStorageSize: Long = 0L
    private var lastStorageCheck: Long = 0L
    private var storageDirty: Boolean = true

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

            val isActive = (ownedModels.value + sharedModels.value).any { it.isActive && it.name == modelName }
            if (isActive) {
                try { llmEngine.close() } catch (_: Exception) {}
                try { com.chhanda.ai.service.ChhandaForegroundService.stop(context) } catch (_: Exception) {}
                serverOrchestrator.updateStatus(false, 0)
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

            _modelPaths.value = _modelPaths.value - modelName
            modelProvisioner.refreshModels()
        }
    }


    fun shutdown() {
        viewModelScope.launch {
            try { llmEngine.close() } catch (_: Exception) {}
            try { com.chhanda.ai.service.ChhandaForegroundService.stop(context) } catch (_: Exception) {}
            hardwareMonitor.setPerformanceMetrics(0.0)
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

data class DownloadStatus(
    val progress: Float,
    val speedBytesPerSec: Long,
    val downloadedBytes: Long,
    val totalBytes: Long
)

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
