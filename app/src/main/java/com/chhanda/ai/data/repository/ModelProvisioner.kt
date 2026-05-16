package com.chhanda.ai.data.repository

import android.content.Context
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
        
        val workManager = androidx.work.WorkManager.getInstance(context)
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.chhanda.ai.service.DownloadWorker>()
            .setInputData(androidx.work.workDataOf(
                "url" to url,
                "filename" to "$modelName.litertlm",
                "token" to hfToken
            ))
            .addTag("download_$modelName")
            .build()
            
        workManager.enqueueUniqueWork(
            "download_$modelName",
            androidx.work.ExistingWorkPolicy.KEEP,
            workRequest
        )
        
        observeWorkManagerDownload(modelName, workRequest.id, workManager)
    }

    private fun observeWorkManagerDownload(modelName: String, workId: java.util.UUID, workManager: androidx.work.WorkManager) {
        scope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo != null) {
                    val progress = workInfo.progress.getInt("progress", 0) / 100f
                    val speed = workInfo.progress.getLong("speed", 0L)
                    val downloaded = workInfo.progress.getLong("bytes_downloaded", 0L)
                    val total = workInfo.progress.getLong("bytes_total", 0L)
                    
                    _downloadStatus.update { it + (modelName to DownloadStatus(progress, speed, downloaded, total)) }
                    
                    if (workInfo.state.isFinished) {
                        if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                            refreshModels()
                        }
                        // Remove status after a delay or on completion
                        delay(2000)
                        _downloadStatus.update { it - modelName }
                    }
                }
            }
        }
    }

    fun pauseDownload(modelName: String) {
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("download_$modelName")
        _downloadPauseState.update { it + (modelName to true) }
    }

    fun resumeDownload(modelName: String) {
        _downloadPauseState.update { it + (modelName to false) }
        // For WorkManager, resume is basically restart or we handle it in DownloadWorker
        // For now, let's just trigger startDownload again which has ExistingWorkPolicy.KEEP
        // but if we want actual resume, DownloadWorker needs to handle it.
        refreshModels() 
    }

    fun cancelDownload(modelName: String) {
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("download_$modelName")
        _downloadStatus.update { it - modelName }
        _downloadPauseState.update { it - modelName }
    }
}
