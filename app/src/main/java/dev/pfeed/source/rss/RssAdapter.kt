package dev.pfeed.source.rss

import dev.pfeed.source.net.attempt
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.SourceAdapter
import dev.pfeed.source.SourceException
import dev.pfeed.source.pageAt
import dev.pfeed.source.sha1Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generic RSS/Atom adapter. The user pastes feed URLs in Settings → Sources. Only feeds the user adds
 * are contacted. Items without playable media (or an official media:player embed) are skipped.
 */
@Singleton
class RssAdapter @Inject constructor(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) : SourceAdapter {
    override val id = "rss"
    override val displayName = "RSS / Atom"
    override val requiresAuth = false

    private val mutex = Mutex()
    private var cache: List<MediaItem> = emptyList()
    private var cachedFeeds: List<String> = emptyList()
    private var cachedAt = 0L

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun fetchFeed(cursor: String?, prefs: FeedPrefs): Page<MediaItem> {
        var items = load(forceRefresh = cursor == null)
        prefs.tag?.let { tag -> items = items.filter { item -> item.tags.any { it.equals(tag, ignoreCase = true) } } }
        return items.pageAt(cursor, PAGE_SIZE)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val q = query.trim().lowercase()
        return load(forceRefresh = false).filter { item ->
            item.title?.lowercase()?.contains(q) == true ||
                item.creator?.lowercase()?.contains(q) == true ||
                item.tags.any { it.lowercase().contains(q) }
        }.pageAt(cursor, PAGE_SIZE)
    }

    override suspend fun resolveStream(item: MediaItem): StreamInfo = when (item.type) {
        MediaType.EMBED -> StreamInfo.Embed(item.extras["player"] ?: item.pageUrl)
        else -> StreamInfo.Direct(
            url = item.extras["media"] ?: throw SourceException(id, "Item has no media URL"),
            mimeType = item.extras["mime"],
        )
    }

    override fun reportUrl(item: MediaItem): String? = item.pageUrl.takeIf { it.startsWith("http") }

    private suspend fun load(forceRefresh: Boolean): List<MediaItem> = mutex.withLock {
        val feeds = settings.get().rssFeeds
        val now = System.currentTimeMillis()
        val fresh = feeds == cachedFeeds && now - cachedAt < if (forceRefresh) MIN_REFRESH_MS else CACHE_MS
        if (fresh) return cache
        if (feeds.isEmpty()) {
            cache = emptyList(); cachedFeeds = feeds; cachedAt = now
            return cache
        }
        val results = coroutineScope {
            feeds.map { url -> async(Dispatchers.IO) { attempt { fetchOne(url) } } }.awaitAll()
        }
        val ok = results.mapNotNull { it.getOrNull() }
        if (ok.isEmpty()) {
            val first = results.firstNotNullOfOrNull { it.exceptionOrNull() }
            throw SourceException(id, "Couldn't load any feed: ${first?.message ?: "unknown error"}", cause = first)
        }
        cache = ok.flatten().distinctBy { it.id }.sortedByDescending { it.publishedAt }
        cachedFeeds = feeds
        cachedAt = now
        cache
    }

    private suspend fun fetchOne(feedUrl: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val base = feedUrl.toHttpUrlOrNull() ?: throw IOException("Invalid URL: $feedUrl")
        val request = Request.Builder().url(base).header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${base.host}")
            val body = response.body ?: throw IOException("Empty body from ${base.host}")
            val parsed = FeedParser.parse(body.byteStream())
            fun abs(u: String?): String? = u?.let { base.resolve(it)?.toString() }
            parsed.entries.mapNotNull { e ->
                val mediaUrl = abs(e.mediaUrl)
                val playerUrl = abs(e.playerUrl)
                val thumb = abs(e.thumbnail)
                val type = FeedParser.classify(mediaUrl, e.mediaMime, e.mediaMedium)
                    ?: if (playerUrl != null) MediaType.EMBED else if (thumb != null) MediaType.IMAGE else return@mapNotNull null
                val media = when {
                    type == MediaType.EMBED -> null
                    mediaUrl != null && FeedParser.classify(mediaUrl, e.mediaMime, e.mediaMedium) != null -> mediaUrl
                    else -> thumb
                }
                val page = abs(e.link) ?: mediaUrl ?: playerUrl ?: feedUrl
                MediaItem(
                    id = sha1Hex(feedUrl + "|" + (e.id ?: e.link ?: mediaUrl ?: playerUrl ?: e.title.orEmpty())),
                    sourceId = id,
                    type = type,
                    title = e.title,
                    creator = e.author ?: parsed.title,
                    tags = e.categories,
                    durationSec = e.durationSec,
                    thumbnailUrl = thumb ?: if (type == MediaType.IMAGE || type == MediaType.GIF) media else null,
                    pageUrl = page,
                    publishedAt = e.published,
                    extras = buildMap {
                        media?.let { put("media", it) }
                        playerUrl?.let { put("player", it) }
                        e.mediaMime?.let { put("mime", it) }
                    },
                )
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val CACHE_MS = 10 * 60_000L
        private const val MIN_REFRESH_MS = 60_000L
    }
}
