@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object DiscoverySyncer {
    private val rssFetcher = RssNewsFetcher()
    private val rssRepository = RssNewsRepository()
    private val malFetcher = MalDiscoveryFetcher()
    private val malRepository = MalDiscoveryRepository()

    suspend fun syncNow() {
        if (DiscoveryProgressState.progress.value.isRunning) return

        DiscoveryProgressState.update(
            isRunning = true,
            percentage = 10,
            message = "Starting sync...",
        )

        try {
            DiscoveryProgressState.update(
                isRunning = true,
                percentage = 25,
                message = "Fetching latest anime news...",
            )
            withTimeoutOrNull(10000) {
                val newsUrl = "https://www.animenewsnetwork.com/news/rss.xml"
                val fetchedNews = rssFetcher.fetchNews(newsUrl, "Anime News Network")
                if (fetchedNews.isNotEmpty()) {
                    rssRepository.insertNews(fetchedNews)
                }
            }
        } catch (e: Exception) {
            // Skip
        }

        try {
            DiscoveryProgressState.update(
                isRunning = true,
                percentage = 50,
                message = "Fetching seasonal manga from MAL...",
            )
            withTimeoutOrNull(20000) {
                val fetchedManga = malFetcher.fetchSeasonalManga()

                if (fetchedManga.isNotEmpty()) {
                    DiscoveryProgressState.update(
                        isRunning = true,
                        percentage = 75,
                        message = "Checking extension matching...",
                    )
                    val sourceManager: SourceManager = Injekt.get()
                    val installedSources = sourceManager.getOnlineSources().filterIsInstance<CatalogueSource>()
                    val activeSource = installedSources.firstOrNull()
                    val isAutoOn = MalDiscoveryRepository.isAutomationEnabled()

                    val matchedMangaList = fetchedManga.map { manga ->
                        var matchedSourceId: Long? = null
                        var matchedMangaUrl: String? = null

                        if (isAutoOn && activeSource != null && manga.malId > 0) {
                            try {
                                withTimeoutOrNull(2500) {
                                    val filters = activeSource.getFilterList()
                                    val searchPage = activeSource.getSearchManga(1, manga.title, filters)
                                    val topMatch = searchPage.mangas.firstOrNull()

                                    if (topMatch != null) {
                                        matchedSourceId = activeSource.id
                                        matchedMangaUrl = topMatch.url
                                    }
                                }
                                delay(300)
                            } catch (e: Exception) {
                                // Skip
                            }
                        }
                        manga.copy(
                            sourceId = matchedSourceId,
                            mangaUrl = matchedMangaUrl,
                        )
                    }

                    DiscoveryProgressState.update(
                        isRunning = true,
                        percentage = 90,
                        message = "Saving to database...",
                    )
                    malRepository.insertSeasonalManga(matchedMangaList)
                }
            }
        } catch (e: Exception) {
            // Skip
        }

        DiscoveryProgressState.update(
            isRunning = true,
            percentage = 100,
            message = "Done!",
        )
        delay(800)
        DiscoveryProgressState.reset()
    }
}
