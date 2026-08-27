package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import logcat.LogPriority
import logcat.logcat
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Downloads and parses XML RSS feeds using OkHttp and JSoup.
 */
class RssNewsFetcher {
    // Injects Mihon's default network client
    private val network: NetworkHelper = Injekt.get()

    suspend fun fetchNews(feedUrl: String, sourceName: String): List<RssNewsItem> {
        return try {
            val request = GET(feedUrl)

            // Mihon uses a custom await() extension for OkHttp Calls
            val response = network.client.newCall(request).await()
            val xmlBody = response.body?.string() ?: return emptyList()

            // Parse the XML using JSoup's native XML parser
            val document = Jsoup.parse(xmlBody, "", Parser.xmlParser())
            val items = document.select("item")

            items.mapNotNull { item ->
                val title = item.selectFirst("title")?.text() ?: return@mapNotNull null
                val link = item.selectFirst("link")?.text() ?: return@mapNotNull null
                val rawDescription = item.selectFirst("description")?.text() ?: ""
                val pubDateStr = item.selectFirst("pubDate")?.text()

                // Some RSS feeds put the image in a <media:content> tag, others embed it in the HTML description
                val imageUrl = item.selectFirst("media|content")?.attr("url")
                    ?: Jsoup.parse(rawDescription).selectFirst("img")?.attr("src")

                // Clean HTML tags out of the description text for clean UI display
                val cleanDescription = Jsoup.parse(rawDescription).text()

                RssNewsItem(
                    title = title,
                    link = link,
                    description = cleanDescription,
                    imageUrl = imageUrl,
                    publicationDate = parseDate(pubDateStr),
                    sourceName = sourceName,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "MUSYomi: Failed to fetch RSS from $sourceName - ${e.message}" }
            emptyList()
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return System.currentTimeMillis()
        return try {
            // Standard RSS Date Format (e.g., "Tue, 04 Oct 2026 10:00:00 GMT")
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            format.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}

// Data class to hold the parsed results before we save them to the database
data class RssNewsItem(
    val title: String,
    val link: String,
    val description: String,
    val imageUrl: String?,
    val publicationDate: Long,
    val sourceName: String,
)
