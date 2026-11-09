package dev.pfeed.source.redgifs

import dev.pfeed.data.settings.SourceCatalog
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.source.SourceException
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * redgifs.com — one of the largest adult short-video hosts, and the most TikTok-shaped source here:
 * everything is a short looping clip. Its public API needs no account, only a temporary bearer token
 * from `/v2/auth/temporary` (good for hours; re-fetched on expiry).
 *
 * There's no single "everything" feed, so the main stream is built from a set of seed terms (like the
 * board tag feeds), each paged via `/v2/gifs/search?order=trending`. Search and the recommender's
 * interest queries hit the same endpoint, so anything you like is searchable here too.
 */
@Singleton
class RedgifsAdapter @Inject constructor(
    private val http: HttpJson,
) : CachingAdapter() {
    override val id = SOURCE_ID
    override val displayName = "RedGIFs"
    override val requiresAuth = false
    override val maxConcurrency = 4
    override val entriesPerExtend = 8

    private val tokenMutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenAt = 0L

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> = SourceCatalog.redgifsTerms

    private val directCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The playable file for one clip by id — used for RedGIFs links posted on Reddit, which arrive as
     * a watch-page URL. Cached; null if the clip is gone.
     */
    suspend fun directUrl(gid: String): String? {
        val id = gid.lowercase()
        directCache[id]?.let { return it }
        val url = "https://api.redgifs.com/v2/gifs/$id"
        val root = try {
            http.get(this.id, url, authHeaders(token()))
        } catch (e: SourceException) {
            if (e.isAuthError) {
                invalidateToken()
                runCatching { http.get(this.id, url, authHeaders(token())) }.getOrNull()
            } else null
        } ?: return null
        val gif = (root as? JsonObject)?.get("gif") as? JsonObject ?: return null
        val urls = gif["urls"] as? JsonObject ?: return null
        val media = listOf("hd", "sd").firstNotNullOfOrNull { (urls[it] as? JsonPrimitive)?.contentOrNull } ?: return null
        directCache[id] = media
        return media
    }

    /** Token = page number for this seed term. */
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val page = token?.toIntOrNull() ?: Random.nextInt(1, RANDOM_START_PAGES + 1)
        val gifs = searchGifs(entry, page, "trending")
        val next = if (gifs.isEmpty() || page >= MAX_PAGE) null else (page + 1).toString()
        return EntryPage(gifs, next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        // Three pages at once: RedGIFs is usually the richest source of clips for a search.
        val pages = kotlinx.coroutines.coroutineScope {
            (1..SEARCH_PAGES).map { p -> async { runCatching { searchGifs(q, p, "best") }.getOrDefault(emptyList()) } }.awaitAll()
        }
        (pages.flatten() + poolMatches(query)).distinctBy { it.key }
    }

    private suspend fun searchGifs(term: String, page: Int, order: String): List<MediaItem> {
        val url = "https://api.redgifs.com/v2/gifs/search?search_text=${URLEncoder.encode(term, "UTF-8")}" +
            "&order=$order&count=$COUNT&page=$page"
        val root = try {
            http.get(id, url, authHeaders(token()))
        } catch (e: SourceException) {
            // Token likely expired: get a fresh one once and retry.
            if (e.isAuthError) {
                invalidateToken()
                http.get(id, url, authHeaders(token()))
            } else throw e
        }
        val gifs = (root as? JsonObject)?.get("gifs") as? JsonArray ?: return emptyList()
        return gifs.mapNotNull { parseGif(it as? JsonObject ?: return@mapNotNull null) }
    }

    private fun parseGif(g: JsonObject): MediaItem? {
        fun s(key: String) = (g[key] as? JsonPrimitive)?.contentOrNull
        val gid = s("id") ?: return null
        val urls = g["urls"] as? JsonObject ?: return null
        fun u(key: String) = (urls[key] as? JsonPrimitive)?.contentOrNull
        val media = u("hd") ?: u("sd") ?: return null
        val poster = u("poster") ?: u("thumbnail") ?: u("vthumbnail")
        val tags = (g["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.orEmpty()
        val creator = s("userName")?.takeIf { it.isNotBlank() && it != "unknown" }
        val duration = (g["duration"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
        val likes = (g["likes"] as? JsonPrimitive)?.contentOrNull // used as a quality prior
        return MediaItem(
            id = gid,
            sourceId = SOURCE_ID,
            type = MediaType.VIDEO,
            title = null,
            creator = creator,
            tags = tags,
            durationSec = duration,
            thumbnailUrl = poster,
            pageUrl = "https://www.redgifs.com/watch/${gid.lowercase()}",
            publishedAt = null,
            extras = buildMap {
                put("media", media)
                likes?.toLongOrNull()?.let { put("score", it.toString()) }
            },
        )
    }

    private suspend fun token(): String {
        cachedToken?.let { if (System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS) return it }
        return tokenMutex.withLock {
            cachedToken?.let { if (System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS) return it }
            val root = http.get(id, "https://api.redgifs.com/v2/auth/temporary", browserUa())
            val t = ((root as? JsonObject)?.get("token") as? JsonPrimitive)?.contentOrNull
                ?: throw SourceException(id, "RedGIFs did not return a token")
            cachedToken = t
            tokenAt = System.currentTimeMillis()
            t
        }
    }

    private fun invalidateToken() {
        cachedToken = null
        tokenAt = 0L
    }

    private fun authHeaders(token: String) = browserUa() + ("Authorization" to "Bearer $token")

    // RedGIFs rejects empty/library default User-Agents; send a browser-like one.
    private fun browserUa() = mapOf("User-Agent" to "Mozilla/5.0 (Android; Mobile) PrivateFeed/2")

    companion object {
        const val SOURCE_ID = "redgifs"
        private const val COUNT = 40
        private const val RANDOM_START_PAGES = 5
        private const val MAX_PAGE = 100
        private const val TOKEN_TTL_MS = 6 * 60 * 60_000L
        private const val SEARCH_PAGES = 3
    }
}
