@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class DiscoverySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val rssFetcher = RssNewsFetcher()
    private val rssRepository = RssNewsRepository()

    private val malFetcher = MalDiscoveryFetcher()
    private val malRepository = MalDiscoveryRepository()

    private val sourceManager: SourceManager = Injekt.get()

    override suspend fun doWork(): Result {
        DiscoveryProgressState.update(isRunning = true, percentage = 10, message = "Starting sync...")

        // 1. Fetch RSS News (10% -> 40%)
        try {
            DiscoveryProgressState.update(isRunning = true, percentage = 25, message = "Fetching latest anime news...")
            withTimeoutOrNull(10000) {
                val newsUrl = "https://www.animenewsnetwork.com/news/rss.xml"
                val fetchedNews = rssFetcher.fetchNews(newsUrl, "Anime News Network")
                if (fetchedNews.isNotEmpty()) {
                    rssRepository.insertNews(fetchedNews)
                }
            }
        } catch (e: Exception) {
            // Silently skip if RSS fails
        }

        // 2. Fetch Manga from MAL (40% -> 70%)
        try {
            DiscoveryProgressState.update(isRunning = true, percentage = 50, message = "Fetching seasonal manga from MAL...")
            withTimeoutOrNull(20000) {
                val fetchedManga = malFetcher.fetchSeasonalManga()

                if (fetchedManga.isNotEmpty()) {
                    DiscoveryProgressState.update(isRunning = true, percentage = 75, message = "Checking extension matching...")
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
                                // Skip extension search error
                            }
                        }

                        manga.copy(
                            sourceId = matchedSourceId,
                            mangaUrl = matchedMangaUrl,
                        )
                    }

                    DiscoveryProgressState.update(isRunning = true, percentage = 90, message = "Saving to database...")
                    malRepository.insertSeasonalManga(matchedMangaList)
                }
            }
        } catch (e: Exception) {
            // Silently skip if MAL fails
        }

        DiscoveryProgressState.update(isRunning = true, percentage = 100, message = "Done!")
        delay(800)
        DiscoveryProgressState.reset()

        return Result.success()
    }

    companion object {
        const val TAG = "DiscoverySyncWorker"

        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<DiscoverySyncWorker>()
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                TAG + "_MANUAL",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun setupPeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<DiscoverySyncWorker>(12, TimeUnit.HOURS)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
