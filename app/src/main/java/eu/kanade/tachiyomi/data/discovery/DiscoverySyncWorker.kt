package eu.kanade.tachiyomi.data.discovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.util.system.logcat
import logcat.LogPriority
import java.util.concurrent.TimeUnit

/**
 * Background worker responsible for silently fetching MyAnimeList 
 * and RSS News data and saving it to the local SQLite database.
 */
class DiscoverySyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        logcat(LogPriority.INFO) { "MUSYomi: Starting Background Sync for MAL and RSS..." }
        
        try {
            // TODO: Step 1 - Fetch MyAnimeList Seasonal Data via Network
            // TODO: Step 2 - Fetch RSS News XML via Network
            // TODO: Step 3 - Insert results into discovery.sq database tables
            
            logcat(LogPriority.INFO) { "MUSYomi: Background Sync Completed Successfully." }
            return Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "MUSYomi: Background Sync Failed - ${e.message}" }
            return Result.failure()
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
                request
            )
        }
    }
}
