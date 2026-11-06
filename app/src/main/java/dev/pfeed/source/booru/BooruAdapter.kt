package dev.pfeed.source.booru

import dev.pfeed.data.settings.BooruFeed
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.filter.MinorsFilter
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.pageAt
import dev.pfeed.source.parse.BooruParser
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Reads Danbooru-style `posts.json` image-board APIs (Danbooru/e621/moebooru-compatible). The user adds
 * a host and optional tag filter; entries are encoded as JSON so the caching base can key on them.
 */
@Singleton
class BooruAdapter @Inject constructor(
    private val settings: SettingsRepository,
    private val http: HttpJson,
    private val json: Json,
) : CachingAdapter() {
    override val id = BooruParser.SOURCE_ID
    override val displayName = "Booru (Danbooru-style)"
    override val requiresAuth = false
    // Booru servers vary widely in capacity; keep concurrent load low to avoid timeouts
    override val maxConcurrency = 6
    override val entriesPerExtend = 14

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> =
        settings.get().booruFeeds.map { json.encodeToString(BooruFeed.serializer(), it) }

    /**
     * Token = page index (0-based). A fresh start opens on a random page among the first few, so
     * each launch doesn't open on the same newest posts; if that page is past the end of a small
     * tag, it falls back to the first page.
     */
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val feed = json.decodeFromString(BooruFeed.serializer(), entry)
        val host = normalizeHost(feed.host)
        val start = token?.toIntOrNull() ?: Random.nextInt(0, RANDOM_START_PAGES)
        var page = start
        var items = BooruParser.parse(http.get(id, postsUrl(host, feed.tags, page)), host)
        if (items.isEmpty() && token == null && start > 0) {
            page = 0
            items = BooruParser.parse(http.get(id, postsUrl(host, feed.tags, page)), host)
        }
        val next = if (items.isEmpty() || page + 1 >= MAX_PAGES) null else (page + 1).toString()
        return EntryPage(items, next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val hosts = settings.get().booruFeeds.map { normalizeHost(it.host) }.distinct()
        val merged = hosts.flatMap { host ->
            runCatching { BooruParser.parse(http.get(id, postsUrl(host, query, 0)), host) }.getOrDefault(emptyList())
        }.distinctBy { it.key }
        return merged.pageAt(cursor, pageSize)
    }

    /**
     * Builds the correct API URL for the board family and page:
     * - Gelbooru-engine boards (rule34.xxx, gelbooru, tbib, xbooru, realbooru, safebooru.org,
     *   hypnohub): /index.php?page=dapi…&pid=N (0-based)
     * - moebooru (konachan, yande.re): /post.json?page=N (1-based)
     * - Sankaku: /post/index.json?page=N (1-based)
     * - Danbooru/e621 and others: /posts.json?page=N (1-based)
     *
     * Boards that accept long tag queries also get negative tags from [MinorsFilter] so those posts
     * aren't fetched at all; Danbooru caps anonymous searches at 2 tags, so there the post filter
     * does the work instead.
     */
    private fun postsUrl(host: String, tags: String?, page: Int): String {
        val h = host.removeSuffix("/")
        val gelbooruEngine = GELBOORU_ENGINE.any { h.contains(it) }
        val longQueries = gelbooruEngine || h.contains("e621.net") || h.contains("e926.net")
        val query = listOfNotNull(
            tags?.trim()?.takeIf { it.isNotEmpty() },
            MinorsFilter.booruNegativeTags.takeIf { longQueries },
        ).joinToString(" ")
        val tagsParam = if (query.isEmpty()) "" else "&tags=" + URLEncoder.encode(query, "UTF-8")
        return when {
            gelbooruEngine -> "$h/index.php?page=dapi&s=post&q=index&json=1&limit=40&pid=$page$tagsParam"
            h.contains("konachan.") || h.contains("yande.re") || h.contains("behoimi") ->
                "$h/post.json?limit=40&page=${page + 1}$tagsParam"
            h.contains("sankakucomplex.com") -> "$h/post/index.json?limit=40&page=${page + 1}$tagsParam"
            else -> "$h/posts.json?limit=40&page=${page + 1}$tagsParam"
        }
    }

    private fun normalizeHost(host: String): String {
        val h = host.trim().removeSuffix("/")
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
    }

    companion object {
        private const val RANDOM_START_PAGES = 4
        /** Most boards refuse deep pages for anonymous users (Danbooru: 1000); stop well before. */
        private const val MAX_PAGES = 200
        private val GELBOORU_ENGINE = listOf(
            "rule34.xxx", "gelbooru.com", "tbib.org", "xbooru.com", "realbooru.com", "safebooru.org", "hypnohub.net",
        )
    }
}
