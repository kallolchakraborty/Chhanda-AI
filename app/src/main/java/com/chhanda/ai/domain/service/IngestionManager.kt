package com.chhanda.ai.domain.service

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.chhanda.ai.service.IngestionWorker
import com.chhanda.ai.data.repository.*
import com.chhanda.ai.domain.usecase.*
import com.chhanda.ai.util.AppLogManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class IngestionTask(val uris: List<Uri>? = null, val url: String? = null, val label: String? = null)

@Singleton
class IngestionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadedFileDao: UploadedFileDao,
    private val vectorChunkDao: VectorChunkDao,
    private val ingestDocumentUseCaseLazy: Lazy<IngestDocumentUseCase>,
    private val scrapeUrlUseCaseLazy: Lazy<ScrapeUrlUseCase>,
    private val appLogManager: AppLogManager,
    private val hardwareMonitor: com.chhanda.ai.data.repository.HardwareMonitor,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _isIngesting = MutableStateFlow(false)
    val isIngesting: StateFlow<Boolean> = _isIngesting.asStateFlow()
    
    private val _ingestionProgress = MutableStateFlow(0f)
    val ingestionProgress: StateFlow<Float> = _ingestionProgress.asStateFlow()
    
    private val _ingestionMessage = MutableStateFlow("")
    val ingestionMessage: StateFlow<String> = _ingestionMessage.asStateFlow()
    
    private val _ingestionError = MutableStateFlow<String?>(null)
    val ingestionError: StateFlow<String?> = _ingestionError.asStateFlow()

    private val _pendingBackgroundPrompt = MutableStateFlow<IngestionTask?>(null)
    val pendingBackgroundPrompt: StateFlow<IngestionTask?> = _pendingBackgroundPrompt.asStateFlow()

    val uploadedFiles = uploadedFileDao.getAllFiles()
        .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    private val ingestDocumentUseCase get() = ingestDocumentUseCaseLazy.get()
    private val scrapeUrlUseCase get() = scrapeUrlUseCaseLazy.get()

    fun ingestDocuments(uris: List<Uri>) {
        scope.launch {
            _isIngesting.value = true
            _ingestionProgress.value = 0f
            _ingestionError.value = null
            _ingestionMessage.value = "Preparing ingestion..."
            hardwareMonitor.setAppVisibility(false) // Throttling metrics during heavy load
            
            try {
                uris.forEachIndexed { index, uri ->
                    val fileName = getFileName(uri) ?: "Document ${index + 1}"
                    _ingestionMessage.value = "Indexing $fileName..."
                    _ingestionProgress.value = (index.toFloat() / uris.size)
                    val docType = determineDocType(uri)
                    ingestDocumentUseCase(uri, docType)
                }
                _ingestionMessage.value = "Batch complete!"
                _ingestionProgress.value = 1.0f
                appLogManager.addLog("STORAGE", "Successfully indexed ${uris.size} documents", "SUCCESS")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ingestionError.value = "Ingestion failed: ${e.message}"
                appLogManager.addLog("STORAGE", "Bulk indexing failed: ${e.message}", "ERROR")
            } finally {
                delay(2000)
                _isIngesting.value = false
                hardwareMonitor.setAppVisibility(true)
            }
        }
    }

    fun scrapeUrl(url: String, inBackground: Boolean = false, label: String? = null) {
        val finalLabel = label ?: url.removePrefix("https://").removePrefix("http://").take(30)
        
        if (inBackground) {
            val data = workDataOf(
                IngestionWorker.KEY_URL to url,
                IngestionWorker.KEY_NAME to finalLabel
            )
            val request = OneTimeWorkRequestBuilder<IngestionWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            appLogManager.addLog("SYSTEM", "URL scraping moved to background: $finalLabel", "SUCCESS")
            return
        }

        scope.launch {
            _isIngesting.value = true
            _ingestionProgress.value = 0.2f
            _ingestionError.value = null
            _ingestionMessage.value = "Extracting knowledge from $finalLabel..."
            
            val timerJob = launch {
                delay(30000)
                if (_isIngesting.value && _ingestionMessage.value.contains("Extracting")) {
                    _pendingBackgroundPrompt.value = IngestionTask(url = url, label = finalLabel)
                }
            }
            
            try {
                val useAi = url.contains("kaggle.com", ignoreCase = true)
                val scrapedText = scrapeUrlUseCase(url, useAi = useAi, maxSizeMb = 300)
                
                _ingestionMessage.value = "Indexing content..."
                ingestDocumentUseCase.ingestScrapedText(scrapedText, url, finalLabel) { progress ->
                    _ingestionProgress.value = 0.2f + (progress * 0.8f)
                }

                _ingestionMessage.value = "Web indexing complete!"
                _ingestionProgress.value = 1.0f
                appLogManager.addLog("STORAGE", "Scraped and indexed: $finalLabel", "SUCCESS")
                showCompletionNotification(finalLabel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ingestionError.value = "Web scrape failed: ${e.message}"
                appLogManager.addLog("STORAGE", "Scraper Error: ${e.message}", "ERROR")
            } finally {
                timerJob.cancel()
                delay(2000)
                _isIngesting.value = false
            }
        }
    }

    fun dismissPendingPrompt() {
        _pendingBackgroundPrompt.value = null
    }

    fun deleteFile(file: UploadedFileEntity) {
        scope.launch {
            uploadedFileDao.deleteFile(file)
            vectorChunkDao.deleteBySource(file.name)
            appLogManager.addLog("STORAGE", "Removed: ${file.name}", "INFO")
            
            try {
                if (file.path.startsWith("content://")) {
                    context.contentResolver.delete(Uri.parse(file.path), null, null)
                } else if (file.path.startsWith("file://")) {
                    val physicalFile = java.io.File(file.path.removePrefix("file://"))
                    if (physicalFile.exists()) physicalFile.delete()
                }
            } catch (e: Exception) {}
        }
    }

    fun deleteFiles(ids: List<String>) {
        scope.launch {
            val files = uploadedFileDao.getFilesByIds(ids)
            files.forEach { file ->
                deleteFile(file)
            }
            appLogManager.addLog("STORAGE", "Batch removal of ${ids.size} items complete", "SUCCESS")
        }
    }

    fun deleteAllFiles() {
        scope.launch {
            val files = uploadedFileDao.getAllFiles().first()
            files.forEach { file ->
                try {
                    if (file.path.startsWith("content://")) {
                        context.contentResolver.delete(Uri.parse(file.path), null, null)
                    } else if (file.path.startsWith("file://")) {
                        val physicalFile = java.io.File(file.path.removePrefix("file://"))
                        if (physicalFile.exists()) physicalFile.delete()
                    }
                } catch (e: Exception) {}
            }
            uploadedFileDao.deleteAll()
            vectorChunkDao.clearAll()
            appLogManager.addLog("STORAGE", "Cleared Knowledge Base and purged disk assets", "WARN")
        }
    }

    fun performCleanup() {
        scope.launch {
            val enabled = settingsRepository.autoDeleteEnabledFlow.first()
            if (!enabled) return@launch

            val days = settingsRepository.autoDeleteDaysFlow.first()
            val threshold = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000L

            val filesToDelete = uploadedFileDao.getFilesOlderThan(threshold)
            filesToDelete.forEach { deleteFile(it) }
            
            if (filesToDelete.isNotEmpty()) {
                appLogManager.addLog("STORAGE", "Auto-cleanup removed ${filesToDelete.size} items", "SUCCESS")
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) it.getString(index) else null
                } else null
            }
        } catch (e: Exception) { null }
    }
    private fun determineDocType(uri: Uri): DocType {
        val mimeType = context.contentResolver.getType(uri)
        return when {
            mimeType?.startsWith("image/") == true -> DocType.IMAGE
            mimeType == "application/pdf" -> DocType.PDF
            mimeType == "text/plain" -> DocType.TXT
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || 
            mimeType == "application/msword" -> DocType.WORD
            mimeType == "application/vnd.ms-excel" || 
            mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> DocType.EXCEL
            mimeType == "application/json" -> DocType.JSON
            mimeType?.startsWith("audio/") == true -> DocType.AUDIO
            else -> DocType.TXT
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
