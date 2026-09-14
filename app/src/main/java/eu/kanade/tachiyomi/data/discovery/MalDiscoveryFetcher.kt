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
     * @param year null = do not filter by year (Any)
     * @param month null = do not filter by month (Any), 1–12 when set
     * When both null → no start_date (like leaving MAL advanced search empty).
     * When only year set → start_date=YYYY-01 (year bucket).
     * When year + month → start_date=YYYY-MM (vision default = current month/year).
     */
    suspend fun fetchSeasonalManga(
        year: Int? = null,
        month: Int? = null,
    ): List<MalDiscoveryItem> {
        return withContext(Dispatchers.IO) {
            val urls = buildUrlList(year, month)

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
                }
            }

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

    private fun buildUrlList(year: Int?, month: Int?): List<String> {
        val primary = when {
            year != null && month != null && month in 1..12 -> {
                val startDate = String.format("%04d-%02d", year, month)
                "https://api.jikan.moe/v4/manga?start_date=$startDate&order_by=score&sort=desc&limit=25&sfw=true"
            }
            year != null && month == null -> {
                val startDate = String.format("%04d-01", year)
                "https://api.jikan.moe/v4/manga?start_date=$startDate&order_by=score&sort=desc&limit=25&sfw=true"
            }
            else -> {
                // Both empty — no date filter (MAL empty selection)
                "https://api.jikan.moe/v4/manga?status=publishing&order_by=score&sort=desc&limit=25&sfw=true"
            }
        }

        return listOf(
            primary,
            "https://api.jikan.moe/v4/manga?status=publishing&order_by=score&sort=desc&limit=25&sfw=true",
            "https://api.jikan.moe/v4/top/manga?filter=publishing&limit=25",
        ).distinct()
    }

    companion object {
        /** Default = current calendar month + year (vision). */
        fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

        fun currentMonth(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    }
}
