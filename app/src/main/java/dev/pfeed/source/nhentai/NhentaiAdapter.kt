package dev.pfeed.source.nhentai

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
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * nhentai: doujinshi galleries through the site's JSON API. Every gallery is one comic you page
 * through. Entry = "query|sort" (query uses the site's syntax: `tag:milf language:english`).
 *
 * The site tags galleries that depict minors ("lolicon", "shotacon") explicitly; those are excluded
 * in every query and, as a second line, by the always-on minors filter on the gallery's tags.
 */
@Singleton
class NhentaiAdapter @Inject constructor(private val http: HttpJson) : CachingAdapter() {
    override val id = SOURCE_ID
    override val displayName = "nhentai"
    override val requiresAuth = false
    override val maxConcurrency = 2
    override val entriesPerExtend = 3
    override fun groupOf(entry: String) = "nhentai.net"

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> = SourceCatalog.nhentaiQueries

    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val query = entry.substringBefore('|')
        val sort = entry.substringAfter('|', "popular")
        val page = token?.toIntOrNull() ?: 1
        val root = http.get(id, searchUrl("$query $EXCLUDE", sort, page))
        val items = enrich(parse(root))
        val pages = ((root as? JsonObject)?.get("num_pages") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
        val next = if (items.isEmpty() || page >= pages || page >= MAX_PAGES) null else (page + 1).toString()
        return EntryPage(items, next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        (runCatching { enrich(parse(http.get(id, searchUrl("$q $EXCLUDE", "popular", 1)))) }.getOrDefault(emptyList()) + poolMatches(query))
            .distinctBy { it.key }
    }

    /**
     * v2 listings carry only ids, titles, thumb and page count; tags and per-page image types come from
     * the gallery endpoint. Fetch those a few at a time; a gallery whose detail fails keeps the listing's
     * guess (every page the thumb's format) so the comic still opens.
     */
    private suspend fun enrich(items: List<MediaItem>): List<MediaItem> = coroutineScope {
        val gate = Semaphore(DETAIL_CONCURRENCY)
        items.map { item ->
            async {
                if (item.tags.size > TAGLESS_MARKER) item else gate.withPermit {
                    runCatching { (http.get(id, "https://nhentai.net/api/v2/galleries/${item.id}") as? JsonObject)?.let(::gallery) }
                        .getOrNull() ?: item
                }
            }
        }.awaitAll()
    }

    companion object {
        const val SOURCE_ID = "nhentai"
        private const val MAX_PAGES = 25
        internal const val EXCLUDE = "-tag:lolicon -tag:shotacon"

        private const val DETAIL_CONCURRENCY = 4
        /** From the site's /api/v2/config image_servers. */
        private const val IMAGE_SERVER = "https://i1.nhentai.net"
        /** Items parsed from a bare listing carry only the two fixed tags. */
        private const val TAGLESS_MARKER = 2
        private val EXT = mapOf("j" to "jpg", "p" to "png", "g" to "gif", "w" to "webp")

        internal fun searchUrl(query: String, sort: String, page: Int) =
            "https://nhentai.net/api/v2/galleries?query=${URLEncoder.encode(query.trim(), "UTF-8")}&sort=$sort&page=$page"

        /** Galleries from a search / listing response. */
        internal fun parse(root: JsonElement): List<MediaItem> {
            val list = (root as? JsonObject)?.get("result") as? JsonArray ?: (root as? JsonArray) ?: return emptyList()
            return list.mapNotNull { (it as? JsonObject)?.let(::gallery) }
        }

        internal fun gallery(g: JsonObject): MediaItem? {
            fun s(k: String) = (g[k] as? JsonPrimitive)?.contentOrNull
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
