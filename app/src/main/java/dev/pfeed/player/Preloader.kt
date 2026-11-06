package dev.pfeed.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import coil.imageLoader
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pfeed.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Warms thumbnails and the first ~1.5 MB (a few seconds) of upcoming progressive streams. */
@OptIn(UnstableApi::class)
@Singleton
class Preloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaCache: MediaCache,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    fun thumbnail(url: String?) {
        if (url.isNullOrBlank()) return
        context.imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
    }

    fun video(key: String, url: String, bytes: Long = DEFAULT_BYTES) {
        if (!url.startsWith("http") || isAdaptive(url) || jobs.containsKey(key)) return
        jobs[key] = scope.launch(Dispatchers.IO) {
            try {
                val writer = CacheWriter(
                    mediaCache.cacheDataSourceFactory.createDataSource(),
                    DataSpec.Builder().setUri(Uri.parse(url)).setPosition(0).setLength(bytes).build(),
                    null,
                    null,
                )
                runInterruptible { writer.cache() }
            } catch (_: Exception) {
                // Best effort: the player will simply fetch it when needed.
            } finally {
                jobs.remove(key)
            }
        }
    }

    /** Cancels preloads that are no longer near the viewport. */
    fun retainOnly(keys: Set<String>) {
        jobs.forEach { (k, job) -> if (k !in keys) job.cancel() }
    }

    private fun isAdaptive(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    companion object {
        private const val DEFAULT_BYTES = 2_500_000L
    }
}
