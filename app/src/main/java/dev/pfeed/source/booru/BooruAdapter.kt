package dev.pfeed.source.booru

import dev.pfeed.data.repo.BooruKeys
import dev.pfeed.data.settings.BooruFeed
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.filter.MinorsFilter
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.model.StreamInfo
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

    /** Cache of post-page → resolved file URL, so replays and re-renders don't re-fetch. */
    private val resolvedCache = java.util.concurrent.ConcurrentHashMap<String, StreamInfo>()

    /**
     * HTML-fallback items (rule34/gelbooru without a key) carry only a still thumbnail. When one is
     * actually viewed, fetch its post page and pull out the real file: a `<video>`/`<source>` for
     * clips, the full-size `<img id="image">` or "Original image" link for stills. This is what makes
     * those posts play/show full-size instead of sitting on a single frame.
     */
    override suspend fun resolveStream(item: MediaItem): StreamInfo {
        if (item.extras["resolve"] != "page") return super.resolveStream(item)
        val page = item.pageUrl.takeIf { it.startsWith("http") } ?: return super.resolveStream(item)
        resolvedCache[page]?.let { return it }
        val resolved = runCatching { resolvePostPage(page) }.getOrNull() ?: run {
            // Couldn't fetch/parse the page: fall back to the thumbnail so something still shows.
            return super.resolveStream(item)
        }
        resolvedCache[page] = resolved
        return resolved
    }

    private suspend fun resolvePostPage(page: String): StreamInfo {
        val html = http.getRaw(id, page)
        // Video first: <source src> / <video ... src>. Then full image: #image, "Original image" link.
        val video = VIDEO_SRC_RE.find(html)?.groupValues?.get(1)
        if (video != null) return StreamInfo.Direct(absolute(video, page), mimeType = null)
        val original = ORIGINAL_LINK_RE.find(html)?.groupValues?.get(1)
            ?: IMAGE_SRC_RE.find(html)?.groupValues?.get(1)
            ?: throw SourceException(id, "No media on post page")
        return StreamInfo.Direct(absolute(original, page), mimeType = null)
    }

    private fun absolute(url: String, page: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        url.startsWith("/") -> "https://" + BooruFamily.bareHost(page).removePrefix("api.") + url
        else -> url
    }

    override suspend fun entries(): List<String> =
        settings.get().booruFeeds
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

    /**
     * Fetch posts from one board. Strategy:
     * 1. If the user saved an API key, use it (higher rate limits, reliable).
     * 2. Otherwise try the API without a key (works on many boards, and some gelbooru-engine boards
     *    allow limited anonymous access).
     * 3. If the keyless API call gets a non-JSON response (how rule34/gelbooru say "need a key"),
     *    fall back to scraping the public HTML search page, which doesn't need auth.
     */
    private suspend fun fetchPosts(host: String, tags: String?, page: Int): List<MediaItem> {
        val bare = BooruFamily.bareHost(host)
        val creds = credentialParams(bare)
        val url = postsUrl(bare, tags, page) + creds

        return try {
            BooruParser.parse(http.get(id, url), bare)
        } catch (e: SourceException) {
            // API refused (non-JSON error or 403) and we had no key → try the HTML fallback.
            val isAuthIssue = e.isAuthError || e.message?.contains("non-JSON") == true
            if (isAuthIssue && creds.isEmpty() && BooruFamily.of(bare) == BooruFamily.GELBOORU) {
                fetchPostsHtml(bare, tags, page)
            } else if (isAuthIssue && creds.isNotEmpty()) {
                throw SourceException(id, "$bare rejected the API key. Check it in Settings → Sources.", isAuthError = true, cause = e)
            } else {
                throw e
            }
        }
    }

    /**
     * HTML fallback for gelbooru-engine boards (rule34.xxx, gelbooru.com) that reject keyless API
     * calls. Parses the public search page to extract thumbnail URLs and post IDs, then fetches
     * the full-size image URL from each post's HTML page in the background. Only the thumbnail is
     * available immediately; the full image resolves on play.
     */
    private suspend fun fetchPostsHtml(host: String, tags: String?, page: Int): List<MediaItem> {
        val base = "https://$host"
        val enc = tags?.trim().orEmpty().let { if (it.isEmpty()) "" else URLEncoder.encode(it, "UTF-8") }
        val tagsParam = if (enc.isEmpty()) "" else "&tags=$enc"
        val url = "$base/index.php?page=post&s=list&pid=${page * 42}$tagsParam"

        val html = try {
            http.getRaw(id, url)
        } catch (e: SourceException) {
            throw e
        }
        return parseHtmlThumbnails(html, host)
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
            BooruFamily.SANKAKU -> "$base/posts/keyset?limit=40&page=${page + 1}$tagsParam"
            BooruFamily.DANBOORU -> "$base/posts.json?limit=40&page=${page + 1}$tagsParam"
        }
    }

    /**
     * Parses post thumbnails from a gelbooru-engine HTML listing page. The thumbnails live in
     * `<span class="thumb">` blocks, each containing an `<a href>` (post page link) and an
     * `<img src>` (thumbnail URL). From these we build a MediaItem whose `extras["media"]` points
     * to the thumbnail initially; the full-size URL resolves on play via the post page.
     *
     * This is the fallback when the JSON API needs a key the user hasn't provided. It's less data
     * than the API (no tags, no full-size URL until play time) but it gets content on screen.
     */
    private fun parseHtmlThumbnails(html: String, host: String): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        // Pattern: <span ...class="thumb"...><a id="p{id}" href="index.php?page=post&s=view&id={id}"><img ...src="{thumb}"...></a></span>
        val thumbRegex = Regex(
            """<span[^>]*class="thumb"[^>]*>.*?<a[^>]*href="[^"]*[?&]id=(\d+)[^"]*"[^>]*>.*?<img[^>]*src="([^"]+)"[^>]*>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val tagRegex = Regex("""alt="[^"]*\btags=([^"&]+)""")
        val titleRegex = Regex("""title="([^"]+)"""")
        for (m in thumbRegex.findAll(html)) {
            val postId = m.groupValues[1]
            val thumbUrl = m.groupValues[2].let {
                if (it.startsWith("//")) "https:$it" else it
            }
            // Try to extract tags from alt/title attributes in the same <span> block
            val block = m.value
            val tagsRaw = tagRegex.find(block)?.groupValues?.get(1)
                ?: titleRegex.find(block)?.groupValues?.get(1)
            val tags = tagsRaw?.split(Regex("[\\s+]+"))?.filter { it.isNotBlank() }?.take(50).orEmpty()

            // The listing only gives a still thumbnail, so classify by the post's tags instead of the
            // thumbnail extension: video/webm/mp4 → VIDEO, (animated) gif → GIF, else IMAGE. The real
            // file URL is fetched from the post page on play (resolveStream below).
            val lower = tags.map { it.lowercase() }
            val type = when {
                lower.any { it == "video" || it == "webm" || it == "mp4" || it == "animated" || it == "sound" } ->
                    dev.pfeed.domain.model.MediaType.VIDEO
                lower.any { it == "gif" || it == "animated_gif" } -> dev.pfeed.domain.model.MediaType.GIF
                else -> dev.pfeed.domain.model.MediaType.IMAGE
            }
            items += MediaItem(
                id = "$host/$postId",
                sourceId = BooruParser.SOURCE_ID,
                type = type,
                title = null,
                creator = null,
                tags = tags,
                durationSec = null,
                thumbnailUrl = thumbUrl,
                pageUrl = BooruFamily.postPage(host, postId),
                publishedAt = null,
                // Marked for lazy resolution: resolveStream fetches the post page for the full file.
                extras = mapOf("media" to thumbUrl, "resolve" to "page", "host" to host),
            )
        }
        return items
    }

    companion object {
        private val VIDEO_SRC_RE = Regex("""<(?:source|video)[^>]*\bsrc="([^"]+\.(?:mp4|webm|m4v)[^"]*)"""", RegexOption.IGNORE_CASE)
        private val ORIGINAL_LINK_RE = Regex("""<a[^>]*\bhref="([^"]+)"[^>]*>\s*Original image""", RegexOption.IGNORE_CASE)
        private val IMAGE_SRC_RE = Regex("""<img[^>]*\bid="image"[^>]*\bsrc="([^"]+)"""", RegexOption.IGNORE_CASE)
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
