package dev.pfeed.data.repo

import android.content.Context
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.reco.PerceptualHash
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Computes thumbnail dHashes for cross-source dedup. Results are memoised; the bitmap lands in Coil's cache. */
@Singleton
class ThumbnailHasher @Inject constructor(@ApplicationContext private val context: Context) {
    private val memo = LruCache<String, Long>(4000)
    private val permits = Semaphore(6)
    /** Each thumbnail's look (see [dev.pfeed.domain.reco.Visual]), encoded. */
    private val looks = LruCache<String, String>(4000)

    /** The look of [item]'s thumbnail, if it has been read. */
    fun look(item: MediaItem): String? = item.thumbnailUrl?.let(looks::get)

    suspend fun hashAll(items: List<MediaItem>): Map<String, Long?> = coroutineScope {
        items.map { item -> async { item.key to hash(item.thumbnailUrl) } }.awaitAll().toMap()
    }

    /** Only memoised values, no network: for latency-sensitive paths like search. */
    fun cached(items: List<MediaItem>): Map<String, Long?> =
        items.associate { it.key to it.thumbnailUrl?.let(memo::get) }

    suspend fun hash(url: String?): Long? {
        if (url.isNullOrBlank()) return null
        // A video file as "thumbnail" would mean downloading the clip to hash one frame.
        if (dev.pfeed.source.parse.MediaTypes.classify(url) == dev.pfeed.domain.model.MediaType.VIDEO) return null
        memo.get(url)?.let { return it }
        val value = permits.withPermit {
            withTimeoutOrNull(TIMEOUT_MS) {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(url).size(64).allowHardware(false).build(),
                )
                val drawable = (result as? SuccessResult)?.drawable ?: return@withTimeoutOrNull null
                // The same small picture also says what the post looks like (see Visual).
                runCatching {
                    val small = drawable.toBitmap(dev.pfeed.domain.reco.Visual.SIDE, dev.pfeed.domain.reco.Visual.SIDE)
                    val px = IntArray(dev.pfeed.domain.reco.Visual.SIDE * dev.pfeed.domain.reco.Visual.SIDE)
                    small.getPixels(px, 0, dev.pfeed.domain.reco.Visual.SIDE, 0, 0, dev.pfeed.domain.reco.Visual.SIDE, dev.pfeed.domain.reco.Visual.SIDE)
                    looks.put(url, dev.pfeed.domain.reco.Visual.encode(dev.pfeed.domain.reco.Visual.describe(px)))
                }
                val bitmap = drawable.toBitmap(PerceptualHash.WIDTH, PerceptualHash.HEIGHT)
                val pixels = IntArray(PerceptualHash.WIDTH * PerceptualHash.HEIGHT)
                bitmap.getPixels(pixels, 0, PerceptualHash.WIDTH, 0, 0, PerceptualHash.WIDTH, PerceptualHash.HEIGHT)
                PerceptualHash.dHash(PerceptualHash.toGray(pixels))
            }
        }
        if (value != null) memo.put(url, value)
        return value
    }

    companion object {
        private const val TIMEOUT_MS = 2_000L
    }
}
