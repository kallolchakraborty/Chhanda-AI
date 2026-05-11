package com.chhanda.ai.domain.usecase

import org.jsoup.Jsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Use case for scraping content from a URL for RAG ingestion.
 */
class ScrapeUrlUseCase @Inject constructor() {

    suspend operator fun invoke(url: String): String = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                .timeout(10000)
                .get()

            // Remove scripts, styles, and other non-content elements
            doc.select("script, style, nav, footer, header, noscript").remove()

            // Extract main content
            val mainContent = doc.body().text()
            
            if (mainContent.isBlank()) {
                throw Exception("No readable text content found on this page.")
            }
            
            mainContent
        } catch (e: Exception) {
            throw Exception("Failed to scrape URL: ${e.message}")
        }
    }
}
