package com.chhanda.ai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.chhanda.ai.domain.usecase.IngestDocumentUseCase
import com.chhanda.ai.data.repository.UploadedFileDao
import com.chhanda.ai.data.repository.UploadedFileEntity
import com.chhanda.ai.domain.usecase.DocType
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import android.net.Uri
import java.util.UUID

class IngestionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface IngestionEntryPoint {
        fun ingestDocumentUseCase(): IngestDocumentUseCase
        fun uploadedFileDao(): UploadedFileDao
        fun scrapeUrlUseCase(): com.chhanda.ai.domain.usecase.ScrapeUrlUseCase
    }

    companion object {
        private const val CHANNEL_ID = "rag_ingestion"
        private const val NOTIFICATION_ID = 7007
        const val KEY_URI = "uri"
        const val KEY_TYPE = "type"
        const val KEY_NAME = "name"
        const val KEY_URL = "url"
    }

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI)
        val typeString = inputData.getString(KEY_TYPE) ?: "TXT"
        val fileName = inputData.getString(KEY_NAME) ?: "Document"
        val url = inputData.getString(KEY_URL)

        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, IngestionEntryPoint::class.java)
        val useCase = entryPoint.ingestDocumentUseCase()
        val dao = entryPoint.uploadedFileDao()

        createNotificationChannel()
        setForeground(createForegroundInfo("Processing $fileName..."))

        return try {
            if (url != null) {
                val scraper = entryPoint.scrapeUrlUseCase()
                val isKaggle = url.contains("kaggle.com", ignoreCase = true)
                val urlLower = url.lowercase()
                
                when {
                    urlLower.endsWith(".pdf") || urlLower.endsWith(".docx") || urlLower.endsWith(".doc") || 
                    urlLower.endsWith(".txt") || urlLower.endsWith(".xlsx") || urlLower.endsWith(".xls") -> {
                        // Direct file download and ingestion
                        val ext = when {
                            urlLower.endsWith(".pdf") -> "pdf"
                            urlLower.endsWith(".docx") -> "docx"
                            urlLower.endsWith(".doc") -> "doc"
                            urlLower.endsWith(".xlsx") -> "xlsx"
                            urlLower.endsWith(".xls") -> "xls"
                            else -> "txt"
                        }
                        val tempFile = java.io.File(applicationContext.cacheDir, "downloaded_${System.currentTimeMillis()}.$ext")
                        java.net.URL(url).openStream().use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        val uri = Uri.fromFile(tempFile)
                        val docType = when (ext) {
                            "pdf" -> DocType.PDF
                            "docx", "doc" -> DocType.WORD
                            "xlsx", "xls" -> DocType.EXCEL
                            else -> DocType.TXT
                        }
                        useCase(uri, docType)
                        
                        dao.insertFile(UploadedFileEntity(
                            id = UUID.randomUUID().toString(),
                            name = fileName,
                            format = "${ext.uppercase()}_URL",
                            size = tempFile.length(),
                            path = url,
                            timestamp = System.currentTimeMillis()
                        ))
                        tempFile.delete()
                    }
                    else -> {
                        val scrapedText = scraper(url, useAi = isKaggle, maxSizeMb = 300)
                        useCase.ingestScrapedText(scrapedText, url, fileName)
                        dao.insertFile(UploadedFileEntity(
                            id = UUID.randomUUID().toString(),
                            name = fileName,
                            format = "WEB_URL",
                            size = scrapedText.length.toLong(),
                            path = url,
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                }
            } else {
                val uri = Uri.parse(uriString)
                val type = DocType.valueOf(typeString)
                
                val fileSize = applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
                val existing = dao.findByNameAndSize(fileName, fileSize)
                if (existing != null) {
                    android.util.Log.i("IngestionWorker", "Duplicate file skipped in worker: $fileName")
                    showCompletionNotification(fileName, true)
                    return Result.success()
                }

                useCase(uri, type)
                
                dao.insertFile(UploadedFileEntity(
                    id = UUID.randomUUID().toString(),
                    name = fileName,
                    format = type.name,
                    size = fileSize,
                    path = uriString ?: "",
                    timestamp = System.currentTimeMillis()
                ))
            }
            
            showCompletionNotification(fileName, true)
            Result.success()
        } catch (e: Exception) {
            if (e.message?.contains("PDF_LINK_DETECTED") == true) {
                android.util.Log.i("IngestionWorker", "Redirecting detected PDF link to binary ingestor: $url")
                // Re-run with PDF extension hint to force the PDF branch in next pass or just handle here
                val tempFile = java.io.File(applicationContext.cacheDir, "detected_${System.currentTimeMillis()}.pdf")
                try {
                    java.net.URL(url).openStream().use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val uri = Uri.fromFile(tempFile)
                    useCase(uri, DocType.PDF)
                    tempFile.delete()
                    showCompletionNotification(fileName, true)
                    return Result.success()
                } catch (pe: Exception) {
                    android.util.Log.e("IngestionWorker", "PDF redirection failed: ${pe.message}")
                }
            }
            android.util.Log.e("IngestionWorker", "Ingestion failed: ${e.message}")
            showCompletionNotification(fileName, false)
            Result.failure()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Knowledge Base", NotificationManager.IMPORTANCE_HIGH)
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Knowledge Ingestion")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showCompletionNotification(fileName: String, success: Boolean) {
        val title = if (success) "Ingestion Complete" else "Ingestion Failed"
        val text = if (success) "$fileName is now indexed in your memory." else "Failed to process $fileName. Please try again."
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)
    }
}
