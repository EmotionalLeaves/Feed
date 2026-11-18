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
import coil.imageLoader
import coil.request.ImageRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pfeed.data.repo.FeedSessionFactory
import dev.pfeed.data.repo.FeedSpec
import dev.pfeed.data.repo.PoolCache
import dev.pfeed.domain.model.FeedMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Ready when you are: every few hours on Wi-Fi, while the app is closed, For You is fetched and
 * ranked ahead of time and the first screen's pictures are loaded, so opening the app shows posts
 * at once, chosen for you, instead of a spinner. Nothing is shown, learned or remembered as seen.
 */
@HiltWorker
class ReadyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sessions: FeedSessionFactory,
    private val preloader: dev.pfeed.player.Preloader,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val onScreen = withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (onScreen || !PoolCache.enabled) return Result.success()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = sessions.create(FeedSpec.Main(FeedMode.FOR_YOU), scope)
            withTimeoutOrNull(TIME_LIMIT_MS) { session.prepareForLater() } ?: return Result.success()
            // The first screen's pictures, so they're there the moment the app opens.
            val first = session.firstKept(FIRST_SCREEN)
            val loader = applicationContext.imageLoader
            for (item in first) {
                val url = item.thumbnailUrl ?: continue
                withTimeoutOrNull(15_000) { loader.execute(ImageRequest.Builder(applicationContext).data(url).build()) }
            }
            // And the opening seconds of the first few videos whose file links don't expire.
            val clips = first.filter { it.type == dev.pfeed.domain.model.MediaType.VIDEO && it.extras["resolve"] == null }
                .mapNotNull { item -> item.extras["media"]?.takeIf { it.startsWith("http") }?.let { item.key to it } }
                .take(FIRST_CLIPS)
            clips.forEach { (key, url) -> preloader.video(key, url) }
            if (clips.isNotEmpty()) kotlinx.coroutines.delay(CLIP_WAIT_MS)
        } finally {
            scope.cancel()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "ready-when-you-are"
        private const val TIME_LIMIT_MS = 90_000L
        private const val FIRST_SCREEN = 12
        private const val FIRST_CLIPS = 4
        private const val CLIP_WAIT_MS = 20_000L

        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            wm.enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                // Saved posts are used for up to 6 hours, so every 3 keeps one ready.
                PeriodicWorkRequestBuilder<ReadyWorker>(3, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    )
                    .build(),
            )
        }
    }
}
