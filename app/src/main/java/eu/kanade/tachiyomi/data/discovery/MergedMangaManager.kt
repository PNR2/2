@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MergedMangaManager {

    private val repository = MergedMangaRepository()

    /**
     * Creates or updates a cohesive manga entry and links matching sources.
     */
    suspend fun createOrUpdateMergedManga(
        title: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        // 1. Create or update the main entry
        val mergedId = repository.createOrUpdateMergedManga(
            MergedManga(
                title = title,
                coverUrl = coverUrl,
                synopsis = synopsis,
                author = author,
                malId = malId,
                preferredLanguage = "en",
            ),
        )

        // 2. Get SourceManager
        val sourceManager = try {
            Injekt.get<SourceManager>()
        } catch (_: Exception) {
            return@withContext mergedId
        }

        // 3. Get searchable sources (prefer English)
        val sources = try {
            sourceManager.getOnlineSources()
                .filterIsInstance<CatalogueSource>()
                .sortedByDescending { it.lang.equals("en", ignoreCase = true) }
                .take(40)
        } catch (_: Exception) {
            emptyList()
        }

        if (sources.isEmpty()) {
            return@withContext mergedId
        }

        // 4. Search in parallel
        val searchResults = coroutineScope {
            sources.map { source ->
                async {
                    searchInSource(source, title)
                }
            }.awaitAll()
        }

        // 5. Keep good matches
        val accepted = searchResults
            .flatten()
            .distinctBy { match ->
                match.sourceId.toString() + "_" + match.url
            }
            .sortedByDescending { it.score }
            .take(20)

        // 6. Save references
        accepted.forEach { match ->
            repository.addReference(
                MergedMangaReference(
                    mergedId = mergedId,
                    sourceId = match.sourceId,
                    mangaUrl = match.url,
                    mangaTitle = match.title,
                    chapterCount = 0,
                    isInfoSource = match.score >= 80,
                    priority = match.score,
                ),
            )
        }

        mergedId
    }

    private suspend fun searchInSource(
        source: CatalogueSource,
        query: String,
    ): List<SearchMatch> {
        return try {
            withTimeoutOrNull(7000) {
                val page = source.getSearchManga(1, query, FilterList())
                page.mangas.take(6).mapNotNull { manga ->
                    val score = calculateScore(manga, query)
                    if (score >= 30) {
                        SearchMatch(
                            sourceId = source.id,
                            url = manga.url,
                            title = manga.title,
                            lang = source.lang,
                            score = score,
                        )
                    } else {
                        null
                    }
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun calculateScore(manga: SManga, query: String): Int {
        val mangaTitle = manga.title.trim().lowercase()
        val q = query.trim().lowercase()

        var score = 0

        when {
            mangaTitle == q -> score += 100
            mangaTitle.startsWith(q) || q.startsWith(mangaTitle) -> score += 80
            mangaTitle.contains(q) || q.contains(mangaTitle) -> score += 55
            else -> {
                val qWords = q.split(" ").filter { it.length > 2 }.toSet()
                val mWords = mangaTitle.split(" ").filter { it.length > 2 }.toSet()
                val common = qWords.intersect(mWords).size
                score += common * 12
            }
        }

        if (!manga.description.isNullOrBlank()) score += 5
        if (!manga.author.isNullOrBlank()) score += 3
        if (mangaTitle.length < 3) score -= 25

        return score.coerceIn(0, 120)
    }

    private data class SearchMatch(
        val sourceId: Long,
        val url: String,
        val title: String,
        val lang: String,
        val score: Int,
    )
}
