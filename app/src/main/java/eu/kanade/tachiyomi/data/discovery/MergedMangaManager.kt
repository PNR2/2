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
     * Creates a merged manga entry and links the best matching extension results.
     * Prefers English sources and cleaner title matches.
     */
    suspend fun createOrUpdateMergedManga(
        title: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        // 1. Create the main merged entry
        val mergedId = repository.createMergedManga(
            MergedManga(
                title = title,
                coverUrl = coverUrl,
                synopsis = synopsis,
                author = author,
                malId = malId,
                preferredLanguage = "en",
            ),
        )

        // 2. Get SourceManager safely
        val sourceManager: SourceManager = try {
            Injekt.get()
        } catch (_: Exception) {
            return@withContext mergedId
        }

        val sources = sourceManager.getOnlineSources()
            .filterIsInstance<CatalogueSource>()
            .sortedByDescending { it.lang.equals("en", ignoreCase = true) } // English first
            .take(25) // limit to keep it reasonably fast

        if (sources.isEmpty()) {
            return@withContext mergedId
        }

        val cleanTitle = cleanTitle(title)

        // 3. Search sources in parallel
        val searchResults = coroutineScope {
            sources.map { source ->
                async {
                    searchInSource(source, cleanTitle)
                }
            }.awaitAll()
        }

        // 4. Filter and add good matches
        searchResults.flatten()
            .filter { isGoodMatch(cleanTitle, it.title) }
            .sortedByDescending { match ->
                // Higher score = better
                var score = 0
                if (match.lang.equals("en", ignoreCase = true)) score += 20
                if (cleanTitle.equals(cleanTitle(match.title), ignoreCase = true)) score += 15
                score
            }
            .take(12) // max 12 references per merged manga
            .forEach { match ->
                repository.addReference(
                    MergedMangaReference(
                        mergedId = mergedId,
                        sourceId = match.sourceId,
                        mangaUrl = match.url,
                        mangaTitle = match.title,
                        chapterCount = 0,
                        isInfoSource = false,
                        priority = if (match.lang.equals("en", ignoreCase = true)) 10 else 5,
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
            withTimeoutOrNull(3000) {
                val result = source.getSearchManga(1, title, FilterList())
                result.mangas.take(3).map { manga ->
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

    private fun cleanTitle(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isGoodMatch(searchTitle: String, resultTitle: String): Boolean {
        val cleanResult = cleanTitle(resultTitle)
        if (cleanResult.isBlank()) return false

        // Exact match after cleaning
        if (cleanResult == searchTitle) return true

        // Contains the main title
        if (cleanResult.contains(searchTitle) || searchTitle.contains(cleanResult)) return true

        // Very short titles are risky – skip them
        if (cleanResult.length < 4) return false

        return false
    }

    private data class SearchMatch(
        val sourceId: Long,
        val url: String,
        val title: String,
        val lang: String,
    )
}
