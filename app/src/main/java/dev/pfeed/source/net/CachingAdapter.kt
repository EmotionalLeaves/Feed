package dev.pfeed.source.net

import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.SourceAdapter
import dev.pfeed.source.SourceException
import dev.pfeed.source.pageAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Base for adapters that read a user-configured list of entries (subreddits, hosts, feeds…), fetch a
 * recent page from each in parallel, and page the merged result in memory. One network round per
 * refresh keeps battery and rate-limits sane; `cursor == null` forces a refresh.
 *
 * [resolveStream] reads the final media URL that the parser stored in `extras` (key "media", or
 * "player" for embeds), so subclasses don't need their own resolve logic.
 */
abstract class CachingAdapter : SourceAdapter {

    private val mutex = Mutex()
    private var cache: List<MediaItem> = emptyList()
    private var signature: List<String> = emptyList()
    private var cachedAt = 0L

    protected open val cacheMs = 5 * 60_000L
    protected open val minRefreshMs = 45_000L
    protected open val pageSize = 20

    /** The configured entries; also used as the cache key. */
    protected abstract suspend fun entries(): List<String>

    /** Fetch and map one entry. Throw [SourceException] on failure. */
    protected abstract suspend fun fetchEntry(entry: String): List<MediaItem>

    override suspend fun fetchFeed(cursor: String?, prefs: FeedPrefs): Page<MediaItem> {
        var items = loaded(force = cursor == null)
        prefs.tag?.let { tag -> items = items.filter { item -> item.tags.any { it.equals(tag, ignoreCase = true) } } }
        return items.pageAt(cursor, pageSize)
    }

    override suspend fun resolveStream(item: MediaItem): StreamInfo = when (item.type) {
        MediaType.EMBED -> StreamInfo.Embed(item.extras["player"] ?: item.pageUrl)
        else -> StreamInfo.Direct(
            url = item.extras["media"] ?: throw SourceException(id, "Item has no media URL"),
            mimeType = item.extras["mime"],
        )
    }

    override fun reportUrl(item: MediaItem): String? = item.pageUrl.takeIf { it.startsWith("http") }

    protected suspend fun loaded(force: Boolean): List<MediaItem> = mutex.withLock {
        val sig = entries()
        val now = System.currentTimeMillis()
        if (sig == signature && now - cachedAt < (if (force) minRefreshMs else cacheMs)) return cache
        if (sig.isEmpty()) {
            cache = emptyList(); signature = sig; cachedAt = now
            return cache
        }
        val results = coroutineScope {
            sig.map { entry -> async(Dispatchers.IO) { runCatching { fetchEntry(entry) } } }.awaitAll()
        }
        val ok = results.mapNotNull { it.getOrNull() }
        if (ok.isEmpty()) {
            // All entries failed — keep stale cache if we have one, otherwise throw
            if (cache.isNotEmpty()) return cache
            val first = results.firstNotNullOfOrNull { it.exceptionOrNull() }
            throw first as? SourceException ?: SourceException(id, first?.message ?: "Couldn't load", cause = first)
        }
        cache = ok.flatten().distinctBy { it.key }.sortedByDescending { it.publishedAt }
        signature = sig
        cachedAt = now
        cache
    }

    /** Filters the loaded cache; subclasses with a real search API override this. */
    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val q = query.trim().lowercase()
        return loaded(force = false).filter { item ->
            item.title?.lowercase()?.contains(q) == true ||
                item.creator?.lowercase()?.contains(q) == true ||
                item.tags.any { it.contains(q) }
        }.pageAt(cursor, pageSize)
    }
}
