package com.chhanda.ai.domain.usecase

import org.jsoup.Jsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.net.URLEncoder

/**
 * Use case for performing a Google search and returning results.
 * This is a scraping-based implementation as requested ("Google search will be the one").
 */
class GoogleSearchUseCase @Inject constructor() {

    data class SearchResult(val title: String, val snippet: String, val url: String)

    suspend operator fun invoke(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()
        try {
            val url = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                .timeout(10000)
                .get()

            // Google search results are typically contained in div.g
            val elements = doc.select("div.g")
            if (elements.isEmpty()) {
                android.util.Log.w("GoogleSearch", "No div.g elements found. Using body text fallback.")
                // Extract headings and paragraphs to get some context
                val bodyText = doc.select("h3, span, div").text().take(2000)
                if (bodyText.isNotBlank()) {
                    results.add(SearchResult("Google Search Results (Fallback)", bodyText, url))
                }
            } else {
                for (element in elements) {
                    val title = element.select("h3").text()
                    // Snippet classes change, but we can try to get the text of the description area
                    val snippet = element.select("div.VwiC3b, div.content, div.st").text() 
                    val link = element.select("a").firstOrNull()?.attr("href")

                    if (!title.isNullOrBlank() && !link.isNullOrBlank() && link.startsWith("http")) {
                        results.add(SearchResult(title, snippet, link))
                    }
                    if (results.size >= 5) break // Get top 5 results
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GoogleSearch", "Search failed: ${e.message}")
        }
        results
    }
}
