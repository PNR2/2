@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.delay
import logcat.LogPriority
import logcat.logcat
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Background worker responsible for silently fetching MyAnimeList
 * and RSS News data, and automatically linking MAL entries to installed extensions.
 */
class DiscoverySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val rssFetcher = RssNewsFetcher()
    private val rssRepository = RssNewsRepository()

    private val malFetcher = MalDiscoveryFetcher()
    private val malRepository = MalDiscoveryRepository()

    // Inject Mihon's Source Manager to access your installed extensions
    private val sourceManager: SourceManager = Injekt.get()

    override suspend fun doWork(): Result {
        logcat(LogPriority.INFO) { "MUSYomi: Starting Background Sync for MAL and RSS..." }

        return try {
            // 1. Fetch & Save RSS News
            val newsUrl = "https://www.animenewsnetwork.com/news/rss.xml"
            val fetchedNews = rssFetcher.fetchNews(newsUrl, "Anime News Network")
            if (fetchedNews.isNotEmpty()) {
                rssRepository.insertNews(fetchedNews)
                logcat(LogPriority.INFO) { "MUSYomi: Successfully saved ${fetchedNews.size} articles." }
            }

            // 2. Fetch MyAnimeList Seasonal Manga
            val fetchedManga = malFetcher.fetchSeasonalManga()
            if (fetchedManga.isNotEmpty()) {
                
                // 3. THE AUTOMATION ENGINE
                // Grab all online extensions the user has installed
                val installedSources = sourceManager.getOnlineSources().filterIsInstance<CatalogueSource>()
                val activeSource = installedSources.firstOrNull() // Pick the first available one

                val matchedMangaList = fetchedManga.map { manga ->
                    var matchedSourceId: Long? = null
                    var matchedMangaUrl: String? = null

                    if (activeSource != null) {
                        try {
                            // Silently search the extension using the MAL title
                            val searchPage = activeSource.getSearchManga(1, manga.title, FilterList())
                            val topMatch = searchPage.mangas.firstOrNull()

                            if (topMatch != null) {
                                matchedSourceId = activeSource.id
                                matchedMangaUrl = topMatch.url
                                logcat(LogPriority.INFO) { "MUSYomi: Automated Match Found for ${manga.title}" }
                            }
                            // Be polite to the extension server to avoid rate limits (1 second delay)
                            delay(1000)
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR) { "MUSYomi: Automation search failed for ${manga.title}" }
                        }
                    }

                    // Return a copy of the manga with the newly matched data attached!
                    manga.copy(
                        sourceId = matchedSourceId,
                        mangaUrl = matchedMangaUrl,
                    )
                }

                // Save the MAL data AND the matched extension data to our custom SQLite database
                malRepository.insertSeasonalManga(matchedMangaList)
                logcat(LogPriority.INFO) { "MUSYomi: Successfully saved ${matchedMangaList.size} seasonal manga with automation." }
            }

            logcat(LogPriority.INFO) { "MUSYomi: Background Sync Completed Successfully." }
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "MUSYomi: Background Sync Failed - ${e.message}" }
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "DiscoverySyncWorker"

        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<DiscoverySyncWorker>()
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueue(request)
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
