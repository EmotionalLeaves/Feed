package dev.pfeed.source.reddit

import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.source.SourceException
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.pageAt
import dev.pfeed.source.parse.RedditParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads public subreddit listings via Reddit's `.json` endpoints (read-only, no auth) and pages
 * each subreddit endlessly with Reddit's `after` token.
 *
 * Variety: each subreddit gets a random listing (hot / top of week, month, year, all time) when it
 * starts, so two launches don't open on the same posts. When a listing runs out (Reddit stops at
 * ~1000 posts) the base class starts it over with a new random listing.
 *
 * Rate limits (~60 req/min per IP): at most 4 requests in flight, 10 subreddits per step, and the
 * OkHttp interceptor honours Retry-After on 429.
 */
@Singleton
class RedditAdapter @Inject constructor(
    private val settings: SettingsRepository,
    private val http: HttpJson,
) : CachingAdapter() {
    override val id = RedditParser.SOURCE_ID
    override val displayName = "Reddit"
    override val requiresAuth = false
    override val maxConcurrency = 4
    override val entriesPerExtend = 10

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> = settings.get().redditSubs

    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val sub = entry.trim().removePrefix("/").removePrefix("r/").trim('/')
        if (sub.isEmpty()) return EntryPage(emptyList(), null)

        var listing = token?.substringBefore('|') ?: LISTINGS.random()
        val after = token?.substringAfter('|', "")?.ifBlank { null }
        val root = try {
            http.get(id, url(sub, listing, after))
        } catch (e: SourceException) {
            // A 429 is handled by the interceptor; for other failures on a fresh start, try plain hot once.
            if (after != null || listing == "hot" || e.message?.contains("rate-limiting") == true) throw e
            listing = "hot"
            http.get(id, url(sub, listing, null))
        }
        val next = afterToken(root)
        return EntryPage(RedditParser.parse(root), next?.let { "$listing|$it" })
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val root = http.get(id, "https://www.reddit.com/search.json?q=$q&sort=top&t=week&limit=50&raw_json=1&include_over_18=on")
        return RedditParser.parse(root).pageAt(cursor, pageSize)
    }

    private fun url(sub: String, listing: String, after: String?): String {
        val afterParam = after?.let { "&after=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return when (listing) {
            "hot" -> "https://www.reddit.com/r/$sub/hot.json?limit=50&raw_json=1$afterParam"
            else -> "https://www.reddit.com/r/$sub/top.json?t=${listing.removePrefix("top-")}&limit=50&raw_json=1$afterParam"
        }
    }

    private fun afterToken(root: JsonElement): String? =
        (((root as? JsonObject)?.get("data") as? JsonObject)?.get("after") as? JsonPrimitive)?.contentOrNull

    companion object {
        private val LISTINGS = listOf("hot", "top-week", "top-month", "top-year", "top-all")
    }
}
