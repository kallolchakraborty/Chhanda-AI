package com.chhanda.ai.domain.usecase

import org.jsoup.Jsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Use case for scraping content from a URL for RAG ingestion.
 */
class ScrapeUrlUseCase @Inject constructor() {

    suspend operator fun invoke(url: String, useAi: Boolean = false, maxSizeMb: Int = 300): String = withContext(Dispatchers.IO) {
        val tempFile = java.io.File.createTempFile("scrape_", ".tmp")
        try {
            val connection = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                .timeout(30000)
                .followRedirects(true)
                .ignoreContentType(true)

            // Check content length first
            val response = connection.execute()
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
            if (maxSizeMb > 0 && contentLength > maxSizeMb * 1024 * 1024) {
                throw Exception("Resource too large: ${(contentLength / (1024.0 * 1024.0)).format(1)}MB. Limit is ${maxSizeMb}MB.")
            }

            // Stream content to temp file to avoid OOM
            tempFile.outputStream().use { output ->
                output.write(response.bodyAsBytes())
            }

            val doc = Jsoup.parse(tempFile, "UTF-8", url)

            // Remove scripts, styles, and other non-content elements
            doc.select("script, style, nav, footer, header, noscript, iframe, link").remove()

            // Extract main content
            val mainContent = doc.body().text()
            
            if (mainContent.isBlank()) {
                throw Exception("No readable text content found on this page.")
            }
            
            mainContent
        } catch (e: Exception) {
            throw Exception("Scrape error: ${e.message}")
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
