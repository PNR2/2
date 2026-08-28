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
import java.util.concurrent.TimeUnit

class DiscoverySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DiscoverySyncer.syncNow()
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
