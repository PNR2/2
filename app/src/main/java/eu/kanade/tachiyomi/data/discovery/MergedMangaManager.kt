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
import java.util.Locale

class MergedMangaManager(
    private val sourceManager: SourceManager,
) {

    private val repository = MergedMangaRepository()

    data class CohesiveSearchOutcome(
        val primaryId: Long,
        val primaryTitle: String,
        val similar: List<SimilarItem>,
    )

    data class SimilarItem(
        val id: Long,
        val title: String,
        val coverUrl: String?,
    )

    suspend fun searchCohesive(
        query: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
    ): CohesiveSearchOutcome = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) {
            return@withContext CohesiveSearchOutcome(-1, q, emptyList())
        }

        val queries = buildSearchQueries(q)
        val hits = searchAllSources(queries)

        val ranked = hits
            .map { hit ->
                hit.copy(score = scoreMatch(q, hit.manga, hit.sourceName, hit.lang))
            }
            .filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }

        val clusters = ranked
            .groupBy { normalizeTitle(it.manga.title) }
            .filter { (key, _) -> key.length >= 3 }
            .map { (key, list) ->
                val best = list.maxByOrNull { it.score }!!
                TitleCluster(
                    key = key,
                    displayTitle = best.manga.title,
                    hits = list.sortedByDescending { it.score },
                    bestScore = best.score,
                    bestCover = list.mapNotNull { it.manga.thumbnail_url }
                        .firstOrNull { it.isNotBlank() },
                    queryScore = scoreMatch(
                        q,
                        SManga.create().apply { title = best.manga.title },
                        best.sourceName,
                        best.lang,
                    ),
                )
            }
            .sortedWith(
                compareByDescending<TitleCluster> { it.queryScore }
                    .thenByDescending { it.bestScore },
            )

        if (clusters.isEmpty()) {
            val id = repository.createOrUpdateMergedManga(
                title = q,
                coverUrl = coverUrl,
                synopsis = synopsis,
                author = author,
                malId = malId,
            )
            return@withContext CohesiveSearchOutcome(id, q, emptyList())
        }

        // Primary must be a strong match to the user query
        val primaryCluster = clusters.firstOrNull { it.queryScore >= PRIMARY_MIN_QUERY_SCORE }
            ?: clusters.first()

        val primaryId = saveClusterAsMerged(
            cluster = primaryCluster,
            fallbackTitle = q,
            coverUrl = coverUrl ?: primaryCluster.bestCover,
            synopsis = synopsis,
            author = author,
            malId = malId,
            linkSources = true,
        )

        val similar = clusters
            .asSequence()
            .filter { it.key != primaryCluster.key }
            .filter { it.queryScore >= SIMILAR_MIN_SCORE }
            // Avoid near-duplicates of the primary title
            .filter { !isNearDuplicateTitle(primaryCluster.key, it.key) }
            .take(MAX_SIMILAR)
            .map { cluster ->
                val id = saveClusterAsMerged(
                    cluster = cluster,
                    fallbackTitle = cluster.displayTitle,
                    coverUrl = cluster.bestCover,
                    synopsis = null,
                    author = null,
                    malId = null,
                    linkSources = false,
                )
                SimilarItem(
                    id = id,
                    title = cluster.displayTitle,
                    coverUrl = cluster.bestCover,
                )
            }
            .toList()

        CohesiveSearchOutcome(
            primaryId = primaryId,
            primaryTitle = primaryCluster.displayTitle,
            similar = similar,
        )
    }

    suspend fun createOrUpdateMergedManga(
        title: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
        linkSources: Boolean = true,
    ): Long = withContext(Dispatchers.IO) {
        val outcome = searchCohesive(
            query = title,
            coverUrl = coverUrl,
            synopsis = synopsis,
            author = author,
            malId = malId,
        )
        outcome.primaryId
    }

    private fun saveClusterAsMerged(
        cluster: TitleCluster,
        fallbackTitle: String,
        coverUrl: String?,
        synopsis: String?,
        author: String?,
        malId: Long?,
        linkSources: Boolean,
    ): Long {
        val bestHit = cluster.hits.firstOrNull()
        val resolvedCover = coverUrl
            ?: cluster.bestCover
            ?: bestHit?.manga?.thumbnail_url

        val mergedId = repository.createOrUpdateMergedManga(
            title = cluster.displayTitle.ifBlank { fallbackTitle }.trim(),
            coverUrl = resolvedCover,
            synopsis = synopsis ?: bestHit?.manga?.description,
            author = author ?: bestHit?.manga?.author,
            malId = malId,
        )

        if (!linkSources) return mergedId

        repository.clearReferences(mergedId)

        val bySource = cluster.hits
            .groupBy { it.sourceId }
            .mapValues { (_, list) -> list.maxByOrNull { it.score }!! }
            .values
            .sortedByDescending { it.score }
            .take(MAX_SOURCES)

        bySource.forEachIndexed { index, hit ->
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

        return mergedId
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
            .take(3)
    }

    private suspend fun searchAllSources(queries: List<String>): List<SourceHit> = coroutineScope {
        val sources = sourceManager.getOnlineSources()
            .filterIsInstance<CatalogueSource>()
            .filter { it.lang.isNotBlank() }

        if (sources.isEmpty() || queries.isEmpty()) return@coroutineScope emptyList()

        val semaphore = Semaphore(6)
        sources.map { source ->
            async {
                semaphore.withPermit { searchOneSource(source, queries) }
            }
        }.awaitAll().flatten()
    }

    private suspend fun searchOneSource(
        source: CatalogueSource,
        queries: List<String>,
    ): List<SourceHit> {
        val found = LinkedHashMap<String, SourceHit>()
        for (query in queries) {
            try {
                val page = withTimeoutOrNull(10_000) {
                    source.getSearchManga(1, query, FilterList())
                } ?: continue
                page.mangas.take(5).forEach { manga ->
                    if (!found.containsKey(manga.url)) {
                        found[manga.url] = SourceHit(
                            sourceId = source.id,
                            sourceName = source.name,
                            lang = source.lang,
                            manga = manga,
                            score = 0,
                        )
                    }
                }
                if (found.size >= 6) break
            } catch (_: Exception) {
            }
        }
        return found.values.toList()
    }

    private fun scoreMatch(
        userTitle: String,
        manga: SManga,
        sourceName: String,
        lang: String,
    ): Int {
        val user = normalizeTitle(userTitle)
        val candidate = normalizeTitle(manga.title)
        if (user.isEmpty() || candidate.isEmpty()) return 0

        var score = 0

        when {
            user == candidate -> score += 100
            candidate.startsWith(user) || user.startsWith(candidate) -> score += 90
            candidate.contains(user) || user.contains(candidate) -> score += 70
            else -> score += (tokenSimilarity(user, candidate) * 55).toInt()
        }

        val userTokens = user.split(' ').filter { it.length > 1 }
        val candTokens = candidate.split(' ').filter { it.length > 1 }
        if (userTokens.isNotEmpty()) {
            val covered = userTokens.count { ut -> candTokens.any { it == ut || it.startsWith(ut) } }
            val ratio = covered.toFloat() / userTokens.size
            score += (ratio * 25).toInt()
            // Penalize titles that only share a short token (e.g. "man", "eat")
            if (covered == 1 && userTokens.size >= 2 && userTokens.any { it.length <= 3 }) {
                score -= 25
            }
        }

        val userCore = extractCoreTitle(userTitle)
        val candCore = extractCoreTitle(manga.title)
        if (userCore.length >= 4 && candCore.length >= 4) {
            when {
                userCore == candCore -> score += 20
                candCore.contains(userCore) || userCore.contains(candCore) -> score += 10
            }
        }

        // Prefer English sources for ranking
        val langNorm = lang.lowercase(Locale.ROOT)
        when {
            langNorm == "en" || langNorm == "gb" || langNorm.startsWith("en") -> score += 12
            langNorm == "ja" || langNorm == "jp" -> score += 2
            else -> score -= 3
        }

        // Soft penalty for common adult/noise markers in title
        val noise = listOf("hentai", "r18", "ntr", "netorare", "cg set", "doujin")
        if (noise.any { candidate.contains(it) } && !user.contains(noise.first { candidate.contains(it) })) {
            score -= 35
        }

        // Length mismatch penalty (very long unrelated titles)
        if (candidate.length > user.length * 2.5 && score < 95) {
            score -= 10
        }

        return score.coerceIn(0, 100)
    }

    private fun isNearDuplicateTitle(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.contains(b) || b.contains(a)) {
            val shorter = minOf(a.length, b.length).toFloat()
            val longer = maxOf(a.length, b.length).toFloat()
            if (shorter / longer >= 0.85f) return true
        }
        return tokenSimilarity(a, b) >= 0.9f
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
        s = s.replace(Regex("\\[[^\\]]*\\]"), " ")
        s = s.replace(Regex("\\([^)]*\\)"), " ")
        s = s.replace(Regex("【[^】]*】"), " ")
        s = s.replace(Regex("（[^）]*）"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun extractCoreTitle(input: String): String {
        var s = stripBrackets(input)
        s = s.replace(Regex("^[^\\-–|]+[\\-–|]\\s*"), "")
        s = normalizeTitle(s)
        val tokens = s.split(' ').filter { it.length > 2 }
        return tokens.joinToString(" ").ifBlank { normalizeTitle(input) }
    }

    private data class SourceHit(
        val sourceId: Long,
        val sourceName: String,
        val lang: String,
        val manga: SManga,
        val score: Int,
    )

    private data class TitleCluster(
        val key: String,
        val displayTitle: String,
        val hits: List<SourceHit>,
        val bestScore: Int,
        val bestCover: String?,
        val queryScore: Int,
    )

    companion object {
        private const val MIN_SCORE = 45
        private const val PRIMARY_MIN_QUERY_SCORE = 70
        private const val SIMILAR_MIN_SCORE = 55
        private const val MAX_SOURCES = 25
        private const val MAX_SIMILAR = 6
    }
}
