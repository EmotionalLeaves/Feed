package dev.pfeed.source.live

import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.SourceAdapter
import dev.pfeed.source.SourceException
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.pageAt
import dev.pfeed.source.parse.MediaTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live: people on cam right now (CamSoda's and Cam4's public rooms), playing in the feed like any video, as
 * long as they're on. The list is the site's own "online" listing, busiest rooms first; a room's
 * stream address is asked for when it's played (it carries a short-lived token). Nothing is sent to
 * the site but those two public requests: no account, no chat.
 */
@Singleton
class LiveCamsAdapter @Inject constructor(
    private val http: HttpJson,
) : SourceAdapter {
    override val id = SOURCE_ID
    override val displayName = "Live cams"
    override val requiresAuth = false

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    /** The online list, kept a couple of minutes (rooms come and go). */
    @Volatile private var cached: Pair<Long, List<MediaItem>>? = null

    private suspend fun online(): List<MediaItem> {
        cached?.let { (at, list) -> if (System.currentTimeMillis() - at < LIST_TTL_MS) return list }
        val soda = ArrayList<MediaItem>()
        for (page in 1..PAGES) {
            val body = runCatching { http.get(id, "https://www.camsoda.com/api/v1/browse/online?p=$page") }.getOrNull() ?: break
            val rooms = parseOnline(body)
            if (rooms.isEmpty()) break
            soda += rooms
            if (soda.size >= MAX_ROOMS) break
        }
        val cam4 = ArrayList<MediaItem>()
        for (page in 1..PAGES) {
            val body = runCatching { http.get(id, "https://www.cam4.com/api/directoryCams?directoryJson=true&online=true&url=true&page=$page&resultsPerPage=60") }.getOrNull() ?: break
            val rooms = parseCam4(body)
            if (rooms.isEmpty()) break
            cam4 += rooms
            if (cam4.size >= MAX_ROOMS) break
        }
        // Both sites woven together, busiest first within each.
        val all = (0 until maxOf(soda.size, cam4.size)).flatMap { i -> listOfNotNull(soda.getOrNull(i), cam4.getOrNull(i)) }
        if (all.isEmpty()) throw SourceException(id, "No one live right now (or the sites didn't answer)")
        val list = all.distinctBy { it.id }
        cached = System.currentTimeMillis() to list
        return list
    }

    override suspend fun fetchFeed(cursor: String?, prefs: FeedPrefs): Page<MediaItem> {
        if (cursor == null) cached = null
        return online().pageAt(cursor, PAGE_SIZE)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val words = query.lowercase().split(' ').filter { it.length >= 2 }
        if (words.isEmpty()) return Page(emptyList(), null)
        val hits = online().filter { item ->
            val hay = ((item.title ?: "") + " " + (item.creator ?: "") + " " + item.tags.joinToString(" ")).lowercase()
            words.all { it in hay }
        }
        return hits.pageAt(cursor, PAGE_SIZE)
    }

    override suspend fun resolveStream(item: MediaItem): StreamInfo {
        val user = item.creator ?: throw SourceException(id, "Unknown room")
        if (item.id.startsWith("cam4/")) {
            // Cam4 hands out its HLS address openly (the CDN one, else the edge), fresh per play.
            val info = runCatching { http.get(id, "https://www.cam4.com/rest/v1.0/profile/$user/streamInfo", mapOf("Referer" to "https://www.cam4.com/")) as? JsonObject }.getOrNull()
            val url = info?.let { (it["cdnURL"] ?: it["edgeURL"])?.jsonPrimitive?.contentOrNull }?.takeIf { it.startsWith("https://") }
                ?: item.extras["hls"]?.takeIf { it.startsWith("https://") }
                ?: throw SourceException(id, "${item.title ?: user} isn't live any more")
            return StreamInfo.Direct(url, MediaTypes.HLS_MIME)
        }
        val j = http.get(id, "https://www.camsoda.com/api/v1/video/vtoken/$user?username=guest_${(1000..99999).random()}",
            mapOf("Referer" to "https://www.camsoda.com/")) as? JsonObject ?: throw SourceException(id, "No stream")
        val edge = (j["edge_servers"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
        val name = j["stream_name"]?.jsonPrimitive?.contentOrNull
        val token = j["token"]?.jsonPrimitive?.contentOrNull
        if (edge.isNullOrBlank() || name.isNullOrBlank() || token.isNullOrBlank()) throw SourceException(id, "${item.title ?: user} isn't live any more")
        return StreamInfo.Direct("https://$edge/${name}_v1/index.m3u8?token=${java.net.URLEncoder.encode(token, "UTF-8")}", MediaTypes.HLS_MIME)
    }

    override fun reportUrl(item: MediaItem): String? = item.pageUrl

    companion object {
        const val SOURCE_ID = "live"
        private const val LIST_TTL_MS = 2 * 60_000L
        private const val PAGES = 3
        private const val MAX_ROOMS = 240
        private const val PAGE_SIZE = 24
        private val HASHTAG = Regex("#([\\p{L}\\p{N}_]{2,30})")
        private val HTML = Regex("<[^>]+>")

        /**
         * The online listing: `results` is a list of rooms, each a `tpl` either as an object keyed
         * by position ("1" = username) or as a plain array, with `template` naming the positions.
         */
        internal fun parseOnline(body: JsonElement): List<MediaItem> {
            val obj = body as? JsonObject ?: return emptyList()
            val names = (obj["template"] as? JsonArray)?.map { it.jsonPrimitive.contentOrNull.orEmpty() } ?: DEFAULT_TEMPLATE
            val results = obj["results"] as? JsonArray ?: return emptyList()
            return results.mapNotNull { r ->
                val tpl = (r as? JsonObject)?.get("tpl") ?: return@mapNotNull null
                fun field(name: String): JsonElement? {
                    val i = names.indexOf(name).takeIf { it >= 0 } ?: return null
                    return when (tpl) {
                        is JsonObject -> tpl[i.toString()]
                        is JsonArray -> tpl.getOrNull(i)
                        else -> null
                    }
                }
                fun text(name: String) = (field(name) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val user = text("username")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // Rooms in a private show or on a break have nothing to watch.
                if (text("status") in setOf("private", "limited", "offline")) return@mapNotNull null
                val subject = text("subject_html").orEmpty().replace(HTML, " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
                val viewers = text("connections")?.toIntOrNull() ?: 0
                val tags = HASHTAG.findAll(subject).map { it.groupValues[1].lowercase() }.toList() + "live cam"
                MediaItem(
                    id = "camsoda/$user",
                    sourceId = SOURCE_ID,
                    type = MediaType.VIDEO,
                    title = listOf(text("display_name") ?: user, subject.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ").take(160),
                    creator = user,
                    tags = tags.distinct(),
                    durationSec = null,
                    thumbnailUrl = text("thumb"),
                    pageUrl = "https://www.camsoda.com/$user",
                    publishedAt = Instant.now(),
                    extras = mapOf(LIVE to "1", "viewers" to viewers.toString(), "score" to viewers.toString()),
                )
            }.sortedByDescending { it.extras["viewers"]?.toIntOrNull() ?: 0 }
        }

        /** Cam4's directory: `users`, each with a snapshot, viewers, show tags and an HLS preview address. */
        internal fun parseCam4(body: JsonElement): List<MediaItem> {
            val users = ((body as? JsonObject)?.get("users") as? JsonArray) ?: return emptyList()
            return users.mapNotNull { u ->
                val o = u as? JsonObject ?: return@mapNotNull null
                fun text(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val user = text("username")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (text("showType") != null && text("showType") != "NORMAL") return@mapNotNull null
                if (text("privateRoom") == "true") return@mapNotNull null
                val viewers = text("viewers")?.toIntOrNull() ?: 0
                val showTags = (o["showTags"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.substringBefore('|') }.orEmpty()
                val status = text("statusMessage").orEmpty().trim()
                val gender = text("gender")?.let { if (it == "shemale") "trans" else it }
                MediaItem(
                    id = "cam4/$user",
                    sourceId = SOURCE_ID,
                    type = MediaType.VIDEO,
                    title = listOf(user, status.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ").take(160),
                    creator = user,
                    tags = (showTags + listOfNotNull(gender) + "live cam").map { it.lowercase() }.distinct(),
                    durationSec = null,
                    thumbnailUrl = text("snapshotImageLink") ?: text("profileImageLink"),
                    pageUrl = "https://www.cam4.com/$user",
                    publishedAt = Instant.now(),
                    extras = buildMap {
                        put(LIVE, "1"); put("viewers", viewers.toString()); put("score", viewers.toString())
                        text("hlsPreviewUrl")?.let { put("hls", it) }
                    },
                )
            }.sortedByDescending { it.extras["viewers"]?.toIntOrNull() ?: 0 }
        }

        /** In extras: a live stream (no length, no seeking, no resume points). */
        const val LIVE = "live"

        private val DEFAULT_TEMPLATE = listOf(
            "user_id", "username", "display_name", "status", "connections", "sort_value", "subject_html", "stream_name",
            "gender", "edge_servers", "thumb", "pvt_rating", "bitrate", "control_her", "standby", "offline_picture",
        )
    }
}
