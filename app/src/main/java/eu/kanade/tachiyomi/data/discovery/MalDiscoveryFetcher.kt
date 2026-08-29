@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MalDiscoveryFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun fetchSeasonalManga(): List<MalDiscoveryItem> {
        return withContext(Dispatchers.IO) {
            val urls = listOf(
                "https://api.jikan.moe/v4/top/manga?filter=publishing&limit=20",
                "https://api.jikan.moe/v4/manga?status=publishing&order_by=score&sort=desc&limit=20",
            )

            for (url in urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Accept", "application/json")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    val body = response.body?.string()

                    if (response.isSuccessful && !body.isNullOrEmpty()) {
                        val parsed = json.decodeFromString<JikanMangaResponse>(body)
                        if (parsed.data.isNotEmpty()) {
                            return@withContext parsed.data.map { item ->
                                MalDiscoveryItem(
                                    malId = item.malId,
                                    title = item.title,
                                    coverUrl = item.images?.jpg?.largeImageUrl
                                        ?: item.images?.jpg?.imageUrl
                                        ?: item.images?.webp?.largeImageUrl
                                        ?: item.images?.webp?.imageUrl,
                                    synopsis = item.synopsis,
                                    score = item.score,
                                    startDate = item.published?.from,
                                    isSeasonal = true,
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // try next
                }
            }

            // Always return at least one item so we can see if the UI and database work
            listOf(
                MalDiscoveryItem(
                    malId = -999,
                    title = "TEST - If you see this, database + UI work",
                    coverUrl = null,
                    synopsis = "Jikan API failed. This is a test item.",
                    score = 0.0,
                    startDate = "Test",
                    isSeasonal = true,
                ),
            )
        }
    }
}
