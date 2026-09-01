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

    suspend fun createOrUpdateMergedManga(
        title: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        // Create merged entry
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

        val sourceManager: SourceManager = try {
            Injekt.get()
        } catch (_: Exception) {
            return@withContext mergedId
        }

        val sources = sourceManager.getOnlineSources()
            .filterIsInstance<CatalogueSource>()
            .sortedByDescending { it.lang.equals("en", ignoreCase = true) }
            .take(35)

        if (sources.isEmpty()) return@withContext mergedId

        // Search with original title (better results)
        val searchResults = coroutineScope {
            sources.map { source ->
                async {
                    searchInSource(source, title)
                }
            }.awaitAll()
        }

        val allMatches = searchResults.flatten()
            .distinctBy { "${it.sourceId}_${it.url}" }

        // Very tolerant filter – we want links
        val accepted = allMatches
            .filter { match ->
                val cleanSearch = clean(title)
                val cleanResult = clean(match.title)
                cleanResult.contains(cleanSearch.take(6)) ||
                    cleanSearch.contains(cleanResult.take(6)) ||
                    cleanResult.split(" ").any { it.length > 3 && cleanSearch.contains(it) }
            }
            .sortedByDescending { match ->
                var score = 0
                if (match.lang.equals("en", ignoreCase = true)) score += 50
                if (clean(match.title).contains(clean(title))) score += 30
                score
            }
            .take(20)

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
            withTimeoutOrNull(4500) {
                val result = source.getSearchManga(1, title, FilterList())
                result.mangas.take(5).map { manga ->
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

    private fun clean(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private data class SearchMatch(
        val sourceId: Long,
        val url: String,
        val title: String,
        val lang: String,
    )
}
