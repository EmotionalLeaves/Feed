package dev.pfeed.source.philomena

import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Philomena-engine boards (the software behind Derpibooru). These have a clean, key-free JSON API
 * and allow explicit content behind an "Everything" filter, which this adapter selects per host.
 *
 *  - derpibooru.org — My Little Pony art, huge and well-tagged.
 *  - furbooru.org — furry art.
 *
 * API: `/api/v1/json/search/images?q={tags}&filter_id={everything}&per_page=50&page={n}&sf={sort}`.
 * Tags are comma-separated; `score.gte:…`/`explicit` narrow it. Each image gives sized
 * representations and a community score we feed into ranking as a quality prior.
 */
@Singleton
class PhilomenaAdapter @Inject constructor(
    private val http: HttpJson,
) : CachingAdapter() {
    override val id = SOURCE_ID
    override val displayName = "Derpibooru / Furbooru"
    override val requiresAuth = false
    override val maxConcurrency = 4
    override val entriesPerExtend = 6

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    /** Entries are "host|query" pairs: every host crossed with a few explicit seed searches. */
    override suspend fun entries(): List<String> =
        HOSTS.keys.flatMap { host -> SEED_QUERIES.map { "$host|$it" } }

    /** Token = page number. */
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val host = entry.substringBefore('|')
        val query = entry.substringAfter('|', "explicit")
        val page = token?.toIntOrNull() ?: Random.nextInt(1, RANDOM_START_PAGES + 1)
        val items = fetch(host, query, page, sort = "wilson_score")
        val next = if (items.isEmpty() || page >= MAX_PAGE) null else (page + 1).toString()
        return EntryPage(items, next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        val remote = HOSTS.keys.flatMap { host ->
            // Combine the user's query with the explicit rating so results are on-topic.
            runCatching { fetch(host, "explicit, $q", 1, sort = "score") }.getOrDefault(emptyList())
        }
        (remote + poolMatches(query)).distinctBy { it.key }
    }

    private suspend fun fetch(host: String, query: String, page: Int, sort: String): List<MediaItem> {
        val filter = HOSTS[host] ?: return emptyList()
        val url = "https://$host/api/v1/json/search/images?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&filter_id=$filter&per_page=$PER_PAGE&page=$page&sf=$sort&sd=desc"
        val root = http.get(id, url) as? JsonObject ?: return emptyList()
        val images = root["images"] as? JsonArray ?: return emptyList()
        return images.mapNotNull { parseImage(it as? JsonObject ?: return@mapNotNull null, host) }
    }

    private fun parseImage(img: JsonObject, host: String): MediaItem? {
        fun s(key: String) = (img[key] as? JsonPrimitive)?.contentOrNull
        val imgId = s("id") ?: return null
        val reps = img["representations"] as? JsonObject ?: return null
        fun rep(key: String) = (reps[key] as? JsonPrimitive)?.contentOrNull
        val full = rep("full") ?: return null
        val thumb = rep("medium") ?: rep("small") ?: rep("thumb")
        val format = s("format")?.lowercase()
        val type = when (format) {
            "webm", "mp4" -> MediaType.VIDEO
            "gif" -> MediaType.GIF
            else -> MediaType.IMAGE
        }
        // Stills: the 1280 px "large" rendition instead of the original (often several MB); the
        // original is kept for zoom.
        val media = if (type == MediaType.IMAGE) rep("large") ?: full else full
        val tags = (img["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.take(MAX_TAGS).orEmpty()
        val score = (img["score"] as? JsonPrimitive)?.contentOrNull
        val created = s("created_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return MediaItem(
            id = "$host/$imgId",
            sourceId = SOURCE_ID,
            type = type,
            title = null,
            creator = s("uploader")?.takeIf { it.isNotBlank() && it != "Anonymous" },
            tags = tags,
            durationSec = null,
            thumbnailUrl = thumb,
            pageUrl = "https://$host/images/$imgId",
            publishedAt = created,
            extras = buildMap {
                put("media", media)
                if (full != media) put("full", full)
                score?.toLongOrNull()?.let { put("score", it.toString()) }
            },
        )
    }

    companion object {
        const val SOURCE_ID = "philomena"
        private const val PER_PAGE = 50
        private const val RANDOM_START_PAGES = 4
        private const val MAX_PAGE = 100
        private const val MAX_TAGS = 60

        /** host → "Everything" filter id (shows explicit). Derpibooru's is well-known (56027). */
        private val HOSTS = mapOf(
            "derpibooru.org" to "56027",
            "furbooru.org" to "2",
        )
        private val SEED_QUERIES = listOf("explicit", "explicit, bondage", "explicit, bdsm")
    }
}
