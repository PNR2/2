@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MalDiscoveryFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun fetchSeasonalManga(
        year: Int? = null,
        month: Int? = null,
    ): List<MalDiscoveryItem> {
        return withContext(Dispatchers.IO) {
            val cal = Calendar.getInstance()
            val targetYear = year ?: cal.get(Calendar.YEAR)
            val targetMonth = month ?: (cal.get(Calendar.MONTH) + 1) // 1-12

            // Build start_date filter like MAL Advanced Search (YYYY-MM)
            val startDate = String.format("%04d-%02d", targetYear, targetMonth)

            val urls = listOf(
                // Best attempt: start_date filter
                "https://api.jikan.moe/v4/manga?start_date=$startDate&order_by=score&sort=desc&limit=25&sfw=true",
                // Fallback 1
                "https://api.jikan.moe/v4/manga?status=publishing&order_by=score&sort=desc&limit=25&sfw=true",
                // Fallback 2
                "https://api.jikan.moe/v4/top/manga?filter=publishing&limit=25",
            )

            for ((index, url) in urls.withIndex()) {
                try {
                    if (index > 0) delay(900)

                    val request = Request.Builder()
                        .url(url)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                        )
                        .header("Accept", "application/json")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    val body = response.body?.string()

                    if (response.isSuccessful && !body.isNullOrEmpty()) {
                        val parsed = json.decodeFromString<JikanMangaResponse>(body)

                        if (parsed.data.isNotEmpty()) {
                            return@withContext parsed.data.map { item ->
                                val image = item.images?.jpg?.largeImageUrl
                                    ?: item.images?.jpg?.imageUrl
                                    ?: item.images?.webp?.largeImageUrl
                                    ?: item.images?.webp?.imageUrl

                                val altTitles = mutableListOf<String>()
                                item.titleEnglish?.let { altTitles.add(it) }
                                item.titleJapanese?.let { altTitles.add(it) }
                                item.titles?.forEach { t ->
                                    t.title?.let { altTitles.add(it) }
                                }

                                val authors = item.authors
                                    ?.mapNotNull { it.name }
                                    ?.joinToString(", ")

                                val genres = item.genres
                                    ?.mapNotNull { it.name }
                                    ?.joinToString(", ")

                                MalDiscoveryItem(
                                    malId = item.malId,
                                    title = item.title,
                                    coverUrl = image,
                                    synopsis = item.synopsis,
                                    score = item.score,
                                    startDate = item.published?.from,
                                    isSeasonal = true,
                                    chapters = item.chapters,
                                    status = item.status,
                                    authors = authors,
                                    genres = genres,
                                    alternativeTitles = altTitles.distinct(),
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // try next
                }
            }

            // Last fallback so the UI never stays empty
            listOf(
                MalDiscoveryItem(
                    malId = -999,
                    title = "TEST - API still failing",
                    coverUrl = null,
                    synopsis = "Jikan could not be reached. Check internet or try again later.",
                    score = 0.0,
                    startDate = "Error",
                    isSeasonal = true,
                ),
            )
        }
    }
}
