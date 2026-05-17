package com.chhanda.ai.domain.usecase

import android.net.Uri
import android.util.Log
import com.chhanda.ai.domain.model.MultimodalIngestor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TurnContextIngestor @Inject constructor(
    private val ingestor: MultimodalIngestor,
    private val persistentIngestor: IngestDocumentUseCase,
    private val scrapeUrlUseCase: ScrapeUrlUseCase,
    private val uploadedFileDao: com.chhanda.ai.data.repository.UploadedFileDao
) {
    suspend fun processTurnContext(
        userText: String,
        attachments: List<Uri>
    ): String = buildString {
        // 1. Process explicit attachments
        if (attachments.isNotEmpty()) {
            for (uri in attachments) {
                try {
                    val uriString = uri.toString()
                    val fileName = uri.lastPathSegment ?: "file"
                    val (rawText, type) = when {
                        uriString.contains("image") || uriString.endsWith(".jpg") || uriString.endsWith(".png") || uriString.endsWith(".jpeg") -> 
                            ingestor.ingestImage(uri) to DocType.IMAGE
                        uriString.endsWith(".pdf") -> 
                            ingestor.ingestPdf(uri).joinToString("\n") to DocType.PDF
                        uriString.contains("audio") || uriString.endsWith(".wav") || uriString.endsWith(".mp3") || uriString.endsWith(".m4a") -> 
                            ingestor.ingestAudio(uri) to DocType.AUDIO
                        uriString.endsWith(".docx") || uriString.endsWith(".doc") -> 
                            ingestor.ingestWord(uri) to DocType.WORD
                        uriString.endsWith(".xlsx") || uriString.endsWith(".xls") -> 
                            ingestor.ingestExcel(uri) to DocType.EXCEL
                        uriString.endsWith(".json") -> 
                            ingestor.ingestJson(uri) to DocType.JSON
                        uriString.endsWith(".csv") -> 
                            ingestor.ingestCsv(uri) to DocType.CSV
                        else -> ingestor.ingestTxt(uri) to DocType.TXT
                    }
                    
                    try {
                        persistentIngestor.ingestScrapedText(rawText, uriString, fileName, type.name)
                        val existing = uploadedFileDao.findByNameAndSize(fileName, rawText.length.toLong())
                        if (existing == null) {
                            uploadedFileDao.insertFile(com.chhanda.ai.data.repository.UploadedFileEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                name = fileName,
                                format = type.name,
                                size = rawText.length.toLong(),
                                path = uriString,
                                timestamp = System.currentTimeMillis()
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e("TurnContextIngestor", "Failed to persist attachment: ${e.message}")
                    }
                    
                    append("--- ATTACHMENT: $fileName (${type.name}) ---\n$rawText\n\n")
                } catch (e: Exception) {
                    append("Error processing ${uri.lastPathSegment}: ${e.localizedMessage}\n\n")
                }
            }
        }

        // 2. Proactive URL Scraping
        val urlRegex = """(https?://[^\s$.?#].[^\s]*)""".toRegex()
        val detectedUrls = urlRegex.findAll(userText).map { it.value }.distinct().toList()
        
        if (detectedUrls.isNotEmpty()) {
            for (url in detectedUrls) {
                try {
                    val scrapedText = scrapeUrlUseCase(url)
                    if (scrapedText.length > 200) {
                        append("--- SCRAPED WEB CONTENT: $url ---\n$scrapedText\n\n")
                        try {
                            persistentIngestor.ingestScrapedText(scrapedText, url, "AUTO_SCRAPE")
                        } catch (e: Exception) { /* Silent fail */ }
                    }
                } catch (e: Exception) {
                    Log.w("TurnContextIngestor", "Scrape failed for $url: ${e.message}")
                }
            }
        }
    }
}
