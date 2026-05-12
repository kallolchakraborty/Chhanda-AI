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
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                .timeout(30000)
                .followRedirects(true)
                .ignoreContentType(true)

            val response = try {
                connection.execute()
            } catch (e: Exception) {
                // Fallback for some blocked user agents
                Jsoup.connect(url)
                    .userAgent("Googlebot/2.1 (+http://www.google.com/bot.html)")
                    .timeout(30000)
                    .execute()
            }

            val doc = response.parse()

            // Remove noise
            doc.select("script, style, nav, footer, header, noscript, iframe, link, .ads, .sidebar").remove()

            // If useAi is true (e.g. for Kaggle/Research sites), focus on data-rich areas
            val contentElement = if (useAi) {
                doc.select("article, main, .main-content, #main-content, .dataset-description, .notebook-content").firstOrNull() ?: doc.body()
            } else {
                doc.body()
            }

            // Extract structured text to preserve context
            val builder = StringBuilder()
            contentElement.select("h1, h2, h3, p, li, table").forEach { element ->
                val text = element.text().trim()
                if (text.length > 20) {
                    builder.append(text).append("\n\n")
                }
            }

            val mainContent = builder.toString().trim()
            
            if (mainContent.isBlank()) {
                // Fallback to raw body text if structured extraction failed
                val rawText = doc.body().text().trim()
                if (rawText.isBlank()) throw Exception("No readable text content found.")
                rawText
            } else {
                mainContent
            }
        } catch (e: Exception) {
            throw Exception("Scrape error: ${e.message}")
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
