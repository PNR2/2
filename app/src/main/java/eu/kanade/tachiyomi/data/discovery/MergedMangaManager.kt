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
        // 1. Create merged entry
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

        // 2. Get sources
        val sourceManager: SourceManager = try {
            Injekt.get()
        } catch (_: Exception) {
            return@withContext mergedId
        }

        val sources = sourceManager.getOnlineSources()
            .filterIsInstance<CatalogueSource>()
            .sortedByDescending { it.lang.equals("en", ignoreCase = true) }
            .take(30) // search more sources

        if (sources.isEmpty()) return@withContext mergedId

        val cleanTitle = cleanTitle(title)

        // 3. Search
        val searchResults = coroutineScope {
            sources.map { source ->
                async {
                    searchInSource(source, title) // use original title too
                }
            }.awaitAll()
        }

        // 4. Accept more matches (less strict)
        val goodMatches = searchResults.flatten()
            .filter { isAcceptableMatch(cleanTitle, it.title) }
            .distinctBy { "${it.sourceId}_${it.url}" }
            .sortedByDescending { match ->
                var score = 0
                if (match.lang.equals("en", ignoreCase = true)) score += 30
                if (cleanTitle(match.title).contains(cleanTitle)) score += 20
                if (cleanTitle == cleanTitle(match.title)) score += 25
                score
            }
            .take(15)

        // 5. Save references
        goodMatches.forEach { match ->
            repository.addReference(
                MergedMangaReference(
                    mergedId = mergedId,
                    sourceId = match.sourceId,
                    mangaUrl = match.url,
                    mangaTitle = match.title,
                    chapterCount = 0,
                    isInfoSource = false,
                    priority = if (match.lang.equals("en", ignoreCase = true)) 10 else 4,
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
            withTimeoutOrNull(4000) {
                val result = source.getSearchManga(1, title, FilterList())
                result.mangas.take(4).map { manga ->
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
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isAcceptableMatch(searchTitle: String, resultTitle: String): Boolean {
        val cleanResult = cleanTitle(resultTitle)
        if (cleanResult.length < 3) return false

        // Very loose matching so we get more links
        if (cleanResult.contains(searchTitle)) return true
        if (searchTitle.contains(cleanResult)) return true

        // Check first few words
        val searchWords = searchTitle.split(" ").filter { it.length > 2 }
        val resultWords = cleanResult.split(" ").filter { it.length > 2 }
        val common = searchWords.count { word -> resultWords.any { it.contains(word) || word.contains(it) } }
        return common >= 1
    }

    private data class SearchMatch(
        val sourceId: Long,
        val url: String,
        val title: String,
        val lang: String,
    )
}
