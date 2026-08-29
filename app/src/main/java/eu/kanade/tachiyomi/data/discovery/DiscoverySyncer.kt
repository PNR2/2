@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object DiscoverySyncer {
    private val rssFetcher = RssNewsFetcher()
    private val rssRepository = RssNewsRepository()
    private val malFetcher = MalDiscoveryFetcher()
    private val malRepository = MalDiscoveryRepository()

    suspend fun syncNow() {
        if (DiscoveryProgressState.progress.value.isRunning) return

        DiscoveryProgressState.update(true, 10, "Starting sync...")

        // News
        try {
            DiscoveryProgressState.update(true, 30, "Fetching news...")
            withTimeoutOrNull(12000) {
                val news = rssFetcher.fetchNews(
                    "https://www.animenewsnetwork.com/news/rss.xml",
                    "Anime News Network",
                )
                if (news.isNotEmpty()) rssRepository.insertNews(news)
            }
        } catch (_: Exception) {}

        // Seasonal
        try {
            DiscoveryProgressState.update(true, 60, "Fetching seasonal manga...")
            val mangaList = withTimeoutOrNull(20000) {
                malFetcher.fetchSeasonalManga()
            } ?: emptyList()

            DiscoveryProgressState.update(true, 85, "Saving to database...")
            // Always try to save (even the test item)
            malRepository.insertSeasonalManga(mangaList)
        } catch (_: Exception) {}

        DiscoveryProgressState.update(true, 100, "Done!")
        delay(1200)
        DiscoveryProgressState.reset()
    }
}
