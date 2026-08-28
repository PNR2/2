@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MalDiscoveryFetcher {

    // Added strict 15-second timeouts so the spinner never hangs forever!
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun fetchSeasonalManga(): List<MalDiscoveryItem> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.jikan.moe/v4/top/manga?filter=publishing")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
                    val parsed = json.decodeFromString<JikanMangaResponse>(responseBody)

                    parsed.data.map { jikanData ->
                        val imageUrl = jikanData.images?.jpg?.largeImageUrl
                            ?: jikanData.images?.jpg?.imageUrl

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
                } else {
                    // ERROR CATCHER 1: API blocked us (e.g., 403 or 429 Rate Limit)
                    listOf(
                        MalDiscoveryItem(
                            malId = -1,
                            title = "API Error: HTTP ${response.code}",
                            coverUrl = "https://via.placeholder.com/300x400.png/f04c4c/ffffff?text=API+Error",
                            synopsis = "The API rejected the request. Body: $responseBody",
                            score = 0.0,
                            startDate = "Error",
                            isSeasonal = true,
                        ),
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // ERROR CATCHER 2: The app crashed while parsing or lost internet connection
                listOf(
                    MalDiscoveryItem(
                        malId = -2,
                        title = "Crash: ${e.javaClass.simpleName}",
                        coverUrl = "https://via.placeholder.com/300x400.png/f04c4c/ffffff?text=Crash",
                        synopsis = e.message ?: "Unknown crash occurred.",
                        score = 0.0,
                        startDate = "Error",
                        isSeasonal = true,
                    ),
                )
            }
        }
    }
}
