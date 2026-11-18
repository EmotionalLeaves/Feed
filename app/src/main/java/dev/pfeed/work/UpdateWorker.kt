package dev.pfeed.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pfeed.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Updates even when the app isn't opened: twice a day, on Wi-Fi, it looks on GitHub and downloads
 * a newer version; if Android lets it go in without a prompt (and the app isn't on screen), it's
 * installed there and then. Posts no notifications.
 */
@HiltWorker
class UpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updater: Updater,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        updater.automatic(minGapMs = MIN_GAP_MS)
        val onScreen = withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (!onScreen && updater.readyQuietly) updater.installNow(quiet = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "updates"
        private const val MIN_GAP_MS = 60 * 60 * 1000L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .build(),
            )
        }
    }
}
