package com.chhanda.ai.domain.usecase

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

data class WebSearchResult(
    val title: String,
    val snippet: String,
    val url: String
)

/**
 * Senior Architect Level Web Search Engine.
 * Uses Google as default search, falls back to DuckDuckGo/Jina on blocks.
 */
@Singleton
class WebSearchUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend operator fun invoke(query: String): List<WebSearchResult> = withContext(Dispatchers.IO) {
        if (!isInternetAvailable()) {
            android.util.Log.w("WebSearch", "No internet connection available. Skipping web search.")
            return@withContext emptyList()
        }

        val results = mutableListOf<WebSearchResult>()
        
        // 🚀 Stage 1: Google HTML Search (Primary)
        try {
            android.util.Log.d("WebSearch", "Searching Google for query: $query")
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.google.com/search?q=$encodedQuery"
            
            val doc = Jsoup.connect(searchUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .timeout(10000)
                .get()

            val elements = doc.select("div.g") // Google's result container
            for (element in elements) {
                val titleEl = element.select("h3").firstOrNull()
                val snippetEl = element.select("div.VwiC3b, div.s, span.aCOpRe").firstOrNull()
                val linkEl = element.select("a").firstOrNull()
                
                var rawUrl = linkEl?.attr("href")?.trim() ?: ""
                val title = titleEl?.text()?.trim() ?: ""
                val snippet = snippetEl?.text()?.trim() ?: ""
                
                // Clean Google redirect urls like /url?q=...
                if (rawUrl.startsWith("/url?q=")) {
                    rawUrl = rawUrl.substringAfter("/url?q=").substringBefore("&")
                    rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                }

                if (title.isNotEmpty() && snippet.isNotEmpty() && rawUrl.startsWith("http")) {
                    results.add(WebSearchResult(title, snippet, rawUrl))
                }
                
                if (results.size >= 5) break // Keep top 5 search results
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSearch", "Stage 1 (Google) failed: ${e.message}")
        }

        // 🚀 Stage 2 Fallback: DuckDuckGo HTML Search
        if (results.isEmpty()) {
            try {
                android.util.Log.d("WebSearch", "Searching DDG HTML for query: $query")
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"
                
                val doc = Jsoup.connect(searchUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .timeout(10000)
                    .get()

                val elements = doc.select(".result")
                for (element in elements) {
                    val titleEl = element.select(".result__title a").firstOrNull() ?: element.select("a.result__url").firstOrNull()
                    val snippetEl = element.select(".result__snippet").firstOrNull()
                    
                    val rawUrl = titleEl?.attr("href")?.trim() ?: ""
                    val title = titleEl?.text()?.trim() ?: ""
                    val snippet = snippetEl?.text()?.trim() ?: ""

                    if (title.isNotEmpty() && snippet.isNotEmpty() && rawUrl.isNotEmpty()) {
                        val cleanUrl = cleanDdgUrl(rawUrl)
                        results.add(WebSearchResult(title, snippet, cleanUrl))
                    }
                    if (results.size >= 5) break
                }
            } catch (e: Exception) {
                android.util.Log.e("WebSearch", "Stage 2 (DDG HTML) failed: ${e.message}")
            }
        }

        // 🚀 Stage 3 Fallback: Jina Search API (r.jina.ai search helper)
        if (results.isEmpty()) {
            try {
                android.util.Log.d("WebSearch", "Searching Jina for query: $query")
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val jinaUrl = "https://r.jina.ai/https://html.duckduckgo.com/html/?q=$encodedQuery"
                
                val doc = Jsoup.connect(jinaUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .timeout(15000)
                    .get()
                
                val bodyText = doc.body().text()
                if (bodyText.length > 200) {
                    results.add(WebSearchResult(
                        title = "Web Search Results",
                        snippet = bodyText.take(1500),
                        url = "https://google.com/search?q=$encodedQuery"
                    ))
                }
            } catch (e: Exception) {
                android.util.Log.e("WebSearch", "Stage 3 (Jina search fallback) failed: ${e.message}")
            }
        }

        android.util.Log.i("WebSearch", "Web search finished. Found ${results.size} results.")
        return@withContext results
    }

    private fun cleanDdgUrl(url: String): String {
        var cleaned = url
        if (cleaned.startsWith("//")) {
            cleaned = "https:$cleaned"
        }
        if (cleaned.contains("uddg=")) {
            try {
                val uddg = cleaned.substringAfter("uddg=")
                val rawUrl = uddg.substringBefore("&")
                cleaned = URLDecoder.decode(rawUrl, "UTF-8")
            } catch (e: Exception) {}
        }
        return cleaned
    }

    private fun isInternetAvailable(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
            activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
}

