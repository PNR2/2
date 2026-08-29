@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.min

data class AutoLinkResult(
    val sourceId: Long,
    val sourceName: String,
    val manga: SManga,
    val score: Int, // higher = better match
)

class AutoLinkEngine {

    private val sourceManager: SourceManager = Injekt.get()

    /**
     * Search all installed English catalogue sources for the given MAL item
     * and return ranked results (best first).
     */
    suspend fun findMatches(item: MalDiscoveryItem): List<AutoLinkResult> {
        return withContext(Dispatchers.IO) {
            val allSources = sourceManager.getAll()
            val sources = allSources
                .filterIsInstance<CatalogueSource>()
                .filter { it.lang.equals("en", ignoreCase = true) }

            if (sources.isEmpty()) return@withContext emptyList()

            val searchTitles = buildList {
                add(item.title)
                addAll(item.alternativeTitles)
            }.distinct()
                .filter { it.isNotBlank() }
                .take(3) // limit to avoid too many requests

            coroutineScope {
                sources.map { source ->
                    async {
                        searchOneSource(source, searchTitles, item)
                    }
                }.awaitAll()
                    .flatten()
                    .sortedByDescending { it.score }
                    .distinctBy { "\( {it.sourceId}- \){it.manga.url}" }
                    .take(12) // show max 12 best matches
            }
        }
    }

    private suspend fun searchOneSource(
        source: CatalogueSource,
        titles: List<String>,
        malItem: MalDiscoveryItem,
    ): List<AutoLinkResult> {
        val results = mutableListOf<AutoLinkResult>()

        for (title in titles) {
            try {
                val page = source.getSearchManga(1, title, FilterList())
                page.mangas.forEach { manga ->
                    val score = calculateScore(manga, malItem, title)
                    if (score >= 30) { // minimum quality threshold
                        results.add(
                            AutoLinkResult(
                                sourceId = source.id,
                                sourceName = source.name,
                                manga = manga,
                                score = score,
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                // ignore failed sources
            }
        }
        return results
    }

    /**
     * Simple ranking:
     * - Title similarity (most important)
     * - Prefer exact / very close titles
     */
    private fun calculateScore(
        manga: SManga,
        malItem: MalDiscoveryItem,
        searchedTitle: String,
    ): Int {
        var score = 0

        val mangaTitle = manga.title.trim().lowercase()
        val malTitle = malItem.title.trim().lowercase()
        val searched = searchedTitle.trim().lowercase()

        // Exact match
        if (mangaTitle == malTitle || mangaTitle == searched) {
            score += 100
        } else if (mangaTitle.contains(malTitle) || malTitle.contains(mangaTitle)) {
            score += 70
        } else if (mangaTitle.contains(searched) || searched.contains(mangaTitle)) {
            score += 55
        } else {
            // simple word overlap
            val malWords = malTitle.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
            val
