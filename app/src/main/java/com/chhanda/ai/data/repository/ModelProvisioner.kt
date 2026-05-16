package com.chhanda.ai.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.presentation.ui.DownloadModelInfo
import com.chhanda.ai.presentation.ui.ModelInfo
import com.chhanda.ai.presentation.viewmodel.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmEngine: LLMEngine,
    private val securityRepository: SecurityRepository,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _ownedModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val ownedModels: StateFlow<List<ModelInfo>> = _ownedModels.asStateFlow()

    private val _sharedModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val sharedModels: StateFlow<List<ModelInfo>> = _sharedModels.asStateFlow()

    private val _downloadableModels = MutableStateFlow<List<DownloadModelInfo>>(emptyList())
    val downloadableModels: StateFlow<List<DownloadModelInfo>> = _downloadableModels.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _downloadStatus = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloadStatus: StateFlow<Map<String, DownloadStatus>> = _downloadStatus.asStateFlow()

    private val _downloadPauseState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val downloadPauseFlow: StateFlow<Map<String, Boolean>> = _downloadPauseState.asStateFlow()

    val downloadProgress: StateFlow<Map<String, Float>> = _downloadStatus.map { map ->
        map.mapValues { it.value.progress }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), emptyMap())

    private val activeDownloads = mutableMapOf<String, Long>()

    init {
        scope.launch {
            settingsRepository.activeModelFlow.collect {
                refreshModels()
            }
        }
    }

    fun refreshModels() {
        scope.launch {
            _isScanning.value = true
            try {
                val modelDir = File(context.getExternalFilesDir(null), "models")
                if (!modelDir.exists()) modelDir.mkdirs()
                
                val files = modelDir.listFiles { f -> f.extension == "bin" || f.extension == "gguf" || f.extension == "litertlm" } ?: emptyArray()
                val selectedModel = settingsRepository.activeModelFlow.firstOrNull()
                val currentLoaded = llmEngine.getCurrentModelName()
                val activeTarget = selectedModel ?: currentLoaded

                val owned = files.filter { !it.name.contains("shared", ignoreCase = true) }.map {
                    ModelInfo(
                        name = it.name, 
                        details = "${it.length() / 1024 / 1024} MB", 
                        isActive = it.name == activeTarget,
                        isMultimodal = it.name.contains("E4B", ignoreCase = true) || it.name.contains("multimodal", ignoreCase = true)
                    )
                }
                
                val shared = files.filter { it.name.contains("shared", ignoreCase = true) }.map {
                    ModelInfo(
                        name = it.name, 
                        details = "Shared Local Model", 
                        isActive = it.name == activeTarget,
                        isMultimodal = it.name.contains("E4B", ignoreCase = true) || it.name.contains("multimodal", ignoreCase = true)
                    )
                }

                _ownedModels.value = owned
                _sharedModels.value = shared
                
                _downloadableModels.value = listOf(
                    DownloadModelInfo("Gemma-4-E2B-IT", "Google's 2B parameter evolutionary model optimized for on-device reasoning.", "2.4 GB", isRecommended = true),
                    DownloadModelInfo("Gemma-4-E4B-IT", "Enhanced 4B parameter model with superior multimodal understanding.", "3.4 GB", isRecommended = true, isMultimodal = true)
                ).filter { model -> !owned.any { it.name.contains(model.name, ignoreCase = true) } }

            } finally {
                _isScanning.value = false
            }
        }
    }

    fun activateModel(modelName: String) {
        scope.launch {
            settingsRepository.setActiveModel(modelName)
            // Proactively refresh to show active selection in UI immediately
            refreshModels()
        }
    }

    fun deleteModel(modelName: String) {
        scope.launch {
            val modelDir = File(context.getExternalFilesDir(null), "models")
            val file = File(modelDir, modelName)
            
            if (file.exists()) {
                // IMPORTANT: Close engine FIRST to release file handles if this is the active model
                if (llmEngine.getCurrentModelName() == modelName) {
                    llmEngine.close()
                }
                
                val deleted = file.delete()
                if (deleted) {
                    Log.d("ModelProvisioner", "Successfully deleted model: $modelName")
                    // Clear from settings if it was the persisted choice
                    if (settingsRepository.activeModelFlow.firstOrNull() == modelName) {
                        settingsRepository.setActiveModel(null)
                    }
                    refreshModels()
                } else {
                    Log.e("ModelProvisioner", "Failed to delete model file: $modelName")
                }
            }
        }
    }

    fun startDownload(model: DownloadModelInfo) {
        val modelName = model.name
        val url = when(modelName) {
            "Gemma-4-E2B-IT" -> "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
            "Gemma-4-E4B-IT" -> "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
            else -> return
        }

        val hfToken = securityRepository.hfToken.value
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading $modelName")
            .setDescription("Chhanda AI Model Download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models", "$modelName.litertlm")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            
        if (url.contains("huggingface.co") && hfToken.isNotBlank()) {
            request.addRequestHeader("Authorization", "Bearer $hfToken")
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        activeDownloads[modelName] = downloadId
        
        monitorDownload(modelName, downloadId, dm)
    }

    fun pauseDownload(modelName: String) {
        _downloadPauseState.update { it + (modelName to true) }
        // Implement DownloadManager pause logic if needed
    }

    fun resumeDownload(modelName: String) {
        _downloadPauseState.update { it + (modelName to false) }
        // Implement DownloadManager resume logic if needed
    }

    fun cancelDownload(modelName: String) {
        val id = activeDownloads[modelName]
        if (id != null) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(id)
            activeDownloads.remove(modelName)
        }
        _downloadStatus.update { it - modelName }
        _downloadPauseState.update { it - modelName }
    }

    private fun monitorDownload(modelName: String, downloadId: Long, dm: DownloadManager) {
        scope.launch {
            var downloading = true
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            var smoothedSpeed = 0L
            val alpha = 0.3 // Smoothing factor for EMA

            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    
                    val now = System.currentTimeMillis()
                    val timeDiff = (now - lastTime) / 1000.0 // seconds
                    
                    if (timeDiff > 0) {
                        val currentMeasuredSpeed = ((bytesDownloaded - lastBytes) / timeDiff).toLong()
                        // Exponential Moving Average: smoothed = alpha * current + (1 - alpha) * last
                        smoothedSpeed = if (smoothedSpeed == 0L) currentMeasuredSpeed 
                                        else (alpha * currentMeasuredSpeed + (1.0 - alpha) * smoothedSpeed).toLong()
                    }
                    
                    lastBytes = bytesDownloaded
                    lastTime = now

                    val progress = if (bytesTotal > 0) bytesDownloaded.toFloat() / bytesTotal.toFloat() else 0f
                    
                    _downloadStatus.update { it + (modelName to DownloadStatus(progress, smoothedSpeed, bytesDownloaded, bytesTotal)) }

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            downloading = false
                            refreshModels()
                        }
                        DownloadManager.STATUS_FAILED -> {
                            downloading = false
                        }
                    }
                }
                cursor?.close()
                delay(1000)
            }
        }
    }
}
