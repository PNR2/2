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

    /**
     * Fetches manga list from Jikan.
     * year/month null = Any (no date filter).
     * Uses full month date range + multiple pages so list is not stuck at 0–few items.
     */
    suspend fun fetchSeasonalManga(
        year: Int? = null,
        month: Int? = null,
    ): List<MalDiscoveryItem> {
        return withContext(Dispatchers.IO) {
            val collected = linkedMapOf<Long, MalDiscoveryItem>()

            val urls = buildUrlList(year, month)
            for ((index, baseUrl) in urls.withIndex()) {
                if (index > 0) delay(1000)
                // Up to 3 pages per strategy (25 * 3 = 75)
                for (page in 1..3) {
                    try {
                        if (page > 1) delay(400)
                        val url = if (baseUrl.contains("?")) {
                            "$baseUrl&page=$page"
                        } else {
                            "$baseUrl?page=$page"
                        }
                        val batch = fetchPage(url)
                        if (batch.isEmpty()) break
                        batch.forEach { item ->
                            if (item.malId > 0) {
                                collected[item.malId] = item
                            }
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
                // Prefer first strategy that returned data
                if (collected.isNotEmpty()) break
            }

            if (collected.isEmpty()) {
                listOf(
                    MalDiscoveryItem(
                        malId = -999,
                        title = "Could not load seasonal manga",
                        coverUrl = null,
                        synopsis = "Jikan API failed or rate-limited. Wait a minute and Apply filter again.",
                        score = 0.0,
                        startDate = "Error",
                        isSeasonal = true,
                    ),
                )
            } else {
                collected.values.toList()
            }
        }
    }

    private fun fetchPage(url: String): List<MalDiscoveryItem> {
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
        if (!response.isSuccessful || body.isNullOrEmpty()) return emptyList()

        val parsed = json.decodeFromString<JikanMangaResponse>(body)
        return parsed.data.map { item ->
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

    private fun buildUrlList(year: Int?, month: Int?): List<String> {
        val typeAndOrder = "type=manga&order_by=score&sort=desc&limit=25&sfw=true"

        val primary = when {
            year != null && month != null && month in 1..12 -> {
                val start = String.format("%04d-%02d-01", year, month)
                val endDay = lastDayOfMonth(year, month)
                val end = String.format("%04d-%02d-%02d", year, month, endDay)
                // Full month range (not bare YYYY-MM which returns almost nothing)
                "https://api.jikan.moe/v4/manga?start_date=$start&end_date=$end&$typeAndOrder"
            }
            year != null && month == null -> {
                val start = String.format("%04d-01-01", year)
                val end = String.format("%04d-12-31", year)
                "https://api.jikan.moe/v4/manga?start_date=$start&end_date=$end&$typeAndOrder"
            }
            else -> {
                "https://api.jikan.moe/v4/manga?status=publishing&$typeAndOrder"
            }
        }

        return listOf(
            primary,
            "https://api.jikan.moe/v4/manga?status=publishing&$typeAndOrder",
            "https://api.jikan.moe/v4/top/manga?filter=publishing&limit=25",
        ).distinct()
    }

    private fun lastDayOfMonth(year: Int, month: Int): Int {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    companion object {
        fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

        fun currentMonth(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    }
}
