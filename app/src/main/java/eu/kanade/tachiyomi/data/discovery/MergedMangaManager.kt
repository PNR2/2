package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
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
     * Creates or updates a cohesive manga entry and links matching sources from extensions.
     */
    suspend fun createOrUpdateMergedManga(
        title: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        // Create or update the main entry (prevents duplicates)
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

        val sourceManager: SourceManager = try {
            Injekt.get()
        } catch (_: Exception) {
            return@withContext mergedId
        }

        val sources = sourceManager.getOnlineSources()
            .filterIsInstance<CatalogueSource>()
            .sortedByDescending { it.lang.equals("en", ignoreCase = true) }
            .take(40)

        if (sources.isEmpty()) {
            return@withContext mergedId
        }

        // Search all sources
        val searchResults = coroutineScope {
            sources.map { source ->
                async {
                    searchInSource(source, title)
                }
            }.awaitAll()
        }

        // Accept results and link them
        val accepted = searchResults.flatten()
            .distinctBy { "${it.sourceId}_${it.url}" }
            .take(25)

        accepted.forEach { match ->
            repository.addReference(
                MergedMangaReference(
                    mergedId = mergedId,
                    sourceId = match.sourceId,
                    mangaUrl = match.url,
                    mangaTitle = match.title,
                    chapterCount = 0,
                    isInfoSource = false,
                    priority = if (match.lang.equals("en", ignoreCase = true)) 10 else 3,
                ),
            )
        }

        mergedId
    }

    private suspend fun searchInSource(
        source: CatalogueSource,
        title: String,
    ): List<SearchMatch> {
        return try {
            withTimeoutOrNull(5000) {
                val result = source.getSearchManga(1, title, FilterList())
                result.mangas.take(6).map { manga ->
                    SearchMatch(
                        sourceId = source.id,
                        url = manga.url,
                        title = manga.title,
                        lang = source.lang,
                    )
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class SearchMatch(
        val sourceId: Long,
        val url: String,
        val title: String,
        val lang: String,
    )
}
