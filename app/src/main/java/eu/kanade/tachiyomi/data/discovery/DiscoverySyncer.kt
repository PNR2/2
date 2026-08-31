package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object DiscoverySyncer {

    private val rssFetcher = RssNewsFetcher()
    private val rssRepository = RssNewsRepository()
    private val malFetcher = MalDiscoveryFetcher()
    private val malRepository = MalDiscoveryRepository()
    private val mergedMangaManager = MergedMangaManager()

    suspend fun syncNow() {
        if (DiscoveryProgressState.progress.value.isRunning) return

        DiscoveryProgressState.update(true, 5, "Starting sync...")

        // ===== NEWS =====
        try {
            DiscoveryProgressState.update(true, 20, "Fetching news...")
            withTimeoutOrNull(12000) {
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

        // ===== SEASONAL MANGA + AUTO LINK =====
        try {
            DiscoveryProgressState.update(true, 45, "Fetching seasonal manga...")
            val mangaList = withTimeoutOrNull(20000) {
                malFetcher.fetchSeasonalManga()
            } ?: emptyList()

            if (mangaList.isNotEmpty()) {
                DiscoveryProgressState.update(true, 65, "Saving seasonal manga...")
                malRepository.insertSeasonalManga(mangaList)

                // Auto-link (Merged Manga)
                DiscoveryProgressState.update(true, 80, "Auto-linking to extensions...")
                // limit to 8 so it doesn't take too long
                mangaList.take(8).forEach { manga ->
                    try {
                        withTimeoutOrNull(8000) {
                            mergedMangaManager.createOrUpdateMergedManga(
                                title = manga.title,
                                coverUrl = manga.coverUrl,
                                synopsis = manga.synopsis,
                                malId = manga.malId,
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }

        DiscoveryProgressState.update(true, 100, "Done!")
        delay(1200)
        DiscoveryProgressState.reset()
    }
}
