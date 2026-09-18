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
 * MAL / Jikan seasonal (and filtered) manga loader.
 *
 * - Jikan max = 25 per page → we page until has_next_page ends or MAX_PAGES
 * - When year/month set: NEVER fall back to "top publishing" (that was Berserk/One Piece)
 * - Client-side check on published.from so wrong titles are dropped
 */
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
            val collected = linkedMapOf<Long, MalDiscoveryItem>()
            val baseUrl = buildPrimaryUrl(year, month)
            val dateFilterActive = year != null || month != null

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
                    page++
                } catch (_: Exception) {
                    break
                }
            }

            // Only if NO date filter: optional second strategy if still empty
            if (collected.isEmpty() && !dateFilterActive) {
                try {
                    val fallback =
                        "https://api.jikan.moe/v4/manga?status=publishing&type=manga&order_by=score&sort=desc&limit=25&sfw=true"
                    var p = 1
                    var next = true
                    while (next && p <= MAX_PAGES) {
                        if (p > 1) delay(PAGE_DELAY_MS)
                        val (items, pagination) = fetchPage("$fallback&page=$p")
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
                        malId = -999,
                        title = if (dateFilterActive) {
                            "No manga for this date filter"
                        } else {
                            "Could not load manga"
                        },
                        coverUrl = null,
                        synopsis = if (dateFilterActive) {
                            "Jikan returned no titles for the selected month/year, " +
                                "or the API rate-limited. Try Any month, another year, " +
                                "or wait ~30s and Apply again."
                        } else {
                            "Jikan API failed. Wait and try Apply filter again."
                        },
                        score = 0.0,
                        startDate = "Empty",
                        isSeasonal = true,
                    ),
                )
            } else {
                collected.values.toList()
            }
        }
    }

    private fun buildPrimaryUrl(year: Int?, month: Int?): String {
        val base = "https://api.jikan.moe/v4/manga?type=manga&limit=25&sfw=true"

        return when {
            year != null && month != null && month in 1..12 -> {
                val start = String.format("%04d-%02d-01", year, month)
                val endDay = lastDayOfMonth(year, month)
                val end = String.format("%04d-%02d-%02d", year, month, endDay)
                // order by start_date = closer to MAL "new this month"
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
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
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
        val items = parsed.data.map { item -> mapItem(item) }
        return items to parsed.pagination
    }

    private fun mapItem(item: JikanMangaData): MalDiscoveryItem {
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
            authors = authors,
            genres = genres,
            alternativeTitles = altTitles.distinct(),
        )
    }

    /**
     * published.from is ISO like 2026-02-15T00:00:00+00:00
     */
    private fun matchesDateFilter(
        publishedFrom: String?,
        year: Int?,
        month: Int?,
    ): Boolean {
        if (publishedFrom.isNullOrBlank()) return false
        // Take YYYY-MM-DD prefix
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
        /** 25 * 40 = up to 1000 titles (Jikan page size is max 25) */
        private const val MAX_PAGES = 40

        /** Stay under ~3 requests/second */
        private const val PAGE_DELAY_MS = 400L

        fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

        fun currentMonth(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    }
}
