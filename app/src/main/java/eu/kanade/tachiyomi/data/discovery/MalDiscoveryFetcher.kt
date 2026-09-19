@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MalDiscoveryFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchSeasonalManga(
        year: Int? = null,
        month: Int? = null,
        status: String? = null,
        genres: String? = null,
    ): List<MalDiscoveryItem> {
        val urlBuilder = "https://api.jikan.moe/v4/manga".toHttpUrl().newBuilder()

        if (year != null && year > 0) {
            val targetMonth = if (month != null && month in 1..12) month else 1
            val endMonth = if (month != null && month in 1..12) month else 12

            val lastDay = when (endMonth) {
                4, 6, 9, 11 -> "30"
                2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) "29" else "28"
                else -> "31"
            }

            val startStr = "$year-${targetMonth.toString().padStart(2, '0')}-01"
            val endStr = "$year-${endMonth.toString().padStart(2, '0')}-$lastDay"

            urlBuilder.addQueryParameter("start_date", startStr)
            urlBuilder.addQueryParameter("end_date", endStr)
        }

        if (!status.isNullOrBlank()) {
            urlBuilder.addQueryParameter("status", status)
        }
        if (!genres.isNullOrBlank()) {
            urlBuilder.addQueryParameter("genres", genres)
        }

        // CRITICAL FIX: We do NOT send order_by or sort to Jikan. 
        // This stops the 504 timeout. We sort it locally in the Repository instead.
        urlBuilder.addQueryParameter("limit", "25")

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Jikan API Failed: ${response.code}")
        }

        val bodyString = response.body?.string() ?: return emptyList()
        val jsonRoot = JSONObject(bodyString)

        if (!jsonRoot.has("data")) return emptyList()
        val dataArray = jsonRoot.getJSONArray("data")

        val results = mutableListOf<MalDiscoveryItem>()

        for (i in 0 until dataArray.length()) {
            val itemObj = dataArray.getJSONObject(i)
            val malId = itemObj.optLong("mal_id", -1L)
            if (malId <= 0) continue

            var coverUrl = ""
            val imagesObj = itemObj.optJSONObject("images")
            if (imagesObj != null) {
                val jpgObj = imagesObj.optJSONObject("jpg")
                if (jpgObj != null) {
                    coverUrl = jpgObj.optString("image_url", "")
                }
            }

            val publishedObj = itemObj.optJSONObject("published")

            results.add(
                MalDiscoveryItem(
                    malId = malId,
                    title = itemObj.optString("title", "Unknown Title"),
                    coverUrl = coverUrl,
                    score = itemObj.optDouble("score", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    chapters = itemObj.optInt("chapters", 0),
                    synopsis = itemObj.optString("synopsis", ""),
                    startDate = publishedObj?.optString("from") ?: "",
                    isSeasonal = true,
                    sourceId = null,
                    mangaUrl = null,
                    status = itemObj.optString("status", ""),
                    authors = null,
                    genres = null,
                ),
            )
        }

        return results
    }

    companion object {
        fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)
        fun currentMonth(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    }
}
