@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Tries MangaUpdates public API for scanlation groups.
 * Vision: show groups when known; leave empty when not found / error.
 */
class ScanlationFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun fetchGroupsForTitle(title: String): String? = withContext(Dispatchers.IO) {
        val q = title.trim()
        if (q.length < 2) return@withContext null

        try {
            val seriesId = searchSeriesId(q) ?: return@withContext null
            val groups = fetchSeriesGroups(seriesId)
            if (groups.isEmpty()) null else groups.joinToString(", ")
        } catch (_: Exception) {
            null
        }
    }

    private fun searchSeriesId(title: String): Long? {
        val bodyJson = """{"search":"${title.replace("\"", "")}","page":1,"perpage":5}"""
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.mangaupdates.com/v1/series/search")
            .header("User-Agent", "MUSYomi/1.0")
            .header("Accept", "application/json")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful || text.isEmpty()) return null

        val parsed = json.decodeFromString<MuSearchResponse>(text)
        val results = parsed.results.orEmpty()
        if (results.isEmpty()) return null

        val norm = title.lowercase().trim()
        val best = results.maxByOrNull { hit ->
            val name = hit.record?.title?.lowercase().orEmpty()
            when {
                name == norm -> 100
                name.contains(norm) || norm.contains(name) -> 70
                else -> 20
            }
        }
        return best?.record?.seriesId
    }

    private fun fetchSeriesGroups(seriesId: Long): List<String> {
        val request = Request.Builder()
            .url("https://api.mangaupdates.com/v1/series/$seriesId")
            .header("User-Agent", "MUSYomi/1.0")
            .header("Accept", "application/json")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful || text.isEmpty()) return emptyList()

        val parsed = json.decodeFromString<MuSeriesResponse>(text)
        val names = mutableListOf<String>()
        parsed.latestChapter?.scanGroup?.name?.let { names.add(it) }
        parsed.publications?.forEach { pub ->
            pub.publisherName?.let { if (it.isNotBlank()) names.add(it) }
        }
        // Some series expose groups under different keys; keep unique short list
        return names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)
    }

    @Serializable
    private data class MuSearchResponse(
        val results: List<MuSearchHit>? = null,
    )

    @Serializable
    private data class MuSearchHit(
        val record: MuSeriesRecord? = null,
    )

    @Serializable
    private data class MuSeriesRecord(
        @SerialName("series_id") val seriesId: Long? = null,
        val title: String? = null,
    )

    @Serializable
    private data class MuSeriesResponse(
        val title: String? = null,
        @SerialName("latest_chapter") val latestChapter: MuLatestChapter? = null,
        val publications: List<MuPublication>? = null,
    )

    @Serializable
    private data class MuLatestChapter(
        @SerialName("scan_group") val scanGroup: MuNamed? = null,
    )

    @Serializable
    private data class MuNamed(
        val name: String? = null,
    )

    @Serializable
    private data class MuPublication(
        @SerialName("publisher_name") val publisherName: String? = null,
    )
}
