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
                    
                    val fileSize = try {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
                    } catch (e: Exception) { 0L }

                    val existing = uploadedFileDao.findByNameAndSize(fileName, fileSize)
                    if (existing != null) {
                        android.util.Log.i("IngestionManager", "Duplicate file skipped: $fileName")
                        return@forEachIndexed
                    }

                    val baseProgress = index.toFloat() / uris.size
                    ingestDocumentUseCase(uri, docType) { fileProgress ->
                        _ingestionProgress.value = baseProgress + (fileProgress / uris.size)
                    }
                    
                    uploadedFileDao.insertFile(UploadedFileEntity(
                        id = UUID.randomUUID().toString(),
                        name = fileName,
                        format = docType.name,
                        size = fileSize,
                        path = uri.toString(),
                        timestamp = System.currentTimeMillis()
                    ))
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

                uploadedFileDao.insertFile(UploadedFileEntity(
                    id = UUID.randomUUID().toString(),
                    name = finalLabel,
                    format = "WEB_URL",
                    size = scrapedText.length.toLong(),
                    path = url,
                    timestamp = System.currentTimeMillis()
                ))

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
        val fileName = getFileName(uri)?.lowercase() ?: ""
        return when {
            mimeType?.startsWith("image/") == true || fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".webp") -> DocType.IMAGE
            mimeType == "application/pdf" || fileName.endsWith(".pdf") -> DocType.PDF
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || 
            mimeType == "application/msword" || fileName.endsWith(".docx") || fileName.endsWith(".doc") -> DocType.WORD
            mimeType == "application/vnd.ms-excel" || 
            mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" || 
            fileName.endsWith(".xlsx") || fileName.endsWith(".xls") -> DocType.EXCEL
            mimeType == "application/json" || fileName.endsWith(".json") -> DocType.JSON
            mimeType == "text/csv" || mimeType == "text/comma-separated-values" || fileName.endsWith(".csv") -> DocType.CSV
            mimeType == "text/tab-separated-values" || fileName.endsWith(".tsv") || fileName.endsWith(".tab") -> DocType.TSV
            mimeType == "text/xml" || mimeType == "application/xml" || fileName.endsWith(".xml") -> DocType.XML
            mimeType == "text/html" || fileName.endsWith(".html") || fileName.endsWith(".htm") -> DocType.HTML
            mimeType == "text/markdown" || fileName.endsWith(".md") -> DocType.MD
            mimeType?.startsWith("audio/") == true || fileName.endsWith(".mp3") || fileName.endsWith(".wav") || fileName.endsWith(".m4a") -> DocType.AUDIO
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
