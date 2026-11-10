package dev.pfeed.source.tube

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.source.parse.MediaTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** One video as a site lists it. */
data class TubeVideo(
    val id: String,
    val title: String?,
    val pageUrl: String,
    val thumb: String?,
    val durationSec: Int?,
    val tags: List<String> = emptyList(),
    val published: java.time.Instant? = null,
    val creator: String? = null,
)

/** What a video page gave us: a stream the player can open, or nothing (the embed is used then). */
data class TubeStream(val url: String, val mime: String? = null)

/**
 * A tube site: how to list videos and how to find the stream on a video page. Parsing is pure and
 * written against each site's markup as served in November 2026 (see TubeSitesTest for fixtures).
 */
sealed class TubeSite(val host: String, val label: String) {
    /** Sections to rotate through: "new", "top", or a search/tag term. */
    abstract fun listUrl(section: String, page: Int): String
    abstract fun parseList(body: String): List<TubeVideo>
    /** Direct stream from the video page, or null to fall back to [embedUrl]. */
    abstract fun stream(pageHtml: String): TubeStream?
    abstract fun embedUrl(id: String): String
    /** Video pages whose stream is hidden behind a second request (Pornhub/RedTube get_media). */
    open fun streamIndexUrl(pageHtml: String): String? = null
    open fun streamFromIndex(indexJson: JsonElement): TubeStream? = null

    companion object {
        val ALL: List<TubeSite> = listOf(Xvideos, Pornhub, Xhamster, Redtube, Eporner)
        fun byHost(host: String): TubeSite? = ALL.firstOrNull { host == it.host || host.endsWith(".${it.host}") }
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        internal fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("\\/", "/")

        /** "10:23" / "1:02:03" / "10 min" → seconds. */
        internal fun duration(raw: String?): Int? {
            val t = raw?.trim()?.lowercase() ?: return null
            Regex("""(\d+)\s*min""").find(t)?.let { return it.groupValues[1].toInt() * 60 }
            val parts = t.split(':').mapNotNull { it.trim().toIntOrNull() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                1 -> parts[0]
                else -> null
            }
        }

        /** Highest "quality":"720" style pick from a list of {videoUrl, quality} objects. */
        internal fun bestOf(list: JsonArray): TubeStream? = list.mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val url = (o["videoUrl"] ?: o["url"])?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val q = o["quality"]?.let { q -> (q as? JsonPrimitive)?.contentOrNull?.filter(Char::isDigit)?.toIntOrNull() } ?: 0
                q to url
            }.maxByOrNull { it.first }?.let { (_, url) -> TubeStream(unescape(url), if (url.contains(".m3u8")) MediaTypes.HLS_MIME else null) }

        /** Pornhub-family `"mediaDefinitions":[…]` found in a page's player setup. */
        internal fun mediaDefinitions(html: String): JsonArray? {
            val i = html.indexOf("\"mediaDefinitions\":")
            if (i < 0) return null
            val start = html.indexOf('[', i)
            if (start < 0) return null
            var depth = 0
            var j = start
            while (j < html.length) {
                when (html[j]) { '[' -> depth++; ']' -> { depth--; if (depth == 0) break } }
                j++
            }
            return runCatching { json.parseToJsonElement(html.substring(start, j + 1)).jsonArray }.getOrNull()
        }

