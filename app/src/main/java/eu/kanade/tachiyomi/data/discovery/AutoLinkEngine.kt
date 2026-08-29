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
    val score: Int,
)

class AutoLinkEngine {

    /**
     * Search all installed English catalogue sources for the given MAL item
     * and return ranked results (best first).
     */
    suspend fun findMatches(item: MalDiscoveryItem): List<AutoLinkResult> {
        return withContext(Dispatchers.IO) {
            val sourceManager = try {
                Injekt.get<SourceManager>()
            } catch (e: Exception) {
                return@withContext emptyList()
            }

            val allSources = try {
                sourceManager.getAll()
            } catch (e: Exception) {
                return@withContext emptyList()
            }

            val sources = allSources
                .filterIsInstance<CatalogueSource>()
                .filter { it.lang.equals("en", ignoreCase = true) }

            if (sources.isEmpty()) {
                return@withContext emptyList()
            }

            val searchTitles = buildList {
                add(item.title)
                addAll(item.alternativeTitles)
            }.distinct()
                .filter { it.isNotBlank() }
                .take(3)

            coroutineScope {
                val deferred = sources.map { source ->
                    async {
                        searchOneSource(source, searchTitles, item)
                    }
                }
                deferred.awaitAll()
                    .flatten()
                    .sortedByDescending { it.score }
                    .distinctBy { result ->
                        result.sourceId.toString() + "-" + result.manga.url
                    }
                    .take(12)
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
                    if (score >= 30) {
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

    private fun calculateScore(
        manga: SManga,
        malItem: MalDiscoveryItem,
        searchedTitle: String,
    ): Int {
        var score = 0

        val mangaTitle = manga.title.trim().lowercase()
        val malTitle = malItem.title.trim().lowercase()
        val searched = searchedTitle.trim().lowercase()

        if (mangaTitle == malTitle || mangaTitle == searched) {
            score += 100
        } else if (mangaTitle.contains(malTitle) || malTitle.contains(mangaTitle)) {
            score += 70
        } else if (mangaTitle.contains(searched) || searched.contains(mangaTitle)) {
            score += 55
        } else {
            val malWords = malTitle.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
            val mangaWords = mangaTitle.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
            val common = malWords.intersect(mangaWords).size
            score += common * 12
        }

        if (!manga.description.isNullOrBlank()) {
            score += 8
        }
        if (!manga.author.isNullOrBlank()) {
            score += 5
        }
        if (mangaTitle.length < 4) {
            score -= 20
        }

        return min(score, 150)
    }
}
