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
        try {
            android.util.Log.d("ScrapeUrl", "Starting scrape for: $url")
            
            // Senior strategy: Use a very common browser User-Agent
            val userAgents = listOf(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            val connection = Jsoup.connect(url)
                .userAgent(userAgents.random())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(20000)
                .followRedirects(true)
                .ignoreContentType(true)
                .ignoreHttpErrors(true) // We want to handle the error ourselves

            val response = connection.execute()
            
            if (response.statusCode() != 200) {
                throw Exception("HTTP ${response.statusCode()}: ${response.statusMessage()}")
            }

            // Check content type
            val contentType = response.contentType() ?: ""
            if (!contentType.contains("text/html") && !contentType.contains("application/xhtml")) {
                // If it's plain text, just return it
                if (contentType.contains("text/plain")) {
                    return@withContext response.body().take(maxSizeMb * 1024 * 1024)
                }
                throw Exception("Unsupported content type: $contentType")
            }

            val doc = response.parse()

            // 1. Remove obvious noise first
            doc.select("script, style, nav, footer, header, noscript, iframe, link, .ads, .sidebar, .menu, .nav, #footer, #header").remove()
            doc.select("[style*=display:none]").remove()

            // 2. Identify the main content container
            val candidates = listOf(
                "article", "main", "[role=main]", ".post-content", ".article-content", 
                ".content", "#content", ".entry-content", ".main-content", "#main",
                ".wiki-content", ".mw-parser-output" // Wikipedia
            )
            
            var mainElement: org.jsoup.nodes.Element? = null
            for (selector in candidates) {
                mainElement = doc.select(selector).firstOrNull()
                if (mainElement != null && mainElement.text().length > 200) break
            }
            
            val contentToProcess = mainElement ?: doc.body()

            // 3. Extract text with hierarchy preservation
            val builder = StringBuilder()
            
            // Add page title as H1 equivalent
            val pageTitle = doc.title().trim()
            if (pageTitle.isNotBlank()) {
                builder.append("# $pageTitle\n\n")
            }

            // Iterate through meaningful tags
            contentToProcess.select("h1, h2, h3, h4, p, li, table, pre, code").forEach { element ->
                val tagName = element.tagName()
                val text = element.text().trim()
                
                if (text.length > 5) {
                    when (tagName) {
                        "h1" -> builder.append("# $text\n\n")
                        "h2" -> builder.append("## $text\n\n")
                        "h3", "h4" -> builder.append("### $text\n\n")
                        "li" -> builder.append("- $text\n")
                        "table" -> builder.append("[Table Content: ${element.text().take(200)}...]\n\n")
                        "pre", "code" -> builder.append("```\n$text\n```\n\n")
                        else -> builder.append(text).append("\n\n")
                    }
                }
            }

            val result = builder.toString().trim()
            
            if (result.length < 100) {
                // Fallback: If structured extraction was too aggressive, take raw body text
                android.util.Log.w("ScrapeUrl", "Structured extraction too short (${result.length}), falling back to body text.")
                val rawBodyText = doc.body().text().trim()
                if (rawBodyText.length < 50) throw Exception("No meaningful text found on the page.")
                rawBodyText
            } else {
                result
            }
        } catch (e: Exception) {
            android.util.Log.e("ScrapeUrl", "Scraping failed: ${e.message}")
            throw Exception("Scraping failed for $url: ${e.message}")
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
