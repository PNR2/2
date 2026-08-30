package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
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

    private val sourceManager: SourceManager = Injekt.get()
    private val repository = MergedMangaRepository()

    /**
     * Main function.
     * Call this when you want to auto-link a manga (from Seasonal or anywhere).
     * It searches all extensions, creates a merged entry, and links the best matches.
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

        // 2. Get all online catalogue sources
        val sources = sourceManager.getOnlineSources()
            .filterIsInstance<CatalogueSource>()

        if (sources.isEmpty()) {
            return@withContext mergedId
        }

        // 3. Search all sources in parallel (with timeout)
        val searchResults = coroutineScope {
            sources.map { source ->
                async {
                    searchInSource(source, title)
                }
            }.awaitAll()
        }

        // 4. Flatten and add good matches as references
        searchResults.flatten().forEach { match ->
            repository.addReference(
                MergedMangaReference(
                    mergedId = mergedId,
                    sourceId = match.sourceId,
                    mangaUrl = match.url,
                    mangaTitle = match.title,
                    chapterCount = 0, // can be updated later
                    isInfoSource = false,
                    priority = if (match.lang.equals("en", true)) 10 else 5,
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
                result.mangas.take(3).map { manga ->
                    SearchMatch(
                        sourceId = source.id,
                        url = manga.url,
                        title = manga.title,
                        lang = source.lang,
                    )
                }
            } ?: emptyList()
        } catch (e: Exception) {
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
