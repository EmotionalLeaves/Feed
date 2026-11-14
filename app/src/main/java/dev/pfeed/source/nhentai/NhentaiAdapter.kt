package dev.pfeed.source.nhentai

import dev.pfeed.source.net.attempt
import dev.pfeed.data.settings.SourceCatalog
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * nhentai: doujinshi galleries through the site's JSON API. Every gallery is one comic you page
 * through. Entry = "query|sort" (query uses the site's syntax: `tag:milf language:english`).
 *
 * The site tags galleries that depict minors ("lolicon", "shotacon") explicitly. Those are excluded
 * three ways: by name in every search, by tag id on every listing entry, and by the always-on
 * minors filter on tag names. Search is /api/v2/search: /api/v2/galleries ignores the query.
 */
@Singleton
class NhentaiAdapter @Inject constructor(private val http: HttpJson) : CachingAdapter() {
    override val id = SOURCE_ID
    override val displayName = "nhentai"
    override val requiresAuth = false
    // The API allows about ten searches and fifteen gallery reads a minute per address; one
    // request every 6.5 s keeps well inside both, and each listing brings 25 galleries.
    override val maxConcurrency = 1
    override val entriesPerExtend = 1
    override val entryTimeoutMs = 40_000L
    override fun groupOf(entry: String) = "nhentai.net"

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> = SourceCatalog.nhentaiQueries

    private val pace = kotlinx.coroutines.sync.Mutex()
    private var lastRequestAt = 0L

    private suspend fun paced(url: String): JsonElement = pace.withLock {
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        try { http.get(id, url) } finally { lastRequestAt = System.currentTimeMillis() }
    }

    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val query = entry.substringBefore('|')
        val sort = entry.substringAfter('|', "popular")
        val page = token?.toIntOrNull() ?: 1
        val root = paced(searchUrl("$query $EXCLUDE", sort, page))
        val items = enrich(parse(root))
        val pages = ((root as? JsonObject)?.get("num_pages") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
        val next = if (items.isEmpty() || page >= pages || page >= MAX_PAGES) null else (page + 1).toString()
        return EntryPage(items, next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        (attempt { enrich(parse(paced(searchUrl("$q $EXCLUDE", "popular", 1)))) }.getOrDefault(emptyList()) + poolMatches(query))
            .distinctBy { it.key }
    }

    /**
     * Listings carry ids, titles, the thumbnail, page count and tag ids, not tag names or each page's
     * file. The first few galleries of a listing are read in full (names for For You, exact page
     * files); the rest open with every page in the thumbnail's format, which is what the site uses
     * for current uploads. Reading all 25 would break the site's rate limit.
     */
    private suspend fun enrich(items: List<MediaItem>): List<MediaItem> = items.mapIndexed { i, item ->
        if (i >= ENRICH_PER_PAGE || item.tags.size > TAGLESS_MARKER) item
        else attempt { (paced("https://nhentai.net/api/v2/galleries/${item.id}") as? JsonObject)?.let(::gallery) }.getOrNull() ?: item
    }.filterNotNull()

    companion object {
        const val SOURCE_ID = "nhentai"
        private const val MAX_PAGES = 25
        internal const val EXCLUDE = "-tag:lolicon -tag:shotacon"

        private const val ENRICH_PER_PAGE = 2
        private const val MIN_GAP_MS = 6_500L

        /**
         * Tag ids of "lolicon" and "shotacon" (looked up from /api/v2/tags/tag/{slug}). Listings
         * give tag ids, not names; a gallery carrying either is dropped before anything else.
         * Every query also excludes them by name ([EXCLUDE]), and the minors filter checks names.
         */
        internal val MINOR_TAG_IDS = setOf(19440L, 32341L)
        /** From the site's /api/v2/config image_servers. */
        private const val IMAGE_SERVER = "https://i1.nhentai.net"
        /** Items parsed from a bare listing carry only the two fixed tags. */
        private const val TAGLESS_MARKER = 2
        private val EXT = mapOf("j" to "jpg", "p" to "png", "g" to "gif", "w" to "webp")

        internal fun searchUrl(query: String, sort: String, page: Int) =
            "https://nhentai.net/api/v2/search?query=${URLEncoder.encode(query.trim(), "UTF-8")}&sort=$sort&page=$page"

        /** Galleries from a search / listing response. */
        internal fun parse(root: JsonElement): List<MediaItem> {
            val list = (root as? JsonObject)?.get("result") as? JsonArray ?: (root as? JsonArray) ?: return emptyList()
            return list.mapNotNull { (it as? JsonObject)?.let(::gallery) }
        }

        internal fun gallery(g: JsonObject): MediaItem? {
            fun s(k: String) = (g[k] as? JsonPrimitive)?.contentOrNull
            val tagIds = (g["tag_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } +
                (g["tags"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
            if (tagIds.any { it in MINOR_TAG_IDS }) return null
            val gid = s("id") ?: return null
            val mediaId = s("media_id") ?: return null
            val titles = g["title"] as? JsonObject
            val title = listOf("pretty", "english", "japanese").firstNotNullOfOrNull { (titles?.get(it) as? JsonPrimitive)?.contentOrNull?.takeIf { t -> t.isNotBlank() } }
                ?: listOf("english_title", "pretty_title", "japanese_title").firstNotNullOfOrNull { s(it)?.takeIf { t -> t.isNotBlank() } }
            val images = g["images"] as? JsonObject
            // v2 listing: "thumbnail":"galleries/123/thumb.webp" + num_pages; detail/v1: images.pages[].t
            val thumbPath = s("thumbnail") ?: ((g["thumbnail"] as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull
            val thumbExt = thumbPath?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
            val pageTypes = (images?.get("pages") as? JsonArray).orEmpty().map { ((it as? JsonObject)?.get("t") as? JsonPrimitive)?.contentOrNull ?: "j" }
                .ifEmpty { List(s("num_pages")?.toIntOrNull() ?: 0) { thumbExt?.let { e -> EXT.entries.firstOrNull { it.value == e }?.key } ?: "w" } }
            // v2 detail: "pages":[{"path":"galleries/123/1.webp",…}], the exact file of every page.
            val exact = (g["pages"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull }
                .map { "$IMAGE_SERVER/${it.trimStart('/')}" }
            if (pageTypes.isEmpty() && exact.isEmpty()) return null
            val pages = exact.ifEmpty { pageTypes.mapIndexed { i, t -> "$IMAGE_SERVER/galleries/$mediaId/${i + 1}.${EXT[t] ?: "jpg"}" } }
            val coverType = ((images?.get("cover") as? JsonObject)?.get("t") as? JsonPrimitive)?.contentOrNull
            val thumbUrl = thumbPath?.let { if (it.startsWith("http")) it else "https://t1.nhentai.net/${it.trimStart('/')}" }
                ?: "https://t.nhentai.net/galleries/$mediaId/thumb.${EXT[coverType ?: pageTypes.firstOrNull() ?: "w"] ?: "jpg"}"
            val tagObjs = (g["tags"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val tags = tagObjs.mapNotNull { t ->
                val type = (t["type"] as? JsonPrimitive)?.contentOrNull
                val name = (t["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.replace(' ', '_') ?: return@mapNotNull null
                when (type) {
                    "tag", "category" -> name
                    "artist" -> "artist:$name"
                    "parody" -> "parody:$name"
                    "character" -> "character:$name"
                    "language" -> "language:$name"
                    else -> null
                }
            }
            val artist = tags.firstOrNull { it.startsWith("artist:") }?.removePrefix("artist:")
            val uploaded = (g["upload_date"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { Instant.ofEpochSecond(it) }
            return MediaItem(
                id = gid,
                sourceId = SOURCE_ID,
                type = MediaType.IMAGE,
                title = title,
                creator = artist,
                tags = (listOf("comic", "doujinshi") + tags).distinct(),
                durationSec = null,
                thumbnailUrl = thumbUrl,
                pageUrl = "https://nhentai.net/g/$gid/",
                publishedAt = uploaded,
                extras = buildMap {
                    put("media", pages.first())
                    put("rating", "e")
                    (g["num_favorites"] as? JsonPrimitive)?.contentOrNull?.let { put("score", it) }
                },
                pages = if (pages.size > 1) pages else emptyList(),
            )
        }
    }
}
