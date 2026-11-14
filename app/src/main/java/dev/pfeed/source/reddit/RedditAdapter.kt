package dev.pfeed.source.reddit

import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.SourceException
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.parse.RedditParser
import dev.pfeed.source.redgifs.RedgifsAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads public subreddit listings via Reddit's Atom feeds (`/r/{sub}/{listing}/.rss`).
 *
 * Reddit shut down unauthenticated `.json` endpoints in May 2026; the feeds still work and take the
 * same `limit` / `after` parameters, so each subreddit pages deep instead of stopping at 25 posts.
 * Parsing (what each post really links to: full-size image, v.redd.it stream, RedGIFs clip…) lives
 * in [RedditRss]. RedGIFs links are resolved through [RedgifsAdapter] when played.
 */
@Singleton
class RedditAdapter @Inject constructor(
    private val settings: SettingsRepository,
    private val http: HttpJson,
    private val redgifs: RedgifsAdapter,
) : CachingAdapter() {
    override val id = RedditParser.SOURCE_ID
    override val displayName = "Reddit"
    override val requiresAuth = false
    // Reddit rate-limits anonymous readers hard (429 after a handful of quick requests, checked
    // live). Each request already brings up to 50 posts, so ask for few subreddits at a time.
    override val maxConcurrency = 1
    override val entriesPerExtend = 2
    // Requests wait their turn (one every 3 s); allow for that on top of the request itself.
    override val entryTimeoutMs = 20_000L
    // All subreddits are one site: when Reddit starts answering 429, rest it as a whole.
    override fun groupOf(entry: String) = "reddit.com"

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> = settings.get().redditSubs

    private val pace = kotlinx.coroutines.sync.Mutex()
    private var lastRequestAt = 0L

    /**
     * Reddit's anonymous budget is a few hundred requests per ten minutes per address, and it answers
     * 429 well before the feed would notice on its own; one request every 3 s stays inside it. Each
     * brings up to 100 posts, far more than anyone scrolls.
     */
    private suspend fun paced(url: String): String = pace.withLock {
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        try { http.getRaw(id, url) } finally { lastRequestAt = System.currentTimeMillis() }
    }

    /** Token = "listing|after|depth", so each subreddit keeps paging the same listing deeper. */
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val sub = entry.trim().removePrefix("/").removePrefix("r/").trim('/')
        if (sub.isEmpty()) return EntryPage(emptyList(), null)

        val parts = token?.split('|')
        val listing = parts?.getOrNull(0)?.takeIf { it in LISTINGS } ?: LISTINGS.random()
        val after = parts?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val depth = parts?.getOrNull(2)?.toIntOrNull() ?: 0
        val xml = try {
            paced(listingUrl(sub, listing, after))
        } catch (e: SourceException) {
            // "top"/"new" can fail on tiny or quarantined subs; fall back to hot once on a fresh start.
            if (token == null && listing != "hot") {
                runCatching { paced(listingUrl(sub, "hot", null)) }.getOrNull()
                    ?.let { return page(it, sub, "hot", depth = 0, previousAfter = null) }
            }
            throw e
        }
        return page(xml, sub, listing, depth, previousAfter = after)
    }

    private fun page(xml: String, sub: String, listing: String, depth: Int, previousAfter: String?): EntryPage {
        val items = RedditRss.parse(xml, sub)
        val last = RedditRss.lastId(xml)
        // Stop when the feed ends, when it ignored `after` (same last post again), or deep enough.
        val next = if (last == null || last == previousAfter || depth + 1 >= MAX_DEPTH) null else "$listing|$last|${depth + 1}"
        return EntryPage(items, next)
    }

    private fun listingUrl(sub: String, listing: String, after: String?): String {
        val t = if (listing == "top") "&t=${TOP_WINDOWS.random()}" else ""
        val a = after?.let { "&after=$it" } ?: ""
        return "https://$HOST/r/$sub/$listing/.rss?limit=$PAGE$t$a"
    }

    override suspend fun resolveStream(item: MediaItem): StreamInfo {
        if (item.extras["resolve"] == "redgifs") {
            val gid = item.extras["gid"] ?: throw SourceException(id, "Missing RedGIFs id")
            val url = redgifs.directUrl(gid) ?: throw SourceException(id, "RedGIFs clip unavailable")
            return StreamInfo.Direct(url, mimeType = null)
        }
        return super.resolveStream(item)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        val items = runCatching {
            RedditRss.parse(paced("https://$HOST/search/.rss?q=$enc&sort=relevance&t=all&include_over_18=on&limit=100"), "search")
        }.getOrDefault(emptyList())
        // Also search inside the configured subs, a few dozen per multireddit request.
        val subs = entries().map { it.trim().removePrefix("/").removePrefix("r/").trim('/') }.filter { it.isNotEmpty() }.distinct()
            .shuffled().take(MAX_SEARCH_SUBS)
        val subResults = coroutineScope {
            subs.chunked(SUBS_PER_SEARCH).map { chunk ->
                async {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        val multi = chunk.joinToString("+")
                        val url = "https://$HOST/r/$multi/search/.rss?q=$enc&restrict_sr=on&sort=relevance&t=all&include_over_18=on&limit=100"
                        // Each result is tagged with its own subreddit (from the entry's <category>).
                        runCatching { RedditRss.parse(paced(url), "search") }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
        }
        (items + subResults.flatten() + poolMatches(query)).distinctBy { it.key }
    }

    companion object {
        /**
         * Feeds are read from old.reddit.com: same Atom feeds, but its own request budget (checked
         * live: www.reddit.com answered 429 with 0 requests left while old.reddit.com had 200).
         */
        private const val HOST = "old.reddit.com"
        private const val MIN_GAP_MS = 3_000L
        private val LISTINGS = listOf("hot", "hot", "top", "new")
        private val TOP_WINDOWS = listOf("week", "month", "year", "all")
        private const val PAGE = 100
        /** Pages deep per subreddit per pass (~500 posts). */
        private const val MAX_DEPTH = 10
        private const val SUBS_PER_SEARCH = 50
        private const val MAX_SEARCH_SUBS = 200
        private const val SEARCH_TIMEOUT_MS = 6_000L
    }
}
