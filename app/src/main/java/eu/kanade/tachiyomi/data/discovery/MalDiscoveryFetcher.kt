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
            // Try two different endpoints so we have a better chance of getting data
            val urls = listOf(
                "https://api.jikan.moe/v4/top/manga?filter=publishing&limit=25",
                "https://api.jikan.moe/v4/manga?status=publishing&order_by=start_date&sort=desc&limit=25",
            )

            for (url in urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        )
                        .header("Accept", "application/json")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
                        val parsed = json.decodeFromString<JikanMangaResponse>(responseBody)

                        if (parsed.data.isNotEmpty()) {
                            return@withContext parsed.data.map { jikanData ->
                                val imageUrl = jikanData.images?.jpg?.largeImageUrl
                                    ?: jikanData.images?.jpg?.imageUrl
                                    ?: jikanData.images?.webp?.largeImageUrl
                                    ?: jikanData.images?.webp?.imageUrl

                                MalDiscoveryItem(
                                    malId = jikanData.malId,
                                    title = jikanData.title,
                                    coverUrl = imageUrl,
                                    synopsis = jikanData.synopsis,
                                    score = jikanData.score,
                                    startDate = jikanData.published?.from,
                                    isSeasonal = true,
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Try next URL
                }
            }

            // If both endpoints failed, return a visible error item so we know something is wrong
            listOf(
                MalDiscoveryItem(
                    malId = -1,
                    title = "Could not load seasonal manga",
                    coverUrl = "",
                    synopsis = "Both Jikan endpoints failed. Check internet or try again later.",
                    score = 0.0,
                    startDate = "Error",
                    isSeasonal = true,
                ),
            )
        }
    }
}
