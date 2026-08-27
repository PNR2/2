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
import logcat.LogPriority
import logcat.logcat
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
        logcat(LogPriority.INFO) { "MUSYomi: Starting Background Sync..." }

        // 1. Fetch RSS News (Wrapped in its own safety net)
        try {
            val newsUrl = "https://www.animenewsnetwork.com/news/rss.xml"
            val fetchedNews = rssFetcher.fetchNews(newsUrl, "Anime News Network")
            if (fetchedNews.isNotEmpty()) {
                rssRepository.insertNews(fetchedNews)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "MUSYomi: RSS News Fetch Failed" }
        }

        // 2. Fetch MAL Seasonal Manga (Wrapped in its own safety net)
        try {
            val fetchedManga = malFetcher.fetchSeasonalManga()
            if (fetchedManga.isNotEmpty()) {
                val installedSources = sourceManager.getOnlineSources().filterIsInstance<CatalogueSource>()
                val activeSource = installedSources.firstOrNull()

                val matchedMangaList = fetchedManga.map { manga ->
                    var matchedSourceId: Long? = null
                    var matchedMangaUrl: String? = null

                    // Only run automation if the user has it enabled and an extension is installed!
                    if (activeSource != null && malRepository.isAutomationEnabled()) {
                        try {
                            // Use the extension's specific filter list so it doesn't crash
                            val filters = activeSource.getFilterList()
                            val searchPage = activeSource.getSearchManga(1, manga.title, filters)
                            val topMatch = searchPage.mangas.firstOrNull()

                            if (topMatch != null) {
                                matchedSourceId = activeSource.id
                                matchedMangaUrl = topMatch.url
                            }
                            delay(1000) // Polite delay
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR) { "MUSYomi: Automation search failed for ${manga.title}" }
                        }
                    }

                    manga.copy(
                        sourceId = matchedSourceId,
                        mangaUrl = matchedMangaUrl,
                    )
                }
                malRepository.insertSeasonalManga(matchedMangaList)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "MUSYomi: MAL Fetch Failed" }
        }

        return Result.success()
    }

    companion object {
        const val TAG = "DiscoverySyncWorker"

        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<DiscoverySyncWorker>()
                .addTag(TAG)
                .build()
            
            // REPLACE ensures that if it gets stuck, pressing refresh forces it to restart!
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
