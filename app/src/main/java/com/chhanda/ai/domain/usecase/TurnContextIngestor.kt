package com.chhanda.ai.domain.usecase

import android.net.Uri
import android.util.Log
import com.chhanda.ai.domain.model.MultimodalIngestor
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class TurnContextIngestor @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
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
                    val (fileName, fileLength) = com.chhanda.ai.util.FileUtils.getFileDetails(context, uri)
                    val lowerName = fileName.lowercase()
                    val rawMime = com.chhanda.ai.util.FileUtils.probeMimeType(context, uri)
                    val mimeType = if (rawMime == "application/zip") {
                        com.chhanda.ai.util.FileUtils.isZipWordOrExcel(context, uri) ?: rawMime
                    } else rawMime

                    val isImage = mimeType.startsWith("image/") || lowerName.contains("image") || lowerName.endsWith(".jpg") || lowerName.endsWith(".png") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp")
                    val isPdf = mimeType == "application/pdf" || lowerName.endsWith(".pdf")
                    val isAudio = mimeType.startsWith("audio/") || lowerName.contains("audio") || lowerName.endsWith(".wav") || lowerName.endsWith(".mp3") || lowerName.endsWith(".m4a")
                    val isWord = mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || 
                                 mimeType == "application/msword" || 
                                 lowerName.endsWith(".docx") || lowerName.endsWith(".doc")
                    val isExcel = mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" || 
                                  mimeType == "application/vnd.ms-excel" || 
                                  lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")
                    val isJson = mimeType == "application/json" || lowerName.endsWith(".json")
                    val isCsv = mimeType == "text/csv" || mimeType == "application/csv" || lowerName.endsWith(".csv")
                    val isTsv = mimeType == "text/tab-separated-values" || lowerName.endsWith(".tsv") || lowerName.endsWith(".tab")
                    val isXml = mimeType == "text/xml" || mimeType == "application/xml" || lowerName.endsWith(".xml")
                    val isHtml = mimeType == "text/html" || lowerName.endsWith(".html") || lowerName.endsWith(".htm")
                    val isMd = mimeType == "text/markdown" || mimeType == "text/x-markdown" || lowerName.endsWith(".md")

                    val (rawText, type) = when {
                        isImage -> 
                            ingestor.ingestImage(uri) to DocType.IMAGE
                        isPdf -> 
                            ingestor.ingestPdf(uri).joinToString("\n") to DocType.PDF
                        isAudio -> 
                            ingestor.ingestAudio(uri) to DocType.AUDIO
                        isWord -> 
                            ingestor.ingestWord(uri) to DocType.WORD
                        isExcel -> 
                            ingestor.ingestExcel(uri) to DocType.EXCEL
                        isJson -> 
                            ingestor.ingestJson(uri) to DocType.JSON
                        isCsv -> 
                            ingestor.ingestCsv(uri) to DocType.CSV
                        isTsv -> 
                            ingestor.ingestTsv(uri) to DocType.TSV
                        isXml -> 
                            ingestor.ingestXml(uri) to DocType.XML
                        isHtml -> 
                            ingestor.ingestHtml(uri) to DocType.HTML
                        isMd -> 
                            ingestor.ingestMd(uri) to DocType.MD
                        else -> ingestor.ingestTxt(uri) to DocType.TXT
                    }
                    
                    try {
                        persistentIngestor.ingestScrapedText(rawText, uriString, fileName, type.name)
                        val sizeToUse = if (fileLength > 0) fileLength else rawText.length.toLong()
                        val existing = uploadedFileDao.findByNameAndSize(fileName, sizeToUse)
                        if (existing == null) {
                            uploadedFileDao.insertFile(com.chhanda.ai.data.repository.UploadedFileEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                name = fileName,
                                format = type.name,
                                size = sizeToUse,
                                path = uriString,
                                timestamp = System.currentTimeMillis()
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e("TurnContextIngestor", "Failed to persist attachment: ${e.message}")
                    }
                    
                    val promptText = if (rawText.length > 2000) {
                        rawText.take(2000) + "\n\n...[Content Truncated due to size limits. Full content is stored in the local vector knowledge base.]"
                    } else rawText
                    append("--- ATTACHMENT: $fileName (${type.name}) ---\n$promptText\n\n")
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
