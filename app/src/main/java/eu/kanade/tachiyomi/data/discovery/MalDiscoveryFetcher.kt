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

/**
 * Loads MAL manga via Jikan.
 * - Max 25 per page → pages until done or MAX_PAGES (up to ~1000)
 * - When year/month set: NEVER fall back to "top publishing"
 * - Extra client filter on published.from
 */
class MalDiscoveryFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun fetchSeasonalManga(
        year: Int? = null,
        month: Int? = null,
    ): List<MalDiscoveryItem> = withContext(Dispatchers.IO) {
        val collected = linkedMapOf<Long, MalDiscoveryItem>()
        val dateFilterActive = year != null || month != null
        val baseUrl = buildPrimaryUrl(year, month)

        var page = 1
        var hasNext = true

        while (hasNext && page <= MAX_PAGES) {
            try {
                if (page > 1) delay(PAGE_DELAY_MS)
                val url = "$baseUrl&page=$page"
                val (items, pagination) = fetchPage(url)

                items.forEach { item ->
                    if (item.malId <= 0) return@forEach
                    if (dateFilterActive && !matchesDateFilter(item.startDate, year, month)) {
                        return@forEach
                    }
                    collected[item.malId] = item
                }

                hasNext = pagination?.hasNextPage == true
                // If API ignores dates and returns junk, stop early after page 1 empty match
                if (dateFilterActive && page == 1 && collected.isEmpty() && items.isNotEmpty()) {
                    // All items failed client date filter → API not respecting dates
                    hasNext = false
                }
                page++
            } catch (_: Exception) {
                break
            }
        }

        // Fallback ONLY when user chose Any/Any
        if (collected.isEmpty() && !dateFilterActive) {
            try {
                val fb =
                    "https://api.jikan.moe/v4/manga?status=publishing&type=manga&order_by=score&sort=desc&limit=25&sfw=true"
                var p = 1
                var next = true
                while (next && p <= MAX_PAGES) {
                    if (p > 1) delay(PAGE_DELAY_MS)
                    val (items, pagination) = fetchPage("$fb&page=$p")
                    items.forEach { item ->
                        if (item.malId > 0) collected[item.malId] = item
                    }
                    next = pagination?.hasNextPage == true
                    p++
                }
            } catch (_: Exception) {
            }
        }

        if (collected.isEmpty()) {
            listOf(
                MalDiscoveryItem(
                    malId = -1,
                    title = "No results for this filter",
                    coverUrl = null,
                    synopsis = "Jikan returned nothing for this month/year, or rate-limited. " +
                        "Try Any month, year 2024/2025, wait 30s, Apply again.",
                    score = null,
                    startDate = null,
                    isSeasonal = true,
                ),
            )
        } else {
            collected.values.toList()
        }
    }

    private fun buildPrimaryUrl(year: Int?, month: Int?): String {
        val base = "https://api.jikan.moe/v4/manga?type=manga&limit=25&sfw=true"
        return when {
            year != null && month != null && month in 1..12 -> {
                val start = String.format("%04d-%02d-01", year, month)
                val end = String.format(
                    "%04d-%02d-%02d",
                    year,
                    month,
                    lastDayOfMonth(year, month),
                )
                "$base&start_date=$start&end_date=$end&order_by=start_date&sort=desc"
            }
            year != null && month == null -> {
                val start = String.format("%04d-01-01", year)
                val end = String.format("%04d-12-31", year)
                "$base&start_date=$start&end_date=$end&order_by=start_date&sort=desc"
            }
            else -> {
                "$base&status=publishing&order_by=score&sort=desc"
            }
        }
    }

    private fun fetchPage(url: String): Pair<List<MalDiscoveryItem>, JikanPagination?> {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            )
            .header("Accept", "application/json")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
        if (!response.isSuccessful || body.isNullOrEmpty()) {
            return emptyList<MalDiscoveryItem>() to null
        }

        val parsed = json.decodeFromString<JikanMangaResponse>(body)
        return parsed.data.map { mapItem(it) } to parsed.pagination
    }

    private fun mapItem(item: JikanMangaData): MalDiscoveryItem {
        val image = item.images?.jpg?.largeImageUrl
            ?: item.images?.jpg?.imageUrl
            ?: item.images?.webp?.largeImageUrl
            ?: item.images?.webp?.imageUrl

        val altTitles = mutableListOf<String>()
        item.titleEnglish?.let { altTitles.add(it) }
        item.titleJapanese?.let { altTitles.add(it) }
        item.titles?.forEach { t -> t.title?.let { altTitles.add(it) } }

        return MalDiscoveryItem(
            malId = item.malId,
            title = item.title,
            coverUrl = image,
            synopsis = item.synopsis,
            score = item.score,
            startDate = item.published?.from,
            isSeasonal = true,
            chapters = item.chapters,
            status = item.status,
            authors = item.authors?.mapNotNull { it.name }?.joinToString(", "),
            genres = item.genres?.mapNotNull { it.name }?.joinToString(", "),
            alternativeTitles = altTitles.distinct(),
        )
    }

    private fun matchesDateFilter(
        publishedFrom: String?,
        year: Int?,
        month: Int?,
    ): Boolean {
        if (publishedFrom.isNullOrBlank()) return false
        val datePart = publishedFrom.take(10)
        if (datePart.length < 7) return false
        val y = datePart.substring(0, 4).toIntOrNull() ?: return false
        val m = datePart.substring(5, 7).toIntOrNull() ?: return false
        if (year != null && y != year) return false
        if (month != null && month in 1..12 && m != month) return false
        return true
    }

    private fun lastDayOfMonth(year: Int, month: Int): Int {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    companion object {
        private const val MAX_PAGES = 40
        private const val PAGE_DELAY_MS = 400L

        fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

        fun currentMonth(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    }
}
