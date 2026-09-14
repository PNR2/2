@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Discovery Hub sync:
 * 1) RSS news
 * 2) MAL seasonal manga
 * 3) Re-link cohesive (merged) entries older than 7 days (vision: weekly auto refresh)
 *
 * Extension search uses SourceManager when available (same pattern as NewsTab open cohesive).
 */
object DiscoverySyncer {

    private val rssFetcher = RssNewsFetcher()
    private val rssRepository = RssNewsRepository()
    private val malFetcher = MalDiscoveryFetcher()
    private val malRepository = MalDiscoveryRepository()
    private val mergedRepository = MergedMangaRepository()

    /** 7 days in milliseconds — vision weekly refresh */
    private const val STALE_MS = 7L * 24L * 60L * 60L * 1000L

    /** Cap so sync does not run forever */
    private const val MAX_REFRESH = 5

    suspend fun syncNow() {
        if (DiscoveryProgressState.progress.value.isRunning) return

        DiscoveryProgressState.update(true, 5, "Starting sync...")

        // ===== NEWS =====
        try {
            DiscoveryProgressState.update(true, 20, "Fetching news...")
            withTimeoutOrNull(15_000) {
                val news = rssFetcher.fetchNews(
                    "https://www.animenewsnetwork.com/news/rss.xml",
                    "Anime News Network",
                )
                if (news.isNotEmpty()) {
                    rssRepository.insertNews(news)
                }
            }
        } catch (_: Exception) {
        }

        // ===== SEASONAL MANGA =====
        try {
            DiscoveryProgressState.update(true, 45, "Fetching seasonal manga...")
            val mangaList = withTimeoutOrNull(25_000) {
                malFetcher.fetchSeasonalManga()
            } ?: emptyList()

            if (mangaList.isNotEmpty()) {
                DiscoveryProgressState.update(true, 60, "Saving seasonal manga...")
                malRepository.insertSeasonalManga(mangaList)
            }
        } catch (_: Exception) {
        }

        // ===== COHESIVE WEEKLY REFRESH =====
        try {
            DiscoveryProgressState.update(true, 70, "Refreshing cohesive entries...")
            refreshStaleMergedEntries()
        } catch (_: Exception) {
        }

        DiscoveryProgressState.update(true, 100, "Done!")
        delay(1_000)
        DiscoveryProgressState.reset()
    }

    /**
     * Re-runs extension search for merged entries not updated in 7 days.
     * Keeps sources alive when one extension dies (vision).
     */
    private suspend fun refreshStaleMergedEntries() {
        val sourceManager = try {
            Injekt.get<SourceManager>()
        } catch (_: Exception) {
            DiscoveryProgressState.update(
                true,
                90,
                "Skip cohesive refresh (no SourceManager)",
            )
            return
        }

        val manager = MergedMangaManager(sourceManager)
        val now = System.currentTimeMillis()

        val stale = mergedRepository.subscribeToMergedManga().value
            .filter { manga ->
                (now - manga.updatedAt) >= STALE_MS
            }
            .sortedBy { it.updatedAt }
            .take(MAX_REFRESH)

        if (stale.isEmpty()) {
            DiscoveryProgressState.update(true, 90, "Cohesive entries are up to date")
            return
        }

        stale.forEachIndexed { index, manga ->
            val pct = 70 + ((index + 1) * 20 / stale.size).coerceAtMost(20)
            DiscoveryProgressState.update(
                true,
                pct,
                "Refreshing ${index + 1}/${stale.size}: ${manga.title}",
            )
            try {
                withTimeoutOrNull(45_000) {
                    manager.createOrUpdateMergedManga(
                        title = manga.title,
                        coverUrl = manga.coverUrl,
                        synopsis = manga.synopsis,
                        author = manga.author,
                        malId = manga.malId,
                    )
                }
            } catch (_: Exception) {
            }
        }
    }
}
