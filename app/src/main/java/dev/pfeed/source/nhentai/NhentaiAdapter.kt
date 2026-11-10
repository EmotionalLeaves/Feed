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
        val url = "https://nhentai.net/api/galleries/search?query=${URLEncoder.encode("$query $EXCLUDE", "UTF-8")}&sort=$sort&page=$page"
        val root = http.get(id, url)
        val items = parse(root)
        val pages = ((root as? JsonObject)?.get("num_pages") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
        val next = if (items.isEmpty() || page >= pages || page >= MAX_PAGES) null else (page + 1).toString()
        return EntryPage(items, next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        val url = "https://nhentai.net/api/galleries/search?query=${URLEncoder.encode("$q $EXCLUDE", "UTF-8")}&sort=popular"
        (runCatching { parse(http.get(id, url)) }.getOrDefault(emptyList()) + poolMatches(query)).distinctBy { it.key }
    }

    companion object {
        const val SOURCE_ID = "nhentai"
        private const val MAX_PAGES = 25
        internal const val EXCLUDE = "-tag:lolicon -tag:shotacon"

        private val EXT = mapOf("j" to "jpg", "p" to "png", "g" to "gif", "w" to "webp")

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
            val images = g["images"] as? JsonObject
            val pageTypes = (images?.get("pages") as? JsonArray).orEmpty().map { ((it as? JsonObject)?.get("t") as? JsonPrimitive)?.contentOrNull ?: "j" }
            if (pageTypes.isEmpty()) return null
            val pages = pageTypes.mapIndexed { i, t -> "https://i.nhentai.net/galleries/$mediaId/${i + 1}.${EXT[t] ?: "jpg"}" }
            val coverType = ((images?.get("cover") as? JsonObject)?.get("t") as? JsonPrimitive)?.contentOrNull ?: pageTypes.first()
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
                thumbnailUrl = "https://t.nhentai.net/galleries/$mediaId/thumb.${EXT[coverType] ?: "jpg"}",
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
