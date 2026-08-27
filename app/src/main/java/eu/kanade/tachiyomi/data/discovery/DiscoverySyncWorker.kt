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

    // Instantiate our new fetcher
    private val rssFetcher = RssNewsFetcher()

    override suspend fun doWork(): Result {
        logcat(LogPriority.INFO) { "MUSYomi: Starting Background Sync for MAL and RSS..." }

        return try {
            // Fetch live news from Anime News Network
            val newsUrl = "https://www.animenewsnetwork.com/news/rss.xml"
            val fetchedNews = rssFetcher.fetchNews(newsUrl, "Anime News Network")
            
            // Log the titles to guarantee our parser is working before we touch the database
            logcat(LogPriority.INFO) { "MUSYomi: Successfully fetched ${fetchedNews.size} articles." }
            fetchedNews.take(5).forEach { article ->
                logcat(LogPriority.INFO) { "MUSYomi Headline: ${article.title}" }
            }

            // TODO: Step 3 - Insert results into discovery.sq database tables (Next step!)

            logcat(LogPriority.INFO) { "MUSYomi: Background Sync Completed Successfully." }
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "MUSYomi: Background Sync Failed - ${e.message}" }
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "DiscoverySyncWorker"

        /**
         * Trigger a one-time immediate sync (Useful for pull-to-refresh in the UI)
         */
        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<DiscoverySyncWorker>()
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * Sets up the background app task to run automatically every 12 hours
         */
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
