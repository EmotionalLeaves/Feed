package dev.pfeed.source.booru

import dev.pfeed.data.repo.BooruKeys
import dev.pfeed.data.settings.BooruFeed
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.filter.MinorsFilter
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.source.SourceException
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.parse.BooruFamily
import dev.pfeed.source.parse.BooruParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Reads image-board APIs across engines (Gelbooru, moebooru, Sankaku, Danbooru/e621; see [BooruFamily]).
 * The user adds a host and optional tag filter; entries are encoded as JSON so the caching base can key on them.
 *
 * rule34.xxx and gelbooru.com refuse anonymous API calls: their feeds are only fetched once the user
 * has saved an API key for them ([BooruKeys]); until then they're skipped rather than failing.
 */
@Singleton
class BooruAdapter @Inject constructor(
    private val settings: SettingsRepository,
    private val http: HttpJson,
    private val json: Json,
    private val keys: BooruKeys,
) : CachingAdapter() {
    override val id = BooruParser.SOURCE_ID
    override val displayName = "Booru (image boards)"
    override val requiresAuth = false
    // Booru servers vary widely in capacity; keep concurrent load low to avoid timeouts
    override val maxConcurrency = 6
    override val entriesPerExtend = 14

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> =
        settings.get().booruFeeds
            .filterNot { keys.missing(it.host) }
            .map { json.encodeToString(BooruFeed.serializer(), it) }

    /**
     * Token = page index (0-based). A fresh start opens on a random page among the first few, so
     * each launch doesn't open on the same newest posts; if that page is past the end of a small
     * tag, it falls back to the first page.
     */
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val feed = json.decodeFromString(BooruFeed.serializer(), entry)
        val start = token?.toIntOrNull() ?: Random.nextInt(0, RANDOM_START_PAGES)
        var page = start
        var items = fetchPosts(feed.host, feed.tags, page)
        if (items.isEmpty() && token == null && start > 0) {
            page = 0
            items = fetchPosts(feed.host, feed.tags, page)
        }
        val next = if (items.isEmpty() || page + 1 >= MAX_PAGES) null else (page + 1).toString()
        return EntryPage(items, next)
    }

    /**
     * Searches every configured board in parallel (each with its own deadline, so one slow board
     * doesn't sink the rest), plus anything already loaded in the feed. Multi-word queries become
     * one board tag ("rough sex" → rough_sex), which is how boards name tags.
     */
    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val tag = toTag(query)
        if (tag.isEmpty()) return@cachedSearch emptyList()
        val hosts = settings.get().booruFeeds.map { BooruFamily.bareHost(it.host) }.distinct()
            .filterNot { keys.missing(it) }
        // "rough sex" → the tag rough_sex, and also rough + sex as two tags (boards AND them;
        // Danbooru allows two tags for anonymous users, so at most two words).
        val words = query.trim().lowercase().removePrefix("#").split(Regex("\\s+")).filter { it.isNotBlank() }
        val queries = buildList {
            add(tag)
            if (words.size >= 2) add(words.take(2).joinToString(" "))
        }.distinct()
        val remote = coroutineScope {
            hosts.flatMap { host -> queries.map { host to it } }.map { (host, q) ->
                async {
                    withTimeoutOrNull(SEARCH_HOST_TIMEOUT_MS) {
                        runCatching { fetchPosts(host, q, 0) }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
        }
        val merged = (interleaveLists(remote) + poolMatches(query)).distinctBy { it.key }
        merged
    }

    private suspend fun fetchPosts(host: String, tags: String?, page: Int): List<MediaItem> {
        val bare = BooruFamily.bareHost(host)
        val url = postsUrl(bare, tags, page) + credentialParams(bare)
        val root = try {
            http.get(id, url)
        } catch (e: SourceException) {
            // These boards answer a missing/wrong key with a plain-text error instead of JSON or a 401.
            if (bare in BooruKeys.NEEDS_KEY && (e.isAuthError || e.message?.contains("non-JSON") == true)) {
                throw SourceException(id, "$bare rejected the API key. Check it in Settings → Sources.", isAuthError = true, cause = e)
            }
            throw e
        }
        return BooruParser.parse(root, bare)
    }

    private suspend fun credentialParams(host: String): String {
        val key = keys.get(host) ?: return ""
        return "&api_key=" + URLEncoder.encode(key.apiKey, "UTF-8") + "&user_id=" + URLEncoder.encode(key.userId, "UTF-8")
    }

    /**
     * API URL for the board's engine and page:
     * - Gelbooru engine: /index.php?page=dapi…&pid=N (0-based)
     * - moebooru (konachan, yande.re): /post.json?page=N (1-based)
     * - Sankaku: /post/index.json?page=N (1-based)
     * - Danbooru/e621 and others: /posts.json?page=N (1-based)
     *
     * Boards that accept long tag queries also get negative tags from [MinorsFilter] so those posts
     * aren't fetched at all; Danbooru caps anonymous searches at 2 tags, so there the post filter
     * does the work instead.
     */
    private fun postsUrl(host: String, tags: String?, page: Int): String {
        val family = BooruFamily.of(host)
        val base = BooruFamily.apiBase(host)
        val longQueries = family == BooruFamily.GELBOORU || host.endsWith("e621.net") || host.endsWith("e926.net")
        val query = listOfNotNull(
            tags?.trim()?.takeIf { it.isNotEmpty() },
            MinorsFilter.booruNegativeTags.takeIf { longQueries },
        ).joinToString(" ")
        val tagsParam = if (query.isEmpty()) "" else "&tags=" + URLEncoder.encode(query, "UTF-8")
        return when (family) {
            BooruFamily.GELBOORU -> "$base/index.php?page=dapi&s=post&q=index&json=1&limit=40&pid=$page$tagsParam"
            BooruFamily.MOEBOORU -> "$base/post.json?limit=40&page=${page + 1}$tagsParam"
            BooruFamily.SANKAKU -> "$base/post/index.json?limit=40&page=${page + 1}$tagsParam"
            BooruFamily.DANBOORU -> "$base/posts.json?limit=40&page=${page + 1}$tagsParam"
        }
    }

    companion object {
        private const val RANDOM_START_PAGES = 4
        /** Most boards refuse deep pages for anonymous users (Danbooru: 1000); stop well before. */
        private const val MAX_PAGES = 200
        private const val SEARCH_HOST_TIMEOUT_MS = 6_000L

        internal fun toTag(query: String): String =
            query.trim().lowercase().removePrefix("#").replace(Regex("\\s+"), "_")

        internal fun <T> interleaveLists(lists: List<List<T>>): List<T> {
            val out = ArrayList<T>(lists.sumOf { it.size })
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (l in lists) if (i < l.size) out += l[i]
            return out
        }
    }
}
