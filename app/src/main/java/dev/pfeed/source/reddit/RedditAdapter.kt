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
    override val maxConcurrency = 4
    override val entriesPerExtend = 10

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> = settings.get().redditSubs

    /** Token = "listing|after|depth", so each subreddit keeps paging the same listing deeper. */
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val sub = entry.trim().removePrefix("/").removePrefix("r/").trim('/')
        if (sub.isEmpty()) return EntryPage(emptyList(), null)

        val parts = token?.split('|')
        val listing = parts?.getOrNull(0)?.takeIf { it in LISTINGS } ?: LISTINGS.random()
        val after = parts?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val depth = parts?.getOrNull(2)?.toIntOrNull() ?: 0
        val xml = try {
            http.getRaw(id, listingUrl(sub, listing, after))
        } catch (e: SourceException) {
            // "top"/"new" can fail on tiny or quarantined subs; fall back to hot once on a fresh start.
            if (token == null && listing != "hot") {
                runCatching { http.getRaw(id, listingUrl(sub, "hot", null)) }.getOrNull()
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
        return "https://www.reddit.com/r/$sub/$listing/.rss?limit=$PAGE$t$a"
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
            RedditRss.parse(http.getRaw(id, "https://www.reddit.com/search/.rss?q=$enc&sort=relevance&t=all&include_over_18=on&limit=100"), "search")
        }.getOrDefault(emptyList())
        // Also search inside the configured subs, a few dozen per multireddit request.
        val subs = entries().map { it.trim().removePrefix("/").removePrefix("r/").trim('/') }.filter { it.isNotEmpty() }.distinct()
            .shuffled().take(MAX_SEARCH_SUBS)
        val subResults = coroutineScope {
            subs.chunked(SUBS_PER_SEARCH).map { chunk ->
                async {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        val multi = chunk.joinToString("+")
                        val url = "https://www.reddit.com/r/$multi/search/.rss?q=$enc&restrict_sr=on&sort=relevance&t=all&include_over_18=on&limit=100"
                        // Each result is tagged with its own subreddit (from the entry's <category>).
                        runCatching { RedditRss.parse(http.getRaw(id, url), "search") }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
        }
        (items + subResults.flatten() + poolMatches(query)).distinctBy { it.key }
    }

    companion object {
        private val LISTINGS = listOf("hot", "hot", "top", "new")
        private val TOP_WINDOWS = listOf("week", "month", "year", "all")
        private const val PAGE = 50
        /** Pages deep per subreddit per pass (~500 posts). */
        private const val MAX_DEPTH = 10
        private const val SUBS_PER_SEARCH = 50
        private const val MAX_SEARCH_SUBS = 200
        private const val SEARCH_TIMEOUT_MS = 6_000L
    }
}
