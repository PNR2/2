@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MergedMangaManager {

    private val repository = MergedMangaRepository()
    private val sourceManager: SourceManager = Injekt.get()

    /**
     * Search ALL catalogue sources, link best matches, avoid duplicates.
     * Returns merged manga id.
     */
    suspend fun createOrUpdateMergedManga(
        title: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        val queries = buildSearchQueries(title)
        val hits = searchAllSources(queries)

        val ranked = hits
            .map { it.copy(score = scoreMatch(title, it.manga, it.sourceName)) }
            .filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }

        // Prefer diversity: best hit per source, then fill top overall
        val bySource = ranked
            .groupBy { it.sourceId }
            .mapValues { (_, list) -> list.maxByOrNull { it.score }!! }
            .values
            .sortedByDescending { it.score }

        val selected = LinkedHashSet<SourceHit>()
        bySource.take(MAX_SOURCES).forEach { selected.add(it) }
        ranked.take(MAX_SOURCES * 2).forEach {
            if (selected.size >= MAX_SOURCES) return@forEach
            selected.add(it)
        }

        val bestCover = selected.mapNotNull { it.manga.thumbnail_url }.firstOrNull()
        val bestAuthor = selected.mapNotNull { it.manga.author }.firstOrNull { it.isNotBlank() }
        val bestSynopsis = selected.mapNotNull { it.manga.description }.firstOrNull { it.isNotBlank() }

        val mergedId = repository.createOrUpdateMergedManga(
            title = title.trim(),
            coverUrl = coverUrl ?: bestCover,
            synopsis = synopsis ?: bestSynopsis,
            author = author ?: bestAuthor,
            malId = malId,
        )

        // Replace references with fresh search results
        repository.clearReferences(mergedId)

        selected.forEachIndexed { index, hit ->
            repository.addReference(
                MergedMangaReference(
                    mergedId = mergedId,
                    sourceId = hit.sourceId,
                    mangaUrl = hit.manga.url,
                    mangaTitle = hit.manga.title,
                    chapterCount = 0,
                    isInfoSource = index == 0,
                    priority = hit.score,
                    sourceName = hit.sourceName,
                ),
            )
        }

        mergedId
    }

    private fun buildSearchQueries(raw: String): List<String> {
        val base = raw.trim()
        if (base.isEmpty()) return emptyList()

        val cleaned = normalizeTitle(base)
        val noBrackets = stripBrackets(base)
        val core = extractCoreTitle(base)

        return listOf(base, cleaned, noBrackets, core)
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(4)
    }

    private suspend fun searchAllSources(queries: List<String>): List<SourceHit> = coroutineScope {
        val sources = sourceManager.getCatalogueSources()
            .filterIsInstance<CatalogueSource>()
            .filter { it.lang.isNotBlank() }

        if (sources.isEmpty() || queries.isEmpty()) return@coroutineScope emptyList()

        // Limit parallel network calls so we do not kill the device / rate-limit
        val semaphore = Semaphore(permits = 8)

        sources.map { source ->
            async {
                semaphore.withPermit {
                    searchOneSource(source, queries)
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun searchOneSource(
        source: CatalogueSource,
        queries: List<String>,
    ): List<SourceHit> {
        val found = LinkedHashMap<String, SourceHit>() // key = manga.url

        for (query in queries) {
            try {
                val page = withTimeoutOrNull(12_000) {
                    source.getSearchManga(1, query, FilterList())
                } ?: continue

                page.mangas.forEach { manga ->
                    val key = manga.url
                    if (!found.containsKey(key)) {
                        found[key] = SourceHit(
                            sourceId = source.id,
                            sourceName = source.name,
                            manga = manga,
                            score = 0, // scored later
                        )
                    }
                }

                // Enough results from this source
                if (found.size >= 8) break
            } catch (_: Exception) {
                // Source down / blocked — skip
            }
        }

        return found.values.toList()
    }

    private fun scoreMatch(userTitle: String, manga: SManga, sourceName: String): Int {
        val user = normalizeTitle(userTitle)
        val candidate = normalizeTitle(manga.title)
        if (user.isEmpty() || candidate.isEmpty()) return 0

        var score = 0

        when {
            user == candidate -> score += 100
            candidate.contains(user) || user.contains(candidate) -> score += 80
            else -> {
                val sim = tokenSimilarity(user, candidate)
                score += (sim * 70).toInt()
            }
        }

        // Core title without author / tags
        val userCore = extractCoreTitle(userTitle)
        val candCore = extractCoreTitle(manga.title)
        if (userCore.length >= 4 && candCore.length >= 4) {
            when {
                userCore == candCore -> score += 25
                candCore.contains(userCore) || userCore.contains(candCore) -> score += 15
            }
        }

        // Author boost
        val author = manga.author?.let { normalizeTitle(it) }.orEmpty()
        if (author.isNotEmpty() && normalizeTitle(userTitle).contains(author)) {
            score += 10
        }

        // Light penalty for very noisy titles vs short query
        if (candidate.length > user.length * 3 && score < 90) {
            score -= 5
        }

        return score.coerceIn(0, 100)
    }

    private fun tokenSimilarity(a: String, b: String): Float {
        val ta = a.split(' ').filter { it.length > 1 }.toSet()
        val tb = b.split(' ').filter { it.length > 1 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val inter = ta.intersect(tb).size.toFloat()
        val union = ta.union(tb).size.toFloat()
        return if (union == 0f) 0f else inter / union
    }

    private fun normalizeTitle(input: String): String {
        var s = input.lowercase(Locale.ROOT)
        s = stripBrackets(s)
        s = s.replace(Regex("[^a-z0-9\\s]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun stripBrackets(input: String): String {
        var s = input
        // [English], (Chinese), 【】 etc.
        s = s.replace(Regex("\\[[^\\]]*\\]"), " ")
        s = s.replace(Regex("\\([^)]*\\)"), " ")
        s = s.replace(Regex("【[^】]*】"), " ")
        s = s.replace(Regex("（[^）]*）"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun extractCoreTitle(input: String): String {
        var s = stripBrackets(input)
        // Remove common "Author - Title" or "[Author] Title"
        s = s.replace(Regex("^[^\\-–|]+[\\-–|]\\s*"), "")
        s = normalizeTitle(s)
        // Drop very short noise tokens
        val tokens = s.split(' ').filter { it.length > 2 }
        return tokens.joinToString(" ").ifBlank { normalizeTitle(input) }
    }

    private data class SourceHit(
        val sourceId: Long,
        val sourceName: String,
        val manga: SManga,
        val score: Int,
    )

    companion object {
        private const val MIN_SCORE = 40
        private const val MAX_SOURCES = 25
    }
}
