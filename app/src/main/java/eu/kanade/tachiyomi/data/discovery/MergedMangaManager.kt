@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

    sealed class SearchEvent {
        data class Progress(
            val done: Int,
            val total: Int,
            val message: String,
        ) : SearchEvent()

        data class PrimaryReady(
            val id: Long,
            val title: String,
            val coverUrl: String?,
            val sourceCount: Int,
        ) : SearchEvent()

        data class SourcesUpdated(
            val id: Long,
            val sourceCount: Int,
        ) : SearchEvent()

        data class SimilarReady(
            val items: List<SimilarItem>,
        ) : SearchEvent()

        data class Finished(
            val outcome: CohesiveSearchOutcome,
        ) : SearchEvent()

        data class Failed(
            val message: String,
        ) : SearchEvent()
    }

    fun searchCohesiveFlow(
        query: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
    ): Flow<SearchEvent> = flow {
        val q = query.trim()
        if (q.isEmpty()) {
            emit(SearchEvent.Failed("Empty query"))
            return@flow
        }

        val key = normalizeTitle(q)
        runningJobs[key]?.cancel()

        val parentJob = SupervisorJob()
        runningJobs[key] = parentJob

        try {
            val sources = sourceManager.getOnlineSources()
                .filterIsInstance<CatalogueSource>()
                .filter { it.lang.isNotBlank() }
                .sortedByDescending { isEnglishLang(it.lang) }

            if (sources.isEmpty()) {
                val id = repository.createOrUpdateMergedManga(
                    title = q,
                    coverUrl = coverUrl,
                    synopsis = synopsis,
                    author = author,
                    malId = malId,
                )
                val outcome = CohesiveSearchOutcome(id, q, emptyList())
                emit(SearchEvent.PrimaryReady(id, q, coverUrl, 0))
                emit(SearchEvent.Finished(outcome))
                return@flow
            }

            val total = sources.size
            emit(SearchEvent.Progress(0, total, "Searching extensions…"))

            val queries = buildSearchQueries(q)
            val allHits = mutableListOf<SourceHit>()
            val hitMutex = Mutex()

            var primaryId = -1L
            var primaryKey: String? = null
            var primaryTitle = q
            var primaryCover = coverUrl
            var linkedCount = 0

            val semaphore = Semaphore(10)
            var completed = 0

            coroutineScope {
                val jobs = sources.map { source ->
                    async {
                        val hits = semaphore.withPermit {
                            searchOneSource(source, queries)
                        }
                        hitMutex.withLock {
                            allHits.addAll(hits)
                            completed++
                        }
                        hits to source.name
                    }
                }

                jobs.forEach { deferred ->
                    val (hits, sourceName) = deferred.await()
                    val done = hitMutex.withLock { completed }

                    emit(
                        SearchEvent.Progress(
                            done = done,
                            total = total,
                            message = "Checked $done/$total · $sourceName",
                        ),
                    )

                    if (hits.isEmpty()) return@forEach

                    val scored = hits.map { hit ->
                        hit.copy(score = scoreMatch(q, hit.manga, hit.sourceName, hit.lang))
                    }.filter { it.score >= MIN_SCORE }

                    if (scored.isEmpty()) return@forEach

                    if (primaryId <= 0) {
                        val best = scored.maxByOrNull { it.score }!!
                        val qScore = scoreMatch(
                            q,
                            SManga.create().apply { title = best.manga.title },
                            best.sourceName,
                            best.lang,
                        )
                        val nsfw = isNsfwTitle(best.manga.title) && !isNsfwTitle(q)
                        if (qScore >= PRIMARY_EARLY_SCORE && !nsfw) {
                            primaryKey = normalizeTitle(best.manga.title)
                            primaryTitle = best.manga.title
                            primaryCover = best.manga.thumbnail_url ?: coverUrl
                            primaryId = repository.createOrUpdateMergedManga(
                                title = primaryTitle,
                                coverUrl = primaryCover,
                                synopsis = synopsis ?: best.manga.description,
                                author = author ?: best.manga.author,
                                malId = malId,
                            )
                            repository.clearReferences(primaryId)
                            addHitsAsReferences(primaryId, scored)
                            linkedCount = repository.getReferences(primaryId).size
                            emit(
                                SearchEvent.PrimaryReady(
                                    id = primaryId,
                                    title = primaryTitle,
                                    coverUrl = primaryCover,
                                    sourceCount = linkedCount,
                                ),
                            )
                        }
                    } else if (primaryKey != null) {
                        val matching = scored.filter {
                            normalizeTitle(it.manga.title) == primaryKey ||
                                isNearDuplicateTitle(
                                    normalizeTitle(it.manga.title),
                                    primaryKey!!,
                                )
                        }
                        if (matching.isNotEmpty()) {
                            addHitsAsReferences(primaryId, matching, clearFirst = false)
                            linkedCount = repository.getReferences(primaryId).size
                            if (primaryCover.isNullOrBlank()) {
                                primaryCover = matching.mapNotNull { it.manga.thumbnail_url }
                                    .firstOrNull { it.isNotBlank() }
                                if (!primaryCover.isNullOrBlank()) {
                                    repository.createOrUpdateMergedManga(
                                        title = primaryTitle,
                                        coverUrl = primaryCover,
                                        synopsis = synopsis,
                                        author = author,
                                        malId = malId,
                                    )
                                }
                            }
                            emit(SearchEvent.SourcesUpdated(primaryId, linkedCount))
                        }
                    }
                }
            }

            val ranked = allHits
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
                        isNsfw = isNsfwTitle(best.manga.title) && !isNsfwTitle(q),
                    )
                }
                .sortedWith(
                    compareByDescending<TitleCluster> { it.queryScore }
                        .thenByDescending { it.bestScore },
                )

            if (primaryId <= 0) {
                val primaryCluster = clusters
                    .firstOrNull { !it.isNsfw && it.queryScore >= PRIMARY_MIN_QUERY_SCORE }
                    ?: clusters.firstOrNull { !it.isNsfw }
                    ?: clusters.firstOrNull()

                if (primaryCluster != null) {
                    primaryId = saveClusterAsMerged(
                        cluster = primaryCluster,
                        fallbackTitle = q,
                        coverUrl = coverUrl ?: primaryCluster.bestCover,
                        synopsis = synopsis,
                        author = author,
                        malId = malId,
                        linkSources = true,
                    )
                    primaryTitle = primaryCluster.displayTitle
                    primaryCover = coverUrl ?: primaryCluster.bestCover
                    linkedCount = repository.getReferences(primaryId).size
                    emit(
                        SearchEvent.PrimaryReady(
                            id = primaryId,
                            title = primaryTitle,
                            coverUrl = primaryCover,
                            sourceCount = linkedCount,
                        ),
                    )
                } else {
                    primaryId = repository.createOrUpdateMergedManga(
                        title = q,
                        coverUrl = coverUrl,
                        synopsis = synopsis,
                        author = author,
                        malId = malId,
                    )
                    emit(SearchEvent.PrimaryReady(primaryId, q, coverUrl, 0))
                }
            } else {
                val keyOfPrimary = primaryKey ?: normalizeTitle(primaryTitle)
                val fullPrimary = clusters.firstOrNull { it.key == keyOfPrimary }
                if (fullPrimary != null) {
                    primaryId = saveClusterAsMerged(
                        cluster = fullPrimary,
                        fallbackTitle = q,
                        coverUrl = coverUrl ?: fullPrimary.bestCover ?: primaryCover,
                        synopsis = synopsis,
                        author = author,
                        malId = malId,
                        linkSources = true,
                    )
                    linkedCount = repository.getReferences(primaryId).size
                    emit(SearchEvent.SourcesUpdated(primaryId, linkedCount))
                }
            }

            val primaryNorm = normalizeTitle(primaryTitle)
            val similar = clusters
                .asSequence()
                .filter { it.key != primaryNorm }
                .filter { it.queryScore >= SIMILAR_MIN_SCORE || it.isNsfw }
                .filter { !isNearDuplicateTitle(primaryNorm, it.key) }
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
                    SimilarItem(id, cluster.displayTitle, cluster.bestCover)
                }
                .toList()

            if (similar.isNotEmpty()) {
                emit(SearchEvent.SimilarReady(similar))
            }

            val outcome = CohesiveSearchOutcome(primaryId, primaryTitle, similar)
            emit(SearchEvent.Finished(outcome))
        } catch (e: Exception) {
            emit(SearchEvent.Failed(e.message ?: "Search failed"))
        } finally {
            runningJobs.remove(key, parentJob)
            parentJob.complete()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun searchCohesive(
        query: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
    ): CohesiveSearchOutcome {
        var outcome = CohesiveSearchOutcome(-1, query, emptyList())
        searchCohesiveFlow(query, coverUrl, synopsis, author, malId).collect { event ->
            if (event is SearchEvent.Finished) {
                outcome = event.outcome
            }
        }
        return outcome
    }

    suspend fun createOrUpdateMergedManga(
        title: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
        linkSources: Boolean = true,
    ): Long {
        return searchCohesive(title, coverUrl, synopsis, author, malId).primaryId
    }

    private fun addHitsAsReferences(
        mergedId: Long,
        hits: List<SourceHit>,
        clearFirst: Boolean = false,
    ) {
        if (clearFirst) {
            repository.clearReferences(mergedId)
        }
        val existing = repository.getReferences(mergedId)
            .map { "${it.sourceId}_${it.mangaUrl}" }
            .toHashSet()

        hits.groupBy { it.sourceId }
            .mapValues { (_, list) -> list.maxByOrNull { it.score }!! }
            .values
            .sortedByDescending { it.score }
            .forEachIndexed { index, hit ->
                val refKey = "${hit.sourceId}_${hit.manga.url}"
                if (refKey in existing) return@forEachIndexed
                if (existing.size >= MAX_SOURCES) return@forEachIndexed
                repository.addReference(
                    MergedMangaReference(
                        mergedId = mergedId,
                        sourceId = hit.sourceId,
                        mangaUrl = hit.manga.url,
                        mangaTitle = hit.manga.title,
                        chapterCount = 0,
                        isInfoSource = existing.isEmpty() && index == 0,
                        priority = hit.score,
                        sourceName = hit.sourceName,
                    ),
                )
                existing.add(refKey)
            }
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
        addHitsAsReferences(mergedId, cluster.hits, clearFirst = false)
        return mergedId
    }

    private fun buildSearchQueries(raw: String): List<String> {
        val base = raw.trim()
        if (base.isEmpty()) return emptyList()
        val cleaned = normalizeTitle(base)
        return listOf(base, cleaned)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(2)
    }

    private suspend fun searchOneSource(
        source: CatalogueSource,
        queries: List<String>,
    ): List<SourceHit> {
        val found = LinkedHashMap<String, SourceHit>()
        for (query in queries) {
            try {
                val page = withTimeoutOrNull(6_000) {
                    source.getSearchManga(1, query, FilterList())
                } ?: continue
                page.mangas.take(4).forEach { manga ->
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
                if (found.isNotEmpty()) break
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
            val covered = userTokens.count { ut ->
                candTokens.any { it == ut || it.startsWith(ut) }
            }
            score += ((covered.toFloat() / userTokens.size) * 25).toInt()
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

        if (isEnglishLang(lang)) {
            score += 12
        } else if (lang.lowercase(Locale.ROOT) in listOf("ja", "jp")) {
            score += 2
        } else {
            score -= 3
        }

        if (isNsfwTitle(candidate) && !isNsfwTitle(user)) {
            score -= 40
        }

        if (candidate.length > user.length * 2.5 && score < 95) {
            score -= 10
        }

        return score.coerceIn(0, 100)
    }

    private fun isNsfwTitle(title: String): Boolean {
        val t = title.lowercase(Locale.ROOT)
        val noise = listOf(
            "hentai",
            "r18",
            "r-18",
            "ntr",
            "netorare",
            "cg set",
            "doujin",
            "adult",
            "explicit",
            "18+",
        )
        return noise.any { t.contains(it) }
    }

    private fun isEnglishLang(lang: String): Boolean {
        val l = lang.lowercase(Locale.ROOT)
        return l == "en" || l == "gb" || l.startsWith("en")
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
        val isNsfw: Boolean = false,
    )

    companion object {
        private const val MIN_SCORE = 45
        private const val PRIMARY_EARLY_SCORE = 80
        private const val PRIMARY_MIN_QUERY_SCORE = 70
        private const val SIMILAR_MIN_SCORE = 55
        private const val MAX_SOURCES = 25
        private const val MAX_SIMILAR = 6

        private val runningJobs = ConcurrentHashMap<String, Job>()
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun ensureBackground(
            manager: MergedMangaManager,
            query: String,
        ) {
            val key = query.trim().lowercase(Locale.ROOT)
            if (key.isEmpty()) return
            if (runningJobs.containsKey(key)) return
            appScope.launch {
                manager.searchCohesiveFlow(query).collect { /* drain */ }
            }
        }
    }
}
