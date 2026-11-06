package dev.pfeed.source.reddit

import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.source.SourceException
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.parse.RedditParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads public subreddit listings via Reddit's RSS feeds (`.rss` suffix).
 *
 * Reddit shut down unauthenticated `.json` endpoints in May 2026 (all return 403). RSS feeds
 * still work as of November 2026. Each subreddit's feed is available at
 * `https://www.reddit.com/r/{sub}/{listing}/.rss`.
 *
 * The RSS returns Atom XML with embedded `<media:content>` or `<media:thumbnail>` elements,
 * plus an HTML `<content>` block that may contain `<img>` and `<a>` tags. We parse both.
 *
 * RSS has no `after` token for deep paging: each feed returns ~25 items. To keep the stream
 * endless, the base class's recycle mechanism starts entries over with a new random listing.
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

        val listing = token ?: LISTINGS.random()
        val rssUrl = "https://www.reddit.com/r/$sub/$listing/.rss"
        val xml = try {
            http.getRaw(id, rssUrl)
        } catch (e: SourceException) {
            // Try another listing if this one 403s or errors
            if (listing != "hot" && token == null) {
                return try {
                    EntryPage(parseRss(http.getRaw(id, "https://www.reddit.com/r/$sub/hot/.rss"), sub), null)
                } catch (_: SourceException) {
                    EntryPage(emptyList(), null)
                }
            }
            throw e
        }
        val items = parseRss(xml, sub)
        // RSS has no next-page token; return null so the base class cycles to the next listing
        return EntryPage(items, null)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        // Reddit search RSS: /search/.rss?q=...&include_over_18=on
        val searchUrl = "https://www.reddit.com/search/.rss?q=${URLEncoder.encode(q, "UTF-8")}&sort=relevance&t=all&include_over_18=on"
        val items = try {
            parseRss(http.getRaw(id, searchUrl), "search")
        } catch (_: Exception) {
            emptyList()
        }
        // Also search inside saved subs
        val subs = entries().map { it.trim().removePrefix("/").removePrefix("r/").trim('/') }.filter { it.isNotEmpty() }.distinct()
        val subResults = coroutineScope {
            subs.chunked(SUBS_PER_SEARCH).map { chunk ->
                async {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        val multi = chunk.joinToString("+")
                        val url = "https://www.reddit.com/r/$multi/search/.rss?q=${URLEncoder.encode(q, "UTF-8")}&restrict_sr=on&sort=relevance&t=all&include_over_18=on"
                        runCatching { parseRss(http.getRaw(id, url), multi) }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
        }
        val merged = (items + subResults.flatten() + poolMatches(query)).distinctBy { it.key }
        merged
    }

    /**
     * Parses Reddit's Atom/RSS feed XML. Extracts media from:
     * 1. `<media:content url="...">` or `<media:thumbnail url="...">` elements
     * 2. `<img src="...">` inside the HTML `<content>` block (Reddit often puts preview URLs here)
     * 3. Direct links to i.redd.it, i.imgur.com, etc. in `<link>` elements
     */
    private fun parseRss(xml: String, sub: String): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        // Split into entries
        val entries = xml.split("<entry>").drop(1) // first part is feed header
        for (entryXml in entries) {
            val end = entryXml.indexOf("</entry>")
            val entry = if (end >= 0) entryXml.substring(0, end) else entryXml

            val title = extractTag(entry, "title")
            val link = extractAttr(entry, "link", "href")
                ?: extractTag(entry, "link")
            val author = extractTag(entry, "name") // inside <author><name>
            val published = extractTag(entry, "published") ?: extractTag(entry, "updated")
            val id = extractTag(entry, "id") ?: link ?: continue

            // Find media URLs
            val mediaUrls = ArrayList<String>()

            // <media:content url="..."> and <media:thumbnail url="...">
            MEDIA_URL_RE.findAll(entry).forEach { m -> mediaUrls += decodeXml(m.groupValues[1]) }

            // <img src="..."> inside HTML content
            val content = extractTag(entry, "content") ?: ""
            IMG_SRC_RE.findAll(content).forEach { m -> mediaUrls += decodeXml(m.groupValues[1]) }

            // Filter to actual media (images/videos), deduplicate, prefer full-size over preview
            val urls = mediaUrls
                .map { it.replace("&amp;", "&") }
                .filter { isMedia(it) }
                .distinctBy { it.substringAfterLast('/').substringBefore('?') }

            val mediaUrl = urls.firstOrNull { !it.contains("preview.") }
                ?: urls.firstOrNull()
                ?: continue

            val thumb = urls.firstOrNull { it.contains("preview.") || it.contains("thumb") }
                ?: mediaUrl

            val type = when {
                mediaUrl.contains(".gif") -> MediaType.GIF
                mediaUrl.contains(".mp4") || mediaUrl.contains(".webm") -> MediaType.VIDEO
                else -> MediaType.IMAGE
            }

            val publishedAt = try {
                published?.let { Instant.parse(it) }
            } catch (_: Exception) {
                null
            }

            items += MediaItem(
                id = "reddit/${id.substringAfterLast('/').substringBefore('?')}",
                sourceId = RedditParser.SOURCE_ID,
                type = type,
                title = title?.let { decodeXml(it) }?.takeIf { it.length > 3 },
                creator = author?.let { decodeXml(it) },
                tags = listOf(sub),
                durationSec = null,
                thumbnailUrl = thumb,
                pageUrl = link ?: "",
                publishedAt = publishedAt,
                extras = mapOf("media" to mediaUrl),
            )
        }
        return items
    }

    companion object {
        private val LISTINGS = listOf("hot", "top", "new")
        private const val SUBS_PER_SEARCH = 50
        private const val SEARCH_TIMEOUT_MS = 6_000L

        private val MEDIA_URL_RE = Regex("""<media:\w+[^>]*url="([^"]+)"[^/]*/?>""")
        private val IMG_SRC_RE = Regex("""<img[^>]*src="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        private val MEDIA_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "mp4", "webm")

        private fun isMedia(url: String): Boolean {
            if (!url.startsWith("http")) return false
            if (url.contains("reddit.com/r/")) return false // not a media URL
            val ext = url.substringAfterLast('.').substringBefore('?').lowercase()
            return ext in MEDIA_EXT || url.contains("i.redd.it") || url.contains("i.imgur.com")
                || url.contains("preview.redd.it")
        }

        private fun extractTag(xml: String, tag: String): String? {
            val patterns = listOf("<$tag>", "<$tag ")
            for (p in patterns) {
                val start = xml.indexOf(p)
                if (start < 0) continue
                val contentStart = xml.indexOf('>', start) + 1
                val end = xml.indexOf("</$tag>", contentStart)
                if (end < 0) continue
                return xml.substring(contentStart, end).trim()
            }
            return null
        }

        private fun extractAttr(xml: String, tag: String, attr: String): String? {
            val tagStart = xml.indexOf("<$tag ")
            if (tagStart < 0) return null
            val tagEnd = xml.indexOf('>', tagStart)
            if (tagEnd < 0) return null
            val chunk = xml.substring(tagStart, tagEnd)
            val attrStart = chunk.indexOf("$attr=\"")
            if (attrStart < 0) return null
            val valStart = attrStart + attr.length + 2
            val valEnd = chunk.indexOf('"', valStart)
            if (valEnd < 0) return null
            return chunk.substring(valStart, valEnd)
        }

        private fun decodeXml(s: String): String = s
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    }
}
