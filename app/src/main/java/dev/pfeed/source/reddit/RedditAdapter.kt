package dev.pfeed.source.reddit

import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.pageAt
import dev.pfeed.source.parse.RedditParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads public subreddit listings via Reddit's documented `.json` endpoints (read-only, no auth).
 * The user adds subreddit names in Settings → Sources. See SOURCES.md for the terms note.
 *
 * Reddit rate-limits aggressively (~60 req/min per IP). To avoid 429s:
 * - Cap concurrency to 4 simultaneous requests ([maxConcurrency]).
 * - Sample at most [SUBS_PER_REFRESH] subs per refresh, rotating randomly so all subs are
 *   visited over time without hammering all 130+ at once.
 * - The OkHttp retry interceptor (AppModule) handles residual 429s with Retry-After back-off.
 */
@Singleton
class RedditAdapter @Inject constructor(
    private val settings: SettingsRepository,
    private val http: HttpJson,
) : CachingAdapter() {
    override val id = RedditParser.SOURCE_ID
    override val displayName = "Reddit"
    override val requiresAuth = false
    // Reddit is strict: 4 concurrent max, 90s cache to stay under 60 req/min across refreshes
    override val maxConcurrency = 4
    override val cacheMs = 90_000L

    companion object {
        /** Max subs to fetch per refresh cycle. Rotated randomly across all configured subs. */
        private const val SUBS_PER_REFRESH = 25
    }

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> {
        val all = settings.get().redditSubs
        // Sample randomly so across refreshes we cycle through the whole list
        return if (all.size <= SUBS_PER_REFRESH) all else all.shuffled().take(SUBS_PER_REFRESH)
    }

    override suspend fun fetchEntry(entry: String): List<MediaItem> {
        val sub = entry.trim().removePrefix("/").removePrefix("r/").trim('/')
        if (sub.isEmpty()) return emptyList()
        // Try /top first (more varied content), fall back to /hot
        return runCatching {
            RedditParser.parse(http.get(id, "https://www.reddit.com/r/$sub/top.json?t=week&limit=50&raw_json=1"))
        }.recoverCatching {
            RedditParser.parse(http.get(id, "https://www.reddit.com/r/$sub/hot.json?limit=50&raw_json=1"))
        }.getOrThrow()
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val root = http.get(id, "https://www.reddit.com/search.json?q=$q&sort=top&t=week&limit=50&raw_json=1")
        return RedditParser.parse(root).pageAt(cursor, pageSize)
    }
}
