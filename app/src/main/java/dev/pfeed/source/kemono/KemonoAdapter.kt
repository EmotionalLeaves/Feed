package dev.pfeed.source.kemono

import dev.pfeed.data.settings.DefaultSources
import dev.pfeed.data.settings.SettingsRepository
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

/**
 * coomer.su (OnlyFans / Fansly) and kemono.su (Patreon / Fanbox / Fantia / Gumroad) — two large
 * creator-leak aggregators that share one open JSON API. Both expose a site-wide recent-posts feed
 * at `/api/v1/posts?o={offset}` (50 per page), which is exactly a scrollable feed: paging the offset
 * walks backwards through everything posted, so the stream is effectively endless.
 *
 * Each post can carry a main file plus extra attachments; every image/video among them becomes its
 * own feed item. Files live at `https://{host}/data{path}`, thumbnails at
 * `https://img.{host}/thumbnail/data{path}` (images only).
 *
 * These have no booru-style tags; the creator and service (onlyfans/patreon/…) are used as tags so
 * the recommender can still learn ("more from this creator / this platform").
 */
@Singleton
class KemonoAdapter @Inject constructor(
    private val settings: SettingsRepository,
    private val http: HttpJson,
) : CachingAdapter() {
    override val id = SOURCE_ID
    override val displayName = "Coomer / Kemono"
    override val requiresAuth = false
    override val maxConcurrency = 3
    override val entriesPerExtend = 4

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> =
        settings.get().kemonoHosts.map(::canonicalHost).filterNot { DefaultSources.isSfwOnlyHost(it) }.distinct()

    /** Token = offset into the site-wide recent-posts list, in steps of [PAGE]. */
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val host = canonicalHost(entry)
        if (host.isEmpty()) return EntryPage(emptyList(), null)
        val offset = token?.toIntOrNull() ?: 0
        val root = api("https://$host/api/v1/posts?o=$offset")
        val posts = postsArray(root)
        val items = posts.flatMap { post -> itemsFromPost(host, post) }
        requireFilesReachable(host, items)
        // Stop when the API returns an empty page; otherwise step one page deeper.
        val next = if (posts.isEmpty() || offset >= MAX_OFFSET) null else (offset + PAGE).toString()
        return EntryPage(items, next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        val hosts = entries()
        val remote = hosts.flatMap { host ->
            runCatching {
                val root = api("https://$host/api/v1/posts?q=${URLEncoder.encode(q, "UTF-8")}&o=0")
                postsArray(root).flatMap { itemsFromPost(host, it) }
            }.getOrDefault(emptyList())
        }
        (remote + poolMatches(query)).distinctBy { it.key }
    }

    private val filesOk = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /**
     * The post list and the files live on different servers, and the file servers have long outages
     * while the list keeps answering. Handing out posts whose files can't load just fills the feed
     * with spinners, so check one file (first byte only) every few minutes and report the outage
     * as what it is instead.
     */
    private suspend fun requireFilesReachable(host: String, items: List<MediaItem>) {
        val sample = items.firstOrNull { it.type == MediaType.IMAGE }?.extras?.get("media")
            ?: items.firstOrNull()?.extras?.get("media") ?: return
        val now = System.currentTimeMillis()
        val cached = filesOk[host]?.takeIf { now - it.first < FILE_CHECK_EVERY_MS }
        val ok = cached?.second ?: (kotlinx.coroutines.withTimeoutOrNull(FILE_CHECK_TIMEOUT_MS) { http.reachable(sample) } ?: false)
            .also { filesOk[host] = now to it }
        if (!ok) throw dev.pfeed.source.SourceException(id, "$host lists posts, but its file servers aren't responding (an outage on their side). Retrying every few minutes.")
    }

    /**
     * The API answers JSON only when asked with `Accept: text/css` (an anti-scraping quirk since 2025);
     * fall back to a plain request in case that ever changes back.
     */
    private suspend fun api(url: String): kotlinx.serialization.json.JsonElement =
        try {
            http.get(id, url, mapOf("Accept" to "text/css"))
        } catch (e: dev.pfeed.source.SourceException) {
            runCatching { http.get(id, url) }.getOrElse { throw e }
        }

    private fun postsArray(root: kotlinx.serialization.json.JsonElement): List<JsonObject> = when {
        root is JsonArray -> root.mapNotNull { it as? JsonObject }
        root is JsonObject && root["posts"] is JsonArray -> (root["posts"] as JsonArray).mapNotNull { it as? JsonObject }
        else -> emptyList()
    }

    private fun itemsFromPost(host: String, post: JsonObject): List<MediaItem> {
        fun s(key: String) = (post[key] as? JsonPrimitive)?.contentOrNull

        val postId = s("id") ?: return emptyList()
        val service = s("service")
        val creator = s("user")
        val title = s("title")?.takeIf { it.isNotBlank() }
        val published = s("published")?.let { runCatching { Instant.parse(if (it.endsWith("Z")) it else it + "Z") }.getOrNull() }
        val tags = listOfNotNull(service, creator?.let { "creator:$it" })

        // Collect the main file and any attachments.
        val paths = buildList {
            (post["file"] as? JsonObject)?.get("path")?.let { (it as? JsonPrimitive)?.contentOrNull?.let(::add) }
            (post["attachments"] as? JsonArray)?.forEach { att ->
                ((att as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull?.let(::add)
            }
        }.distinct()

        fun clean(path: String) = if (path.startsWith("/")) path else "/$path"
        // Videos have no server thumbnail. Pointing the thumbnail at the video itself made the image
        // loader download whole clips (often 100+ MB) just to draw a placeholder, several posts ahead.
        // Use a still from the same post if there is one, otherwise nothing.
        val postStill = paths.firstOrNull { classify(it) == MediaType.IMAGE }
            ?.let { "https://img.$host/thumbnail/data${clean(it)}" }

        return paths.mapIndexedNotNull { index, path ->
            val type = classify(path) ?: return@mapIndexedNotNull null // skip non-media (pdfs, zips…)
            val clean = clean(path)
            val media = "https://$host/data$clean"
            val thumb = when (type) {
                MediaType.IMAGE -> "https://img.$host/thumbnail/data$clean"
                MediaType.GIF -> media
                else -> postStill
            }
            MediaItem(
                id = "$host/$postId/$index",
                sourceId = SOURCE_ID,
                type = type,
                title = title,
                creator = creator?.let { "$it@$service" },
                tags = tags,
                durationSec = null,
                thumbnailUrl = thumb,
                pageUrl = "https://$host/$service/user/$creator/post/$postId",
                publishedAt = published,
                extras = mapOf("media" to media),
            )
        }
    }

    private fun classify(path: String): MediaType? {
        val ext = path.substringAfterLast('.', "").substringBefore('?').lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "jpe", "bmp" -> MediaType.IMAGE
            "gif" -> MediaType.GIF
            "mp4", "webm", "m4v", "mov" -> MediaType.VIDEO
            else -> null
        }
    }

    companion object {
        const val SOURCE_ID = "kemono"

        /** Old domains the sites moved away from (coomer.su stopped resolving in mid-2025). */
        private val MOVED = mapOf(
            "coomer.su" to "coomer.st", "coomer.party" to "coomer.st",
            "kemono.su" to "kemono.cr", "kemono.party" to "kemono.cr",
        )

        fun canonicalHost(raw: String): String {
            val h = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.")
            return MOVED[h] ?: h
        }
        private const val PAGE = 50
        private const val FILE_CHECK_EVERY_MS = 5 * 60_000L
        private const val FILE_CHECK_TIMEOUT_MS = 8_000L
        /** Don't page past this; the recent feed that deep is rarely useful and ages out. */
        private const val MAX_OFFSET = 50_000
    }
}
