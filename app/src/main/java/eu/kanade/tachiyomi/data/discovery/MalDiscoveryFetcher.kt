package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reaches out to the Jikan (MyAnimeList) API to fetch trending/seasonal manga.
 */
class MalDiscoveryFetcher {

    // Using a standard OkHttpClient to guarantee no module visibility issues
    private val client = OkHttpClient()

    // Configure JSON parser to safely ignore any extra data the API sends
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchSeasonalManga(): List<MalDiscoveryItem> {
        return withContext(Dispatchers.IO) {
            try {
                // Fetching the top currently publishing manga
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
                    emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
}
