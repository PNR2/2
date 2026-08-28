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
                    .url("https://api.jikan.moe/v4/top/manga?filter=publishing&limit=25")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MUSYomi",
                    )
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
                    listOf(
                        MalDiscoveryItem(
                            malId = -1,
                            title = "API Error: HTTP ${response.code}",
                            coverUrl = "",
                            synopsis = "Failed with status code: ${response.code}",
                            score = 0.0,
                            startDate = "Error",
                            isSeasonal = true,
                        ),
                    )
                }
            } catch (e: Exception) {
                listOf(
                    MalDiscoveryItem(
                        malId = -2,
                        title = "Network Error: ${e.javaClass.simpleName}",
                        coverUrl = "",
                        synopsis = e.message ?: "Could not connect to server.",
                        score = 0.0,
                        startDate = "Error",
                        isSeasonal = true,
                    ),
                )
            }
        }
    }
}
