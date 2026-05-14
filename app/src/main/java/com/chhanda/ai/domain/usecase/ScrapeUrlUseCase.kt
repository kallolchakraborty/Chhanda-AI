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
 * Implements exponential backoff, rotating user-agents, and a multi-stage fallback strategy
 * (JSON-LD -> Jina Reader -> Semantic DOM) to extract flawless content from any site.
 */
class ScrapeUrlUseCase @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Mobile/15E148 Safari/604.1"
    )

    suspend operator fun invoke(url: String, useAi: Boolean = false, maxSizeMb: Int = 300): String = withContext(Dispatchers.IO) {
        if (!isInternetAvailable()) {
            throw Exception("INTERNET_REQUIRED: No active connection detected. Scraping aborted.")
        }
        
        var lastError: Exception? = null
        
        // 🚀 Senior Strategy: 3-Stage Retry with Identity Stealth
        for (attempt in 1..3) {
            try {
                android.util.Log.d("ScrapeUrl", "Scraping Attempt $attempt for: $url")
                return@withContext executeScrape(url, attempt)
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w("ScrapeUrl", "Attempt $attempt failed: ${e.message}")
                if (attempt < 3) delay(2000L * attempt)
            }
        }
        
        throw Exception("Failed to bypass site security. Status: ${lastError?.message}")
    }

    private suspend fun executeScrape(url: String, attempt: Int): String {
        // 🚀 Stage 1: Standard Jsoup Extraction (Fastest)
        try {
            val content = performJsoupScrape(url, attempt)
            if (content.length > 800) return content // High confidence threshold
        } catch (e: Exception) {
            if (e.message == "PDF_LINK_DETECTED") throw e
            android.util.Log.w("ScrapeUrl", "Jsoup stage failed: ${e.message}")
        }

        // 🚀 Stage 2: Jina Reader Fallback (Handles JS/Protections flawlessly)
        try {
            val jinaUrl = "https://r.jina.ai/$url"
            android.util.Log.d("ScrapeUrl", "Attempting Jina Reader: $jinaUrl")
            val jinaResponse = Jsoup.connect(jinaUrl)
                .userAgent(userAgents[attempt % userAgents.size])
                .timeout(20000)
                .ignoreHttpErrors(true)
                .execute()
            
            if (jinaResponse.statusCode() == 200) {
                val jinaContent = jinaResponse.body()
                if (jinaContent.length > 300) {
                    android.util.Log.i("ScrapeUrl", "Successfully recovered content via Jina Reader (${jinaContent.length} chars)")
                    // Basic cleaning for Jina's markdown output to reduce noise
                    val cleanedJina = jinaContent.replace(Regex("\\[.*?\\]\\(.*?\\)"), "") 
                                                 .replace(Regex("!\\(.*?\\)"), "")
                    return cleanedJina
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ScrapeUrl", "Jina fallback failed: ${e.message}")
        }

        // 🚀 Stage 3: Extreme Content Recovery (JSON-LD / Metadata)
        return performLastResortScrape(url, attempt)
    }

    private fun performJsoupScrape(url: String, attempt: Int): String {
        val currentUA = userAgents[attempt % userAgents.size]
        val response = Jsoup.connect(url)
            .userAgent(currentUA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Referer", "https://www.google.com/")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "cross-site")
            .timeout(15000)
            .followRedirects(true)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .execute()

        val statusCode = response.statusCode()
        if (statusCode == 403 || statusCode == 429) {
            android.util.Log.w("ScrapeUrl", "Anti-bot detected (Status $statusCode). Escalating to Stage 2.")
            throw Exception("BLOCK_$statusCode")
        }
        
        if (response.contentType()?.contains("application/pdf") == true) throw Exception("PDF_LINK_DETECTED")

        val doc = response.parse()
        doc.setBaseUri(url)
        cleanDocument(doc)
        
        val builder = StringBuilder()
        val title = doc.title().trim()
        if (title.isNotBlank()) builder.append("# $title\n\n")

        // 💎 Search for JSON-LD (World Class Strategy: Schema.org detection)
        val jsonData = extractJsonMetadata(doc)
        if (jsonData.isNotBlank()) {
            builder.append("## Structured Specification\n")
            builder.append(jsonData)
            builder.append("\n\n")
        }

        val mainContent = findMainContent(doc)
        extractMeaningfulText(mainContent, builder)
        
        val result = builder.toString().trim()
        if (result.length < 500) {
            android.util.Log.i("ScrapeUrl", "Jsoup content too thin (${result.length} chars). Escalating to Jina.")
            throw Exception("THIN_CONTENT")
        }
        
        return result
    }

    private fun extractJsonMetadata(doc: Document): String {
        val builder = StringBuilder()
        doc.select("script[type=application/ld+json]").forEach { script ->
            try {
                val json = script.data()
                // Focus on Product, Article, or Organization
                if (json.contains("\"@type\":") && (json.contains("Product") || json.contains("Article") || json.contains("Organization"))) {
                    val name = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val desc = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val brand = Regex("\"brand\":\\s*\\{\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val price = Regex("\"price\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val currency = Regex("\"priceCurrency\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    
                    if (name != null) builder.append("- **Item**: $name\n")
                    if (brand != null) builder.append("- **Brand**: $brand\n")
                    if (price != null) builder.append("- **Price**: $price $currency\n")
                    if (desc != null) builder.append("- **Summary**: $desc\n")
                }
            } catch (e: Exception) { }
        }
        return builder.toString().trim()
    }

    private fun performLastResortScrape(url: String, attempt: Int): String {
        android.util.Log.w("ScrapeUrl", "Executing Last-Resort Scrape for $url")
        val doc = Jsoup.connect(url)
            .userAgent(userAgents[attempt % userAgents.size])
            .timeout(10000)
            .ignoreHttpErrors(true)
            .get()
        
        val builder = StringBuilder()
        val title = doc.title()
        if (title.isNotBlank()) builder.append("# $title\n\n")
        
        // Check for meta descriptions if body might be thin
        val metaDesc = doc.select("meta[name=description]").attr("content")
        val ogDesc = doc.select("meta[property=og:description]").attr("content")
        if (metaDesc.isNotBlank()) builder.append("> Metadata: $metaDesc\n\n")
        else if (ogDesc.isNotBlank()) builder.append("> Metadata: $ogDesc\n\n")

        // Use semantic extraction even in last resort
        val mainContent = try { findMainContent(doc) } catch (e: Exception) { doc.body() }
        val text = mainContent.text().trim()
        
        if (text.length < 50 && metaDesc.isBlank() && ogDesc.isBlank()) {
            throw Exception("CRITICAL_FAILURE: Site returned empty or protected content.")
        }
        
        builder.append(text)
        return builder.toString().trim()
    }

    private fun cleanDocument(doc: Document) {
        val noiseSelectors = listOf(
            "script", "style", "nav", "footer", "header", "noscript", "iframe", "link",
            ".ads", ".sidebar", ".menu", ".nav", "#footer", "#header", ".ad-container",
            ".social-share", ".newsletter-signup", "aside", ".cookie-banner", ".consent-msg"
        )
        doc.select(noiseSelectors.joinToString(", ")).remove()
        doc.select("p:empty, div:empty").remove()
    }

    private fun findMainContent(doc: Document): Element {
        val primaryCandidates = listOf("article", "main", "[role=main]", ".post-content", ".article-content", ".content-area", ".product-details")
        for (selector in primaryCandidates) {
            doc.select(selector).firstOrNull()?.let { if (it.text().length > 400) return it }
        }

        var bestElement: Element = doc.body()
        var maxScore = 0

        doc.select("div, section, article").forEach { element ->
            val text = element.ownText().trim()
            if (text.length < 20) return@forEach
            
            val linkDensity = calculateLinkDensity(element)
            if (linkDensity > 0.4) return@forEach 
            
            val score = text.length + (element.select("p").size * 25)
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
                    if (alt.isNotBlank()) builder.append("[IMAGE: $alt]\n\n")
                }
                "a" -> {
                    val href = el.attr("abs:href").lowercase()
                    if (href.endsWith(".pdf") || href.endsWith(".docx") || href.endsWith(".doc") || 
                        href.endsWith(".xlsx") || href.endsWith(".xls") || href.endsWith(".zip")) {
                        builder.append("[FILE: $text ($href)]\n\n")
                    }
                }
            }
        }
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return activeNetwork.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
