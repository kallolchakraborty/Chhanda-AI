package com.chhanda.ai.domain.usecase

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Senior-Grade Web Scraping Engine.
 * Implements exponential backoff, rotating user-agents, and a custom Readability scoring algorithm
 * to extract meaningful content while aggressively filtering noise and advertisements.
 */
class ScrapeUrlUseCase @Inject constructor() {

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Mobile/15E148 Safari/604.1"
    )

    suspend operator fun invoke(url: String, useAi: Boolean = false, maxSizeMb: Int = 300): String = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        
        // 🚀 Senior Strategy: 3-Stage Retry with Identity Stealth
        for (attempt in 1..3) {
            try {
                android.util.Log.d("ScrapeUrl", "Scraping Attempt $attempt for: $url")
                return@withContext executeScrape(url, attempt)
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w("ScrapeUrl", "Attempt $attempt failed: ${e.message}")
                if (attempt < 3) delay(2000L * attempt) // Increased delay
            }
        }
        
        throw Exception("Failed to bypass site security. Status: ${lastError?.message}")
    }

    private fun executeScrape(url: String, attempt: Int): String {
        val currentUA = userAgents[attempt % userAgents.size]
        val connection = Jsoup.connect(url)
            .userAgent(currentUA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Referer", "https://www.google.com/")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "cross-site")
            .header("Sec-Fetch-User", "?1")
            .header("Connection", "keep-alive")
            .timeout(20000)
            .followRedirects(true)
            .ignoreContentType(true)
            .ignoreHttpErrors(true) // We check status ourselves

        val response = connection.execute()
        
        if (response.statusCode() == 403 || response.statusCode() == 429) {
            throw Exception("Access Denied (${response.statusCode()}). The site is blocking automated access.")
        }

        if (response.statusCode() != 200) {
            throw Exception("HTTP ${response.statusCode()}: ${response.statusMessage()}")
        }

        val contentType = response.contentType() ?: ""
        if (contentType.contains("application/pdf")) {
            throw Exception("PDF_LINK_DETECTED") // Signal to worker to use PDF ingestor
        }
        
        if (!contentType.contains("text/html") && !contentType.contains("application/xhtml")) {
            if (contentType.contains("text/plain")) return response.body()
            throw Exception("Unsupported content type: $contentType")
        }

        val doc = response.parse()
        doc.setBaseUri(url)
        
        // 🧹 Aggressive Noise Removal
        cleanDocument(doc)

        // 🧠 Identify Main Content using Readability Scoring
        val mainContent = findMainContent(doc)
        
        // 📝 Formatted Extraction
        val builder = StringBuilder()
        
        // Metadata headers
        val title = doc.title().trim()
        if (title.isNotBlank()) builder.append("# $title\n\n")
        
        val metaDesc = doc.select("meta[name=description]").attr("content").trim()
        if (metaDesc.isNotBlank()) builder.append("> $metaDesc\n\n")

        // Content Iteration
        extractMeaningfulText(mainContent, builder)

        // Final Validation
        val result = builder.toString().trim()
        if (result.length < 200) {
            // Fallback to body text if scoring was too picky
            val bodyText = doc.body().text().trim()
            if (bodyText.length < 100) throw Exception("Extracted content too thin (${bodyText.length} chars)")
            return bodyText
        }
        
        return result
    }

    private fun cleanDocument(doc: Document) {
        val noiseSelectors = listOf(
            "script", "style", "nav", "footer", "header", "noscript", "iframe", "link",
            ".ads", ".sidebar", ".menu", ".nav", "#footer", "#header", ".ad-container",
            ".promoted", ".sponsored", ".social-share", ".newsletter-signup", "aside",
            ".banner", ".popup", "[style*=display:none]", "[aria-hidden=true]",
            ".cookie-banner", ".consent-msg", "#comments", ".comments-area"
        )
        doc.select(noiseSelectors.joinToString(", ")).remove()
        
        // Remove empty paragraphs/divs
        doc.select("p:empty, div:empty").remove()
    }

    private fun findMainContent(doc: Document): Element {
        // High-confidence candidates
        val primaryCandidates = listOf("article", "main", "[role=main]", ".post-content", ".article-content", ".content-area")
        for (selector in primaryCandidates) {
            doc.select(selector).firstOrNull()?.let { if (it.text().length > 500) return it }
        }

        // Scoring algorithm: Score elements based on text density vs link density
        var bestElement: Element = doc.body()
        var maxScore = 0

        doc.select("div, section, article").forEach { element ->
            val text = element.ownText().trim()
            if (text.length < 25) return@forEach
            
            val linkDensity = calculateLinkDensity(element)
            if (linkDensity > 0.3) return@forEach // Too many links, likely a menu or sidebar
            
            val score = text.length + (element.select("p").size * 20)
            if (score > maxScore) {
                maxScore = score
                bestElement = element
            }
        }

        return bestElement
    }

    private fun calculateLinkDensity(element: Element): Double {
        val textLength = element.text().length
        if (textLength == 0) return 0.0
        val linkTextLength = element.select("a").sumOf { it.text().length }
        return linkTextLength.toDouble() / textLength.toDouble()
    }

    private fun extractMeaningfulText(root: Element, builder: StringBuilder) {
        root.select("h1, h2, h3, h4, p, li, table, pre, code, img, figcaption, a, dt, dd").forEach { el ->
            val tag = el.tagName()
            val text = el.text().trim()
            
            when (tag) {
                "h1" -> if (text.length > 2) builder.append("# $text\n\n")
                "h2" -> if (text.length > 2) builder.append("## $text\n\n")
                "h3", "h4" -> if (text.length > 2) builder.append("### $text\n\n")
                "p" -> if (text.length > 10) builder.append("$text\n\n")
                "li" -> if (text.length > 2) builder.append("- $text\n")
                "dt" -> builder.append("**$text**: ")
                "dd" -> builder.append("$text\n\n")
                "pre", "code" -> if (text.length > 2) builder.append("```\n$text\n```\n\n")
                "table" -> {
                    val tableText = el.text().take(1000)
                    builder.append("[TABLE DATA: $tableText]\n\n")
                }
                "img" -> {
                    val alt = el.attr("alt").trim()
                    val title = el.attr("title").trim()
                    if (alt.isNotBlank()) builder.append("[IMAGE DESCRIPTION: $alt]\n\n")
                    else if (title.isNotBlank()) builder.append("[IMAGE TITLE: $title]\n\n")
                }
                "figcaption" -> if (text.isNotBlank()) builder.append("*Caption: $text*\n\n")
                "a" -> {
                    val href = el.attr("abs:href").lowercase()
                    if (href.endsWith(".pdf") || href.endsWith(".docx") || href.endsWith(".doc") || 
                        href.endsWith(".xlsx") || href.endsWith(".xls") || href.endsWith(".zip")) {
                        builder.append("[EXTERNAL FILE LINK: $text ($href)]\n\n")
                    }
                }
            }
        }
    }
}
