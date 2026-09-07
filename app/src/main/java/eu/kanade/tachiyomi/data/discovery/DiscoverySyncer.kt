package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Discovery Hub sync (MAL + RSS only).
 * Does NOT search extensions — SourceManager is Metro-injected and only used
 * from CohesiveSearchViewModel / MergedMangaViewModel.
 */
object DiscoverySyncer {

    private val rssFetcher = RssNewsFetcher()
    private val rssRepository = RssNewsRepository()
    private val malFetcher = MalDiscoveryFetcher()
    private val malRepository = MalDiscoveryRepository()

    suspend fun syncNow() {
        if (DiscoveryProgressState.progress.value.isRunning) return

        DiscoveryProgressState.update(true, 5, "Starting sync...")

        // ===== NEWS =====
        try {
            DiscoveryProgressState.update(true, 25, "Fetching news...")
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
            DiscoveryProgressState.update(true, 55, "Fetching seasonal manga...")
            val mangaList = withTimeoutOrNull(25_000) {
                malFetcher.fetchSeasonalManga()
            } ?: emptyList()

            if (mangaList.isNotEmpty()) {
                DiscoveryProgressState.update(true, 80, "Saving seasonal manga...")
                malRepository.insertSeasonalManga(mangaList)
            }
        } catch (_: Exception) {
        }

        DiscoveryProgressState.update(true, 100, "Done!")
        delay(1_000)
        DiscoveryProgressState.reset()
    }
}
