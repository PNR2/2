@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
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

    suspend fun fetchSeasonalManga(): List<MalDiscoveryItem> {
        return withContext(Dispatchers.IO) {
            // Multiple endpoints + small delay to avoid rate limit
            val urls = listOf(
                "https://api.jikan.moe/v4/top/manga?filter=publishing&limit=25",
                "https://api.jikan.moe/v4/manga?status=publishing&order_by=score&sort=desc&limit=25",
                "https://api.jikan.moe/v4/top/manga?limit=25",
            )

            for ((index, url) in urls.withIndex()) {
                try {
                    if (index > 0) delay(800) // avoid rate limit

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

                                MalDiscoveryItem(
                                    malId = item.malId,
                                    title = item.title,
                                    coverUrl = image,
                                    synopsis = item.synopsis,
                                    score = item.score,
                                    startDate = item.published?.from,
                                    isSeasonal = true,
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // try next endpoint
                }
            }

            // Fallback test item (only if everything fails)
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
