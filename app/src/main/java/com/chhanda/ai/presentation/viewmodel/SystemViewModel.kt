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
import com.chhanda.ai.domain.model.LogEntry
import com.chhanda.ai.util.AppLogManager
import com.chhanda.ai.domain.service.IngestionManager
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
    private val chatDao: ChatDao,
    private val deviceDao: com.chhanda.ai.data.repository.DeviceDao,
    private val metricsManagerLazy: dagger.Lazy<com.chhanda.ai.domain.model.RAGMetricsManager>,
    private val thermalStatusTracker: com.chhanda.ai.util.ThermalStatusTracker,
    private val hardwareMonitor: com.chhanda.ai.data.repository.HardwareMonitor,
    private val networkManager: com.chhanda.ai.data.repository.NetworkManager,
    private val serverOrchestrator: com.chhanda.ai.data.inference.ServerOrchestrator,
    private val modelProvisioner: com.chhanda.ai.data.repository.ModelProvisioner,
    private val securityRepository: com.chhanda.ai.data.repository.SecurityRepository,
    private val appLogManager: AppLogManager,
    private val ingestionManager: IngestionManager,
    private val chhandaServer: com.chhanda.ai.data.inference.ChhandaServer,
    private val cloudSyncManager: com.chhanda.ai.data.sync.CloudSyncManager,
) : ViewModel() {

    private val llmEngine get() = llmEngineLazy.get()
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

    val isModelLoaded = llmEngine.isModelLoaded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val isModelLoading = serverOrchestrator.isModelLoading

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





    // Ingestion Delegation
    val isIngesting = ingestionManager.isIngesting
    val ingestionProgress = ingestionManager.ingestionProgress
    val ingestionMessage = ingestionManager.ingestionMessage
    private val _ingestionError = MutableStateFlow<String?>(null)
    val ingestionError = combine(ingestionManager.ingestionError, _ingestionError) { managerErr, localErr ->
        managerErr ?: localErr
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    val uploadedFiles = ingestionManager.uploadedFiles
    val pendingBackgroundPrompt = ingestionManager.pendingBackgroundPrompt

    private val _showVectorStorageWarning = MutableStateFlow(false)
    val showVectorStorageWarning = _showVectorStorageWarning.asStateFlow()

    fun dismissVectorStorageWarning() {
        _showVectorStorageWarning.value = false
    }

    fun dismissPendingPrompt() {
        ingestionManager.dismissPendingPrompt()
    }

    fun dismissIngestionProgress() {
        // Handled via flows
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

    val allFiles = ingestionManager.uploadedFiles

    val recentFiles = allFiles.map { it.take(10) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

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
        ingestionManager.deleteFiles(ids)
    }



    fun checkAndPerformCleanup() {
        ingestionManager.performCleanup()
    }





    // reAttachDownloads removed - handled by ModelProvisioner

    private val workManager by lazy { androidx.work.WorkManager.getInstance(context) }

    fun ingestDocuments(uris: List<android.net.Uri>) {
        ingestionManager.ingestDocuments(uris)
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
        ingestionManager.dismissPendingPrompt()
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

    val darkMode = settingsRepository.darkModeFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val hfToken = securityRepository.hfToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val serverPort = settingsRepository.serverPortFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "8080")
    val contextLength = settingsRepository.contextLengthFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "2048")
    val maxDevices = settingsRepository.maxDevicesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    val apiKey = securityRepository.apiKey.stateIn(viewModelScope, SharingStarted.Eagerly, "Initializing...")
    val publicUrl = settingsRepository.publicUrlFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val appLanguage = settingsRepository.appLanguageFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "English")
    val autoDeleteDays = settingsRepository.autoDeleteDaysFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 7)
    val autoDeleteEnabled = settingsRepository.autoDeleteEnabledFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val turboQuantEnabled = settingsRepository.turboQuantEnabledFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val selectedVoice = settingsRepository.selectedVoiceFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "Kallol (Indian Male)")
    val ragEnabled = settingsRepository.ragEnabledFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val thinkingModeEnabled = settingsRepository.thinkingModeEnabledFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val privacyShieldEnabled = settingsRepository.privacyShieldEnabledFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val appSecurityEnabled = settingsRepository.appSecurityEnabledFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val vectorDbCapacityBytes = hardwareMonitor.storageMetrics.map { 
        val dynamicLimit = (it.deviceAvailableBytes * 0.15).toLong()
        maxOf(1024L * 1024 * 1024, dynamicLimit)
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 1024L * 1024 * 1024)
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

    fun setAppSecurityEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAppSecurityEnabled(enabled) }
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
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "Calculating...")

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

    // Log Delegation
    val logs: StateFlow<List<LogEntry>> = appLogManager.logs

    // --- Analytics Dashboard Flows ---
    val tpsHistory = hardwareMonitor.tpsHistory
    val ramHistory = hardwareMonitor.ramHistory
    val sessionTokens = hardwareMonitor.sessionTokens
    val sessionCostSaved = hardwareMonitor.sessionCostSaved
    // ---------------------------------

    val isThinkingSupported = combine(ownedModels, sharedModels) { owned, shared ->
        val activeModel = (owned + shared).find { it.isActive }
        activeModel?.name?.contains("deepseek", ignoreCase = true) == true || 
        activeModel?.name?.contains("r1", ignoreCase = true) == true ||
        activeModel?.name?.contains("gemma-4", ignoreCase = true) == true
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

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
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), StorageSummary(0, 0, emptyList()))

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
        } else {
            serverOrchestrator.startServer()
        }
    }

    // Duplicate network states removed

    fun toggleTunnel() {
        serverOrchestrator.toggleTunnel()
    }

    val isLocalLinkOk = serverOrchestrator.isLocalLinkOk



    val deviceModelName = (android.os.Build.MODEL ?: "Unknown_Device").replace(" ", "_")

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
                checkAndPerformCleanup()
                setupSystem()
                
                // Dynamically detect hardware capability to recommend model
                val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memoryInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val totalRamGb = memoryInfo.totalMem / (1024.0 * 1024 * 1024)
                val recModel = if (totalRamGb >= 6.5) "Gemma-4-E4B-IT" else "Gemma-4-E2B-IT"
                _recommendedModel.value = recModel
                addLog("SYSTEM", "Device RAM detected: ${"%.1f".format(totalRamGb)} GB. Recommended: $recModel", "INFO")
            } catch (e: Throwable) {
                Log.e("SystemViewModel", "CRITICAL: Initialization failure detected", e)
            }
        }
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
        ingestionManager.deleteAllFiles()
    }



    fun scrapeUrl(url: String, inBackground: Boolean = false, label: String? = null) {
        ingestionManager.scrapeUrl(url, inBackground, label)
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

    fun revokeAllSessions() {
        viewModelScope.launch {
            securityRepository.revokeAllSessions()
            deviceDao.disconnectAllDevices()
            addLog("SECURITY", "All sessions revoked. API Key rotated.", "WARN")
        }
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
            ingestionManager.deleteAllFiles()
            chatDao.clearHistory()
            addLog("SYSTEM", "Deep purge complete: Knowledge base and history cleared", "SUCCESS")
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

    fun addLog(tag: String, message: String, level: String) {
        appLogManager.addLog(tag, message, level)
    }

    fun deleteLogs(logIds: List<String>) {
        appLogManager.deleteLogs(logIds)
    }

    fun clearAllLogs() {
        appLogManager.clearAllLogs()
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
            // Coordinate with server if active model is being deleted
            val isActive = (ownedModels.value + sharedModels.value).any { it.isActive && it.name == modelName }
            if (isActive) {
                appLogManager.addLog("SYSTEM", "Active model deletion triggered. Stopping server...", "WARN")
                serverOrchestrator.stopServer()
            }
            
            // Delegate actual file deletion to provisioner which knows the paths
            modelProvisioner.deleteModel(modelName)
            addLog("SYSTEM", "Deletion request sent for $modelName", "INFO")
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

    // --- Cloud Sync ---
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Long>(0L)
    val lastSyncTime = _lastSyncTime.asStateFlow()

    fun backupToCloud(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        viewModelScope.launch {
            _isSyncing.value = true
            addLog("CLOUD", "Starting encrypted backup to Google Drive...", "INFO")
            val success = cloudSyncManager.backupHistory(account)
            if (success) {
                _lastSyncTime.value = System.currentTimeMillis()
                addLog("CLOUD", "Cloud backup successful and encrypted.", "SUCCESS")
            } else {
                addLog("CLOUD", "Cloud backup failed. Check connection or Drive space.", "ERROR")
            }
            _isSyncing.value = false
        }
    }

    fun restoreFromCloud(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        viewModelScope.launch {
            _isSyncing.value = true
            addLog("CLOUD", "Restoring history from encrypted cloud backup...", "INFO")
            val success = cloudSyncManager.restoreHistory(account)
            if (success) {
                addLog("CLOUD", "Cloud restore successful. Local history merged.", "SUCCESS")
            } else {
                addLog("CLOUD", "Cloud restore failed or no backup found.", "ERROR")
            }
            _isSyncing.value = false
        }
    }


}

data class DownloadStatus(
    val progress: Float,
    val speedBytesPerSec: Long,
    val downloadedBytes: Long,
    val totalBytes: Long
)

@kotlinx.serialization.Serializable

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
