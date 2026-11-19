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
 * Live: people on cam right now (CamSoda's, Cam4's and Flirt4Free's public rooms), playing in the feed like any video, as
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
        val f4f = runCatching { parseFlirt4Free(http.getRaw(id, "https://www.flirt4free.com/?tpl=index2&model=json")) }.getOrDefault(emptyList())
        // All three sites woven together, busiest first within each.
        val all = (0 until maxOf(soda.size, cam4.size, f4f.size)).flatMap { i -> listOfNotNull(soda.getOrNull(i), cam4.getOrNull(i), f4f.getOrNull(i)) }
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
        if (item.id.startsWith("f4f/")) {
            // Flirt4Free: its open rooms' HLS addresses come from a public call (the CDN can vary).
            val modelId = item.id.removePrefix("f4f/")
            val j = runCatching { http.get(id, "https://www.flirt4free.com/ws/chat/get-stream-urls.php?model_id=$modelId", mapOf("Referer" to item.pageUrl)) as? JsonObject }.getOrNull()
            val url = ((j?.get("data") as? JsonObject)?.get("hls") as? JsonArray)?.firstOrNull()
                ?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
                ?.let { if (it.startsWith("//")) "https:$it" else it }
                ?: "https://hls.vscdns.com/manifest.m3u8?key=nil&provider=cdn5&model_id=$modelId"
            return StreamInfo.Direct(url, MediaTypes.HLS_MIME)
        }
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
        /** The page's JavaScript leaves a comma before the closing bracket, which JSON doesn't allow. */
        private val TRAILING_COMMA = Regex(",\\s*([\\]}])")

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
                val who = when (text("gender")) { "f" -> "female"; "m" -> "male"; "t" -> "trans"; "c" -> "couple"; else -> null }
                val tags = HASHTAG.findAll(subject).map { it.groupValues[1].lowercase() }.toList() + listOfNotNull(who) + "live cam"
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
                val gender = when {
                    text("broadcastType")?.contains("group") == true || text("broadcastType") == "male_female_group" -> "couple"
                    text("gender") == "shemale" -> "trans"
                    else -> text("gender")
                }
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

        /**
         * Flirt4Free's home page data (JavaScript, with the rooms as a JSON array under `'models'`):
         * open rooms only (not in a private show, video not blocked), best-ranked first.
         */
        internal fun parseFlirt4Free(page: String): List<MediaItem> {
            val start = page.indexOf("'models':").takeIf { it >= 0 }?.let { page.indexOf('[', it) } ?: return emptyList()
            // The array runs to its matching bracket.
            var depth = 0; var end = -1; var inString = false; var i = start
            while (i < page.length) {
                val c = page[i]
                if (inString) { if (c == '\\') i++ else if (c == '"') inString = false }
                else when (c) { '"' -> inString = true; '[' -> depth++; ']' -> { depth--; if (depth == 0) { end = i; break } } }
                i++
            }
            if (end < 0) return emptyList()
            val arr = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(TRAILING_COMMA.replace(page.substring(start, end + 1), "$1")) as? JsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                fun text(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val modelId = text("model_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (text("room_status") != "In Open" || text("video_blocked") == "1" || text("is_hls") != "1") return@mapNotNull null
                val name = text("display") ?: text("model_name") ?: return@mapNotNull null
                val seo = text("model_seo_name") ?: name.lowercase()
                val who = when {
                    text("service") == "trans" -> "trans"
                    text("service") == "guys" -> "male"
                    (text("num_perf")?.toIntOrNull() ?: 1) > 1 -> "couple"
                    else -> "female"
                }
                val categories = listOfNotNull(text("category_name"), text("category_name_2"), text("category_name_3")).map { it.lowercase() }
                val score = text("power_score_xvc")?.toLongOrNull() ?: 0L
                MediaItem(
                    id = "f4f/$modelId",
                    sourceId = SOURCE_ID,
                    type = MediaType.VIDEO,
                    title = listOfNotNull(name, text("location")?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    creator = name,
                    tags = (categories + who + "live cam").distinct(),
                    durationSec = null,
                    thumbnailUrl = text("sample_image_id")?.let { "https://cdn5.vscdns.com/images/models/samples-640x480/$it.jpg" },
                    pageUrl = "https://www.flirt4free.com/videos/${text("service") ?: "girls"}/models/$seo/",
                    publishedAt = Instant.now(),
                    // No viewer counts here; its own ranking stands in.
                    extras = mapOf(LIVE to "1", "score" to score.toString()),
                )
            }.sortedByDescending { it.extras["score"]?.toLongOrNull() ?: 0L }
        }

        /** Who's on cam, as the Live tab's chips offer it (the tag each room carries). */
        val WHO = listOf("female" to "Women", "couple" to "Couples", "male" to "Men", "trans" to "Trans")

        /** In extras: a live stream (no length, no seeking, no resume points). */
        const val LIVE = "live"

        private val DEFAULT_TEMPLATE = listOf(
            "user_id", "username", "display_name", "status", "connections", "sort_value", "subject_html", "stream_name",
            "gender", "edge_servers", "thumb", "pvt_rating", "bitrate", "control_her", "standby", "offline_picture",
        )
    }
}
