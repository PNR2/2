@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MergedMangaManager(
    val sourceManager: SourceManager,
) {

    private val repository = MergedMangaRepository()
    
    // Instance-bound job tracking prevents state leaks across reloads
    private val activeBackgroundJobs = ConcurrentHashMap<String, Job>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /**
     * Phase 1: The Fast Race
     * Instantly grabs the first valid result from any extension to build a rich UI shell,
     * then kicks off Phase 2 in the background.
     */
    suspend fun searchCohesive(
        query: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
    ): CohesiveSearchOutcome {
        val q = query.trim()
        if (q.isEmpty()) return CohesiveSearchOutcome(-1, "", emptyList())

        // 1. Check if we already have a rich shell in the DB
        var shellId = repository.getIdByExactTitle(q)
        var existingMerged = shellId?.let { repository.getMergedMangaById(it) }

        // 2. If no valid cover exists, run a fast race to find one
        if (existingMerged?.coverUrl.isNullOrBlank()) {
            val fastHit = fastRaceForPlaceholder(q)
            shellId = repository.createOrUpdateMergedManga(
                id = shellId,
                title = fastHit?.manga?.title ?: q,
                coverUrl = fastHit?.manga?.thumbnail_url ?: coverUrl,
                synopsis = fastHit?.manga?.description ?: synopsis,
                author = fastHit?.manga?.author ?: author,
                malId = malId,
            )
            existingMerged = repository.getMergedMangaById(shellId)
        }

        // 3. Fire the background harvester (Phase 2) safely
        ensureBackgroundHarvest(q, shellId!!)

        val finalTitle = existingMerged?.title ?: q
        return CohesiveSearchOutcome(shellId, finalTitle, emptyList())
    }

    /**
     * Races all extensions. The moment ONE extension returns a valid manga with a cover, 
     * it cancels the other searches and returns the data for the instant UI shell.
     */
    private suspend fun fastRaceForPlaceholder(query: String): SourceHit? = coroutineScope {
        val sources = sourceManager.getOnlineSources()
            .filterIsInstance<CatalogueSource>()
            .filter { it.lang.isNotBlank() }
            .sortedByDescending { isEnglishLang(it.lang) }
            .take(MAX_SEARCH_SOURCES)

        if (sources.isEmpty()) return@coroutineScope null

        val resultChannel = kotlinx.coroutines.channels.Channel<SourceHit?>(1)
        
        val raceJobs = sources.map { source ->
            launch {
                try {
                    val page = withTimeoutOrNull(2500L) {
                        source.getSearchManga(1, query, FilterList())
                    }
                    val validManga = page?.mangas?.firstOrNull { 
                        !it.thumbnail_url.isNullOrBlank() && 
                        normalizeTitle(it.title).contains(normalizeTitle(query))
                    }
                    
                    if (validManga != null) {
                        resultChannel.trySend(SourceHit(source.id, source.name, source.lang, validManga, 100))
                    }
                } catch (_: Exception) {}
            }
        }

        // Wait for the first success, or timeout after 3 seconds
        val winner = withTimeoutOrNull(3000L) {
            resultChannel.receive()
        }

        // Cancel the losers so we don't choke the network
        raceJobs.forEach { it.cancel() }
        resultChannel.close()

        winner
    }

    /**
     * Phase 2: The Background Grind
     * Exhaustively searches extensions, merges metadata, and harvests chapters.
     */
    private fun ensureBackgroundHarvest(query: String, mergedId: Long) {
        val key = query.trim().lowercase(Locale.ROOT)
        if (activeBackgroundJobs.containsKey(key)) return

        val job = appScope.launch {
            try {
                // 1. Exhaustive search to find all matching extensions
                val allHits = exhaustiveSearch(query)
                
                // 2. Score, filter, and cluster the results
                val scored = allHits
                    .map { it.copy(score = scoreMatch(query, it.manga, it.sourceName, it.lang)) }
                    .filter { it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }
                
                if (scored.isNotEmpty()) {
                    // Update the shell with the absolute best metadata
                    val bestHit = scored.first()
                    repository.updateDetailsIfBlank(
                        mergedId = mergedId,
                        author = bestHit.manga.author,
                        artist = bestHit.manga.artist,
                        synopsis = bestHit.manga.description,
                        coverUrl = bestHit.manga.thumbnail_url,
                    )
                    
                    // Link the sources
                    repository.clearReferences(mergedId)
                    addHitsAsReferences(mergedId, scored, false)
                }

                // 3. Harvest chapters from the linked sources
                harvestChapters(mergedId)
                
            } finally {
                // Prevent state leaks by cleaning up the job map
                activeBackgroundJobs.remove(key)
            }
        }
        
        activeBackgroundJobs[key] = job
    }

    private suspend fun exhaustiveSearch(query: String): List<SourceHit> = coroutineScope {
        val sources = sourceManager.getOnlineSources()
            .filterIsInstance<CatalogueSource>()
            .take(MAX_SEARCH_SOURCES)
            
        val queries = buildSearchQueries(query)
        val allHits = mutableListOf<SourceHit>()
        val hitMutex = Mutex()
        val semaphore = Semaphore(PARALLELISM)

        sources.map { source ->
            launch {
                semaphore.withPermit {
                    val hits = searchOneSource(source, queries)
                    hitMutex.withLock { allHits.addAll(hits) }
                }
            }
        }.forEach { it.join() }
        
        allHits
    }

    private suspend fun harvestChapters(mergedId: Long) = coroutineScope {
        val refs = repository.getReferences(mergedId)
        val semaphore = Semaphore(5)

        refs.forEach { ref ->
            launch {
                semaphore.withPermit {
                    try {
                        val source = sourceManager.get(ref.sourceId) as? CatalogueSource ?: return@withPermit
                        val sManga = SManga.create().apply {
                            url = ref.mangaUrl
                            title = ref.mangaTitle ?: ""
                        }

                        val chapters = withTimeoutOrNull(20_000L) {
                            fetchChaptersSafe(source, sManga)
                        } ?: emptyList()

                        if (chapters.isNotEmpty()) {
                            val mergedChapters = chapters.map { ch ->
                                MergedChapter(
                                    mergedId = mergedId,
                                    sourceId = ref.sourceId,
                                    url = ch.url,
                                    name = ch.name,
                                    chapterNumber = ch.chapter_number,
                                    language = source.lang,
                                    dateUpload = ch.date_upload,
                                )
                            }
                            repository.addChapters(mergedChapters)
                            repository.updateReferenceChapterCount(mergedId, ref.sourceId, ref.mangaUrl, mergedChapters.size)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun fetchChaptersSafe(source: CatalogueSource, manga: SManga): List<SChapter> {
        return suspendCancellableCoroutine { continuation ->
            val subscription = source.fetchChapterList(manga).subscribe(
                { if (continuation.isActive) continuation.resume(it) },
                { if (continuation.isActive) continuation.resumeWithException(it) }
            )
            continuation.invokeOnCancellation { subscription.unsubscribe() }
        }
    }

    private fun addHitsAsReferences(mergedId: Long, hits: List<SourceHit>, clearFirst: Boolean) {
        if (clearFirst) repository.clearReferences(mergedId)
        
        val existing = repository.getReferences(mergedId).map { "${it.sourceId}_${it.mangaUrl}" }.toHashSet()

        hits.groupBy { it.sourceId }
            .mapValues { (_, list) -> list.maxByOrNull { it.score }!! }
            .values
            .sortedByDescending { it.score }
            .forEachIndexed { index, hit ->
                val refKey = "${hit.sourceId}_${hit.manga.url}"
                if (refKey in existing || existing.size >= MAX_SOURCES) return@forEachIndexed
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
                    )
                )
                existing.add(refKey)
            }
    }

    private fun buildSearchQueries(raw: String): List<String> {
        val base = raw.trim()
        if (base.isEmpty()) return emptyList()
        val cleaned = normalizeTitle(base)
        return listOf(base, cleaned).map { it.trim() }.filter { it.length >= 2 }.distinctBy { it.lowercase(Locale.ROOT) }.take(2)
    }

    private suspend fun searchOneSource(source: CatalogueSource, queries: List<String>): List<SourceHit> {
        val found = LinkedHashMap<String, SourceHit>()
        for (query in queries) {
            try {
                val page = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    source.getSearchManga(1, query, FilterList())
                } ?: continue
                page.mangas.take(3).forEach { manga ->
                    if (!found.containsKey(manga.url)) {
                        found[manga.url] = SourceHit(source.id, source.name, source.lang, manga, 0)
                    }
                }
                if (found.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        return found.values.toList()
    }

    private fun scoreMatch(userTitle: String, manga: SManga, sourceName: String, lang: String): Int {
        val user = normalizeTitle(userTitle)
        val candidate = normalizeTitle(manga.title)
        if (user.isEmpty() || candidate.isEmpty()) return 0

        var score = 0
        when {
            user == candidate -> score += 100
            candidate.startsWith(user) || user.startsWith(candidate) -> score += 90
            candidate.contains(user) || user.contains(candidate) -> score += 70
        }

        if (isEnglishLang(lang)) score += 12
        if (isNsfwTitle(candidate) && !isNsfwTitle(user)) score -= 40
        if (candidate != user && candidate.length > user.length) {
            val ratio = candidate.length.toFloat() / user.length.toFloat()
            if (ratio > 1.5f) score -= ((ratio - 1.5f) * 25).toInt().coerceAtMost(45)
        }
        return score.coerceIn(0, 100)
    }

    private fun isNsfwTitle(title: String): Boolean {
        val t = title.lowercase(Locale.ROOT)
        return listOf("hentai", "r18", "r-18", "ntr", "doujin", "adult", "18+").any { t.contains(it) }
    }

    private fun isEnglishLang(lang: String): Boolean {
        val l = lang.lowercase(Locale.ROOT)
        return l == "en" || l == "gb" || l.startsWith("en")
    }

    private fun normalizeTitle(input: String): String {
        return input.lowercase(Locale.ROOT).replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ").replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private data class SourceHit(
        val sourceId: Long,
        val sourceName: String,
        val lang: String,
        val manga: SManga,
        val score: Int,
    )

    companion object {
        private const val MIN_SCORE = 45
        private const val MAX_SOURCES = 25
        private const val MAX_SEARCH_SOURCES = 35
        private const val PARALLELISM = 16
        private const val SOURCE_TIMEOUT_MS = 4_000L
    }
}
