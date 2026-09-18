package eu.kanade.tachiyomi.data.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JikanMangaResponse(
    val pagination: JikanPagination? = null,
    val data: List<JikanMangaData> = emptyList(),
)

@Serializable
data class JikanPagination(
    @SerialName("last_visible_page")
    val lastVisiblePage: Int = 1,
    @SerialName("has_next_page")
    val hasNextPage: Boolean = false,
    @SerialName("current_page")
    val currentPage: Int = 1,
    val items: JikanPaginationItems? = null,
)

@Serializable
data class JikanPaginationItems(
    val count: Int = 0,
    val total: Int = 0,
    @SerialName("per_page")
    val perPage: Int = 25,
)

@Serializable
data class JikanMangaData(
    @SerialName("mal_id")
    val malId: Long,
    val title: String,
    @SerialName("title_english")
    val titleEnglish: String? = null,
    @SerialName("title_japanese")
    val titleJapanese: String? = null,
    val titles: List<JikanTitle>? = null,
    val images: JikanImages? = null,
    val synopsis: String? = null,
    val score: Double? = null,
    val chapters: Int? = null,
    val status: String? = null,
    val published: JikanPublished? = null,
    val authors: List<JikanAuthor>? = null,
    val genres: List<JikanGenre>? = null,
)

@Serializable
data class JikanTitle(
    val type: String? = null,
    val title: String? = null,
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

@Serializable
data class JikanAuthor(
    val name: String? = null,
)

@Serializable
data class JikanGenre(
    val name: String? = null,
)

data class MalDiscoveryItem(
    val malId: Long,
    val title: String,
    val coverUrl: String?,
    val synopsis: String?,
    val score: Double?,
    val startDate: String?,
    val isSeasonal: Boolean,
    val sourceId: Long? = null,
    val mangaUrl: String? = null,
    val chapters: Int? = null,
    val status: String? = null,
    val authors: String? = null,
    val genres: String? = null,
    val alternativeTitles: List<String> = emptyList(),
)
