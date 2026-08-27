package eu.kanade.tachiyomi.data.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JikanMangaResponse(
    val data: List<JikanMangaData> = emptyList(),
)

@Serializable
data class JikanMangaData(
    @SerialName("mal_id")
    val malId: Long,
    val title: String,
    val images: JikanImages? = null,
    val synopsis: String? = null,
    val score: Double? = null,
    val published: JikanPublished? = null,
)

@Serializable
data class JikanImages(
    val jpg: JikanImageUrls? = null,
    val webp: JikanImageUrls? = null,
)

@Serializable
data class JikanImageUrls(
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("large_image_url")
    val largeImageUrl: String? = null,
)

@Serializable
data class JikanPublished(
    val from: String? = null,
    val to: String? = null,
)

data class MalDiscoveryItem(
    val malId: Long,
    val title: String,
    val coverUrl: String?,
    val synopsis: String?,
    val score: Double?,
    val startDate: String?,
    val isSeasonal: Boolean,
)