        /** Pick the stream from media definitions: an hls/mp4 entry with a real URL. */
        internal fun fromMediaDefinitions(defs: JsonArray): TubeStream? {
            val objs = defs.mapNotNull { it as? JsonObject }
            // Entries whose "quality" is a list point to an index that must be fetched (handled by streamIndexUrl).
            val direct = objs.filter { it["quality"] !is JsonArray }
            return bestOf(JsonArray(direct))
        }
    }

    // ── xvideos ─────────────────────────────────────────────────────────────

    object Xvideos : TubeSite("xvideos.com", "XVideos") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.xvideos.com/new/$page"
            "top" -> "https://www.xvideos.com/best"
            else -> "https://www.xvideos.com/?k=${enc(section)}&p=$page"
        }

        private val BLOCK = Regex("""<div[^>]*\bclass="[^"]*thumb-block[^"]*"[^>]*\bdata-id="(\d+)"[^>]*>(.*?)<div[^>]*\bclass="thumb-under"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        private val HREF = Regex("""href="(/video[^"]+)"""")
        private val IMG = Regex("""<img[^>]*\b(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|webp)[^"]*)"""")
        private val TITLE = Regex("""<a[^>]*\btitle="([^"]*)"""")
        private val DUR = Regex("""class="duration"[^>]*>([^<]*)<""")

        override fun parseList(body: String): List<TubeVideo> = BLOCK.findAll(body).mapNotNull { m ->
            val id = m.groupValues[1]
            val thumbPart = m.groupValues[2]
            val under = m.groupValues[3]
            val href = HREF.find(thumbPart)?.groupValues?.get(1) ?: HREF.find(under)?.groupValues?.get(1) ?: return@mapNotNull null
            TubeVideo(
                id = id,
                title = TITLE.find(under)?.groupValues?.get(1)?.let(::unescape)?.trim(),
                pageUrl = "https://www.xvideos.com" + unescape(href),
                thumb = IMG.find(thumbPart)?.groupValues?.get(1)?.let(::unescape),
                durationSec = duration(DUR.find(under)?.groupValues?.get(1)),
            )
        }.toList()

        private val HLS = Regex("""setVideoHLS\(\s*['"]([^'"]+)['"]""")
        private val HIGH = Regex("""setVideoUrlHigh\(\s*['"]([^'"]+)['"]""")
        private val LOW = Regex("""setVideoUrlLow\(\s*['"]([^'"]+)['"]""")

        override fun stream(pageHtml: String): TubeStream? {
            HLS.find(pageHtml)?.groupValues?.get(1)?.let { return TubeStream(unescape(it), MediaTypes.HLS_MIME) }
            (HIGH.find(pageHtml) ?: LOW.find(pageHtml))?.groupValues?.get(1)?.let { return TubeStream(unescape(it)) }
            return null
        }

        override fun embedUrl(id: String) = "https://www.xvideos.com/embedframe/$id"
    }

    // ── Pornhub ─────────────────────────────────────────────────────────────

    object Pornhub : TubeSite("pornhub.com", "Pornhub") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.pornhub.com/video?o=cm&page=${page + 1}"
            "top" -> "https://www.pornhub.com/video?o=mv&t=w&page=${page + 1}"
            else -> "https://www.pornhub.com/video/search?search=${enc(section)}&page=${page + 1}"
        }

        private val ITEM = Regex("""<li[^>]*\bdata-video-vkey="([^"]+)"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        private val THUMB = Regex("""\b(?:data-src|data-thumb_url|src)="(https?://[^"]+\.(?:jpg|jpeg|webp)[^"]*)"""")
        private val TITLE = Regex("""<a[^>]*\bhref="/view_video\.php\?viewkey=[^"]+"[^>]*\btitle="([^"]*)"""")
        private val DUR = Regex("""class="duration"[^>]*>([^<]*)<""")

        override fun parseList(body: String): List<TubeVideo> = ITEM.findAll(body).map { m ->
            val vkey = m.groupValues[1]
            val block = m.groupValues[2]
            TubeVideo(
                id = vkey,
                title = TITLE.find(block)?.groupValues?.get(1)?.let(::unescape)?.trim(),
                pageUrl = "https://www.pornhub.com/view_video.php?viewkey=$vkey",
                thumb = THUMB.find(block)?.groupValues?.get(1)?.let(::unescape),
                durationSec = duration(DUR.find(block)?.groupValues?.get(1)),
            )
        }.distinctBy { it.id }.toList()

        override fun stream(pageHtml: String): TubeStream? = mediaDefinitions(pageHtml)?.let(::fromMediaDefinitions)

        override fun streamIndexUrl(pageHtml: String): String? = mediaDefinitions(pageHtml)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it["quality"] is JsonArray && it["format"]?.jsonPrimitive?.contentOrNull == "hls" }
            ?.get("videoUrl")?.jsonPrimitive?.contentOrNull?.let(::unescape)?.takeIf { it.startsWith("http") }

        override fun streamFromIndex(indexJson: JsonElement): TubeStream? = (indexJson as? JsonArray)?.let(::bestOf)

        override fun embedUrl(id: String) = "https://www.pornhub.com/embed/$id"
    }

    // ── xHamster ────────────────────────────────────────────────────────────

    object Xhamster : TubeSite("xhamster.com", "xHamster") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://xhamster.com/newest/${page + 1}"
            "top" -> "https://xhamster.com/best/weekly/${page + 1}"
            else -> "https://xhamster.com/search/${enc(section).replace("+", "%20")}?page=${page + 1}"
        }

        /** Listing pages carry their videos as JSON in `window.initials`. */
        override fun parseList(body: String): List<TubeVideo> {
            val initials = initials(body) ?: return parseListHtml(body)
            val thumbs = initials["videoListProps"]?.jsonObject?.get("videoThumbProps")?.jsonArray
                ?: return parseListHtml(body)
            return thumbs.mapNotNull { it as? JsonObject }.mapNotNull { v ->
                val url = v["pageURL"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val id = url.substringAfterLast('-').substringBefore('?').takeIf { it.all(Char::isDigit) } ?: url.substringAfterLast('/')
                TubeVideo(
                    id = id,
                    title = v["title"]?.jsonPrimitive?.contentOrNull,
                    pageUrl = url,
                    thumb = v["thumbURL"]?.jsonPrimitive?.contentOrNull,
                    durationSec = (v["duration"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                )
            }
        }

        private val A = Regex("""<a[^>]*\bclass="[^"]*video-thumb__image-container[^"]*"[^>]*\bhref="(https://xhamster\.com/videos/[^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val IMG = Regex("""<img[^>]*\bsrc="(https?://[^"]+)"""")
        private val DUR = Regex("""data-role="video-duration"[^>]*>\s*([\d:]+)""")
        private val NAME = Regex("""<a[^>]*\bclass="[^"]*video-thumb-info__name[^"]*"[^>]*\bhref="(https://xhamster\.com/videos/[^"]+)"[^>]*>([^<]*)<""")

        private fun parseListHtml(body: String): List<TubeVideo> {
            val names = NAME.findAll(body).associate { it.groupValues[1] to unescape(it.groupValues[2]).trim() }
            return A.findAll(body).map { m ->
                val url = m.groupValues[1]
                TubeVideo(
                    id = url.substringAfterLast('-'),
                    title = names[url],
                    pageUrl = url,
                    thumb = IMG.find(m.groupValues[2])?.groupValues?.get(1),
                    durationSec = duration(DUR.find(m.groupValues[2])?.groupValues?.get(1)),
                )
            }.distinctBy { it.id }.toList()
        }

        internal fun initials(html: String): JsonObject? {
            val i = html.indexOf("window.initials")
            if (i < 0) return null
            val start = html.indexOf('{', i)
            if (start < 0) return null
            var depth = 0
            var j = start
            var inStr = false
            var esc = false
            while (j < html.length) {
                val c = html[j]
                if (inStr) {
                    if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                } else when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) break }
                }
                j++
            }
            return runCatching { json.parseToJsonElement(html.substring(start, j + 1)).jsonObject }.getOrNull()
        }

        override fun stream(pageHtml: String): TubeStream? {
            val sources = initials(pageHtml)?.get("xplayerSettings")?.jsonObject?.get("sources")?.jsonObject
            if (sources != null) {
                sources["hls"]?.jsonObject?.values?.mapNotNull { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
                    ?.firstOrNull { it.startsWith("http") }?.let { return TubeStream(it, MediaTypes.HLS_MIME) }
                sources["standard"]?.jsonObject?.get("mp4")?.let { it as? JsonArray }?.let(::bestOf)?.let { return it }
            }
            Regex(""""url":"(https?:[^"]+\.m3u8[^"]*)"""").find(pageHtml)?.groupValues?.get(1)?.let { return TubeStream(unescape(it), MediaTypes.HLS_MIME) }
            return null
        }

        override fun embedUrl(id: String) = "https://xhamster.com/embed/$id"
    }

    // ── RedTube (official listing API; player setup like Pornhub) ───────────

    object Redtube : TubeSite("redtube.com", "RedTube") {
        override fun listUrl(section: String, page: Int): String {
            val base = "https://api.redtube.com/?data=redtube.Videos.searchVideos&output=json&thumbsize=all&page=${page + 1}"
            return when (section) {
                "new" -> "$base&ordering=newest"
                "top" -> "$base&ordering=mostviewed&period=weekly"
                else -> "$base&ordering=rating&search=${enc(section)}"
            }
        }

        override fun parseList(body: String): List<TubeVideo> {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
            return root["videos"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.get("video") as? JsonObject }.mapNotNull { v ->
                val id = v["video_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                TubeVideo(
                    id = id,
                    title = v["title"]?.jsonPrimitive?.contentOrNull,
                    pageUrl = v["url"]?.jsonPrimitive?.contentOrNull ?: "https://www.redtube.com/$id",
                    thumb = v["default_thumb"]?.jsonPrimitive?.contentOrNull,
                    durationSec = duration(v["duration"]?.jsonPrimitive?.contentOrNull),
                    tags = v["tags"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.get("tag_name")?.jsonPrimitive?.contentOrNull },
                    published = MediaTypes.parseDate(v["publish_date"]?.jsonPrimitive?.contentOrNull?.replace(' ', 'T')?.let { "${it}Z" }),
                )
            }
        }

        override fun stream(pageHtml: String): TubeStream? = mediaDefinitions(pageHtml)?.let(::fromMediaDefinitions)
        override fun streamIndexUrl(pageHtml: String): String? = Pornhub.streamIndexUrl(pageHtml)
        override fun streamFromIndex(indexJson: JsonElement): TubeStream? = Pornhub.streamFromIndex(indexJson)
        override fun embedUrl(id: String) = "https://embed.redtube.com/?id=$id"
    }

    // ── Eporner (official API; plays through its embed) ─────────────────────

    object Eporner : TubeSite("eporner.com", "Eporner") {
        override fun listUrl(section: String, page: Int): String {
            val base = "https://www.eporner.com/api/v2/video/search/?per_page=30&page=${page + 1}&thumbsize=big&gay=0&lq=1&format=json"
            return when (section) {
                "new" -> "$base&query=all&order=latest"
                "top" -> "$base&query=all&order=top-weekly"
                else -> "$base&query=${enc(section)}&order=top-rated"
            }
        }

        override fun parseList(body: String): List<TubeVideo> {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
            return root["videos"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { v ->
                val id = v["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                TubeVideo(
                    id = id,
                    title = v["title"]?.jsonPrimitive?.contentOrNull,
                    pageUrl = v["url"]?.jsonPrimitive?.contentOrNull ?: "https://www.eporner.com/video-$id/",
                    thumb = v["default_thumb"]?.jsonObject?.get("src")?.jsonPrimitive?.contentOrNull,
                    durationSec = (v["length_sec"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                    tags = v["keywords"]?.jsonPrimitive?.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                    published = MediaTypes.parseDate(v["added"]?.jsonPrimitive?.contentOrNull?.replace(' ', 'T')?.let { "${it}Z" }),
                )
            }
        }

        override fun stream(pageHtml: String): TubeStream? = null
        override fun embedUrl(id: String) = "https://www.eporner.com/embed/$id/"
    }
}

/** Feed items from a site's listing. */
internal fun TubeSite.items(videos: List<TubeVideo>, section: String): List<MediaItem> = videos.map { v ->
    MediaItem(
        id = "$host/${v.id}",
        sourceId = TubeAdapter.SOURCE_ID,
        type = MediaType.VIDEO,
        title = v.title,
        creator = v.creator,
        tags = (v.tags + listOfNotNull(section.takeIf { it != "new" && it != "top" })).distinct(),
        durationSec = v.durationSec,
        thumbnailUrl = v.thumb,
        pageUrl = v.pageUrl,
        publishedAt = v.published,
        extras = mapOf("resolve" to "tube", "vid" to v.id),
    )
}
