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

    private val repository = MergedMangaRepository()

    /**
     * Creates a merged manga entry, links the best sources, and tries to collect chapters.
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
            .sortedByDescending { it.lang.equals("en", ignoreCase = true) }
            .take(20)

        if (sources.isEmpty()) {
            return@withContext mergedId
        }

        val cleanTitle = cleanTitle(title)

        // 3. Search sources
        val searchResults = coroutineScope {
            sources.map { source ->
                async {
                    searchInSource(source, cleanTitle)
                }
            }.awaitAll()
        }

        // 4. Filter good matches and save references
        val goodMatches = searchResults.flatten()
            .filter { isGoodMatch(cleanTitle, it.title) }
            .sortedByDescending { match ->
                var score = 0
                if (match.lang.equals("en", ignoreCase = true)) score += 20
                if (cleanTitle == cleanTitle(match.title)) score += 15
                score
            }
            .take(10)

        goodMatches.forEach { match ->
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

        // 5. Try to fetch chapters from the best few matches
        goodMatches.take(4).forEach { match ->
            try {
                val source = sources.find { it.id == match.sourceId } ?: return@forEach
                val chapters = fetchChaptersFromSource(source, match.url, match.lang)

                if (chapters.isNotEmpty()) {
                    // Save chapter count on the reference
                    repository.updateReferenceChapterCount(
                        mergedId = mergedId,
                        sourceId = match.sourceId,
                        mangaUrl = match.url,
                        count = chapters.size,
                    )

                    // Save the actual chapters
                    val mergedChapters = chapters.map { ch ->
                        MergedChapter(
                            mergedId = mergedId,
                            sourceId = match.sourceId,
                            url = ch.url,
                            name = ch.name,
                            chapterNumber = ch.chapterNumber,
                            language = match.lang,
                            dateUpload = ch.dateUpload,
                        )
                    }
                    repository.addChapters(mergedChapters)
                }
            } catch (_: Exception) {
                // ignore individual source failures
            }
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

    private suspend fun fetchChaptersFromSource(
        source: CatalogueSource,
        mangaUrl: String,
        language: String,
    ): List<ChapterInfo> {
        return try {
            withTimeoutOrNull(5000) {
                val sManga = SManga.create().apply {
                    url = mangaUrl
                }
                val details = source.getMangaDetails(sManga)
                val chapterList = source.getChapterList(details)

                chapterList.map { ch ->
                    ChapterInfo(
                        url = ch.url,
                        name = ch.name,
                        chapterNumber = ch.chapter_number,
                        dateUpload = ch.date_upload,
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
        if (cleanResult.isBlank() || cleanResult.length < 3) return false
        if (cleanResult == searchTitle) return true
        if (cleanResult.contains(searchTitle) || searchTitle.contains(cleanResult)) return true
        return false
    }

    private data class SearchMatch(
        val sourceId: Long,
        val url: String,
        val title: String,
        val lang: String,
    )

    private data class ChapterInfo(
        val url: String,
        val name: String,
        val chapterNumber: Float,
        val dateUpload: Long,
    )
}
