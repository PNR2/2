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
 * 3) Auto-link top seasonal titles → cohesive (vision)
 * 4) Re-link cohesive entries older than 7 days
 */
object DiscoverySyncer {

    private val rssFetcher = RssNewsFetcher()
    private val rssRepository = RssNewsRepository()
    private val malFetcher = MalDiscoveryFetcher()
    private val malRepository = MalDiscoveryRepository()
    private val mergedRepository = MergedMangaRepository()

    private const val STALE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_STALE_REFRESH = 5

    /** How many new seasonal titles to auto-link each sync */
    private const val MAX_SEASONAL_AUTOLINK = 6

    suspend fun syncNow() {
        if (DiscoveryProgressState.progress.value.isRunning) return

        DiscoveryProgressState.update(true, 5, "Starting sync...")

        // ===== NEWS =====
        try {
            DiscoveryProgressState.update(true, 15, "Fetching news...")
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
        var seasonalList: List<MalDiscoveryItem> = emptyList()
        try {
            DiscoveryProgressState.update(true, 35, "Fetching seasonal manga...")
            seasonalList = withTimeoutOrNull(25_000) {
                malFetcher.fetchSeasonalManga()
            } ?: emptyList()

            if (seasonalList.isNotEmpty()) {
                DiscoveryProgressState.update(true, 50, "Saving seasonal manga...")
                malRepository.insertSeasonalManga(seasonalList)
            }
        } catch (_: Exception) {
        }

        // ===== AUTO-LINK NEW SEASONAL → COHESIVE =====
        try {
            DiscoveryProgressState.update(true, 60, "Auto-linking seasonal manga...")
            autoLinkSeasonal(seasonalList)
        } catch (_: Exception) {
        }

        // ===== WEEKLY COHESIVE REFRESH =====
        try {
            DiscoveryProgressState.update(true, 80, "Refreshing stale cohesive entries...")
            refreshStaleMergedEntries()
        } catch (_: Exception) {
        }

        DiscoveryProgressState.update(true, 100, "Done!")
        delay(1_000)
        DiscoveryProgressState.reset()
    }

    /**
     * Creates cohesive entries for top seasonal titles that are not yet linked.
     * Skips titles that already exist in merged_manga (by malId or title).
     */
    private suspend fun autoLinkSeasonal(list: List<MalDiscoveryItem>) {
        if (list.isEmpty()) return

        val sourceManager = try {
            Injekt.get<SourceManager>()
        } catch (_: Exception) {
            DiscoveryProgressState.update(true, 75, "Skip auto-link (no SourceManager)")
            return
        }

        val manager = MergedMangaManager(sourceManager)
        val existing = mergedRepository.subscribeToMergedManga().value
        val existingMalIds = existing.mapNotNull { it.malId }.toHashSet()
        val existingTitles = existing.map { it.title.trim().lowercase() }.toHashSet()

        val candidates = list
            .filter { item ->
                item.malId > 0 &&
                    item.malId !in existingMalIds &&
                    item.title.trim().lowercase() !in existingTitles
            }
            .take(MAX_SEASONAL_AUTOLINK)

        if (candidates.isEmpty()) {
            DiscoveryProgressState.update(true, 75, "Seasonal already linked")
            return
        }

        candidates.forEachIndexed { index, manga ->
            val pct = 60 + ((index + 1) * 15 / candidates.size).coerceAtMost(15)
            DiscoveryProgressState.update(
                true,
                pct,
                "Auto-link ${index + 1}/${candidates.size}: ${manga.title}",
            )
            try {
                withTimeoutOrNull(40_000) {
                    manager.createOrUpdateMergedManga(
                        title = manga.title,
                        coverUrl = manga.coverUrl,
                        synopsis = manga.synopsis,
                        author = manga.authors,
                        malId = manga.malId,
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Re-runs extension search for merged entries not updated in 7 days.
     */
    private suspend fun refreshStaleMergedEntries() {
        val sourceManager = try {
            Injekt.get<SourceManager>()
        } catch (_: Exception) {
            DiscoveryProgressState.update(
                true,
                95,
                "Skip stale refresh (no SourceManager)",
            )
            return
        }

        val manager = MergedMangaManager(sourceManager)
        val now = System.currentTimeMillis()

        val stale = mergedRepository.subscribeToMergedManga().value
            .filter { manga -> (now - manga.updatedAt) >= STALE_MS }
            .sortedBy { it.updatedAt }
            .take(MAX_STALE_REFRESH)

        if (stale.isEmpty()) {
            DiscoveryProgressState.update(true, 95, "Cohesive entries are up to date")
            return
        }

        stale.forEachIndexed { index, manga ->
            val pct = 80 + ((index + 1) * 15 / stale.size).coerceAtMost(15)
            DiscoveryProgressState.update(
                true,
                pct,
                "Refreshing ${index + 1}/${stale.size}: ${manga.title}",
            )
            try {
                withTimeoutOrNull(40_000) {
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
