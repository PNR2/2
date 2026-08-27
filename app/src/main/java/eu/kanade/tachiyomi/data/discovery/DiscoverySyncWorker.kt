package eu.kanade.tachiyomi.data.discovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import logcat.LogPriority
import logcat.logcat
import java.util.concurrent.TimeUnit

/**
 * Background worker responsible for silently fetching MyAnimeList
 * and RSS News data and saving it to the local SQLite database.
 */
class DiscoverySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val rssFetcher = RssNewsFetcher()
    private val repository = RssNewsRepository()

    override suspend fun doWork(): Result {
        logcat(LogPriority.INFO) { "MUSYomi: Starting Background Sync for MAL and RSS..." }

        return try {
            val newsUrl = "https://www.animenewsnetwork.com/news/rss.xml"
            val fetchedNews = rssFetcher.fetchNews(newsUrl, "Anime News Network")

            // Save the downloaded articles straight into the SQLite Database!
            if (fetchedNews.isNotEmpty()) {
                repository.insertNews(fetchedNews)
                logcat(LogPriority.INFO) { "MUSYomi: Successfully saved ${fetchedNews.size} articles to database." }
            }

            // TODO: Step 4 - Fetch MyAnimeList Seasonal Data via Network

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
