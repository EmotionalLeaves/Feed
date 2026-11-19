package dev.pfeed.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pfeed.data.repo.FeedSessionFactory
import dev.pfeed.data.repo.FeedSpec
import dev.pfeed.data.repo.LibraryRepository
import dev.pfeed.domain.model.FeedMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Your weekly mix: once a week, on Wi-Fi, For You picks 30 posts it thinks you'll love that you
 * haven't seen, and puts them in a Library collection of that name (replacing last week's), to
 * watch whenever. Nothing is shown, learned or marked seen while it's made.
 */
@HiltWorker
class WeeklyMixWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sessions: FeedSessionFactory,
    private val library: LibraryRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = sessions.create(FeedSpec.Main(FeedMode.FOR_YOU), scope)
            withTimeoutOrNull(TIME_LIMIT_MS) { session.prepareForLater(rounds = 3) } ?: return Result.retry()
            val picks = session.firstKept(SIZE)
            if (picks.size < MIN) return Result.retry()
            runCatching {
                library.deleteCollection(NAME)
                picks.forEach { library.addToCollection(NAME, it) }
            }
        } finally {
            scope.cancel()
        }
        return Result.success()
    }

    companion object {
        const val NAME = "Your weekly mix"
        private const val WORK = "weekly-mix"
        private const val SIZE = 30
        private const val MIN = 10
        private const val TIME_LIMIT_MS = 120_000L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WeeklyMixWorker>(7, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                    .build(),
            )
        }
    }
}
