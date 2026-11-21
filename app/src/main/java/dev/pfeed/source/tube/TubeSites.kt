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
    /** A few seconds' muted preview clip the site offers (for live grids), if any. */
    val preview: String? = null,
    /** The best height the listing says it comes in (720 for "HD"), if it says. */
    val height: Int? = null,
    /** The performers the site credits (its own models list), if it does. */
    val performers: List<String> = emptyList(),
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
    /**
     * Same site under other domains, tried in order when the main one answers with nothing usable
     * (xHamster sometimes serves a bot check instead of its listing; its mirrors don't at the same time).
     */
    open val mirrors: List<String> = emptyList()

    /** The page is the site's empty shell for this network (a regional block), not a real listing. */
    open fun isShell(body: String): Boolean = false

    /** [url] on [mirror] instead of the main domain. */
    fun onMirror(url: String, mirror: String): String = url.replaceFirst("://$host", "://$mirror").replaceFirst("://www.$host", "://www.$mirror")
    /** Direct stream from the video page, or null to fall back to [embedUrl]. */
    abstract fun stream(pageHtml: String): TubeStream?
    abstract fun embedUrl(id: String): String
    /** Video pages whose stream is hidden behind a second request (Pornhub/RedTube get_media). */
    open fun streamIndexUrl(pageHtml: String): String? = null
    /** The page whose HTML holds the stream (or the index URL); usually the video page itself. */
    open fun streamPageUrl(pageUrl: String, id: String): String = pageUrl

    /**
     * Sites whose listings don't carry tags, where a post must not be shown until its tags have been
     * read and checked (drawn/animated content: the minors filter works on tags). Each video page is
     * read first; the stream found there is kept for playback.
     */
    open val checkTagsFirst: Boolean = false

    /** Sites for one audience (gay, trans) start switched off: on in Settings → Sources → Tube sites. */
    open val startsOff: Boolean = false

    /** Tags (and categories) named on a video page, for [checkTagsFirst] sites. */
    open fun pageTags(pageHtml: String): List<String> = emptyList()

    /** The performers the video page credits (its own, not the site's menus), as the site writes them. */
    open fun pagePeople(pageHtml: String): List<String> = emptyList()
    open fun streamFromIndex(indexJson: JsonElement): TubeStream? = null

    /**
     * Sites whose video page only frames a player hosted elsewhere (HQPorner → mydaddy.cc, Porndig →
     * videos.porndig.com): the player page's address, whose HTML [stream] then reads.
     */
    open fun playerUrl(pageHtml: String): String? = null

    /** How much each stretch of the video is watched, where the page says (see [dev.pfeed.domain.reco.BestPart]). */
    open fun hotspots(pageHtml: String): List<Int>? = null

    /** The comments under the video, where the page carries them: (who, what), top first. */
    open fun comments(pageHtml: String): List<Pair<String, String>> = emptyList()
    /** Where a site that loads its comments separately keeps them, from its video page; null if in the page or none. */
    open fun commentsUrl(pageHtml: String): String? = null
    /** Where the site lists what's related to video [id], when that's a request of its own (TXXX's API); parsed with [parseList]. */
    open fun relatedUrl(id: String): String? = null
    /** That answer, as (who, what). */
    open fun parseComments(body: kotlinx.serialization.json.JsonElement): List<Pair<String, String>> = emptyList()

    /** Scrub previews and chapters, where the page has them (the Pornhub family's player setup). */
    open fun timeline(pageHtml: String): dev.pfeed.domain.reco.VideoExtras.Timeline? = phTimeline(pageHtml) ?: kvsTimeline(pageHtml)
    open fun chapters(pageHtml: String): List<Pair<String, Long>> =
        dev.pfeed.domain.reco.VideoExtras.chapters(Regex("""\"actionTags\":\"([^\"]*)\"""").find(pageHtml)?.groupValues?.get(1))

    /** `"thumbs":{"samplingFrequency":4,…,"urlPattern":"…S{5}.jpg"}` → a [dev.pfeed.domain.reco.VideoExtras.Timeline]. */
    /**
     * KVS sites' player: one small picture every `timeline_screens_interval` seconds, numbered from 1
     * in `timeline_screens_url`'s {time} (each is a "sheet" of one frame).
     */
    protected fun kvsTimeline(html: String): dev.pfeed.domain.reco.VideoExtras.Timeline? {
        val url = KVS_SCREENS.find(html)?.groupValues?.get(1) ?: return null
        val every = KVS_INTERVAL.find(html)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val count = KVS_COUNT.find(html)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val full = if (url.startsWith("//")) "https:$url" else url
        if (!full.startsWith("https://")) return null
        return dev.pfeed.domain.reco.VideoExtras.Timeline(full.replace("{time}", "{n}"), every, cols = 1, rows = 1, firstSheet = 1, frames = count)
    }

    protected fun phTimeline(html: String): dev.pfeed.domain.reco.VideoExtras.Timeline? {
        val i = html.indexOf("\"thumbs\":{")
        if (i < 0) return null
        val block = html.substring(i, minOf(html.length, i + 1500))
        val every = Regex("""\"samplingFrequency\":\"?(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val pattern = Regex("""\"urlPattern\":\"(https:[^\"]+\{\d+\}[^\"]*)\"""").find(block)?.groupValues?.get(1)?.let(::unescape) ?: return null
        return dev.pfeed.domain.reco.VideoExtras.Timeline(pattern.replace(Regex("\\{\\d+\\}"), "{n}"), every)
    }

    companion object {
        // Lazy: the nested objects extend TubeSite, so building this eagerly during the companion's
        // initialisation re-enters the class initialiser and reads a null list.
        val ALL: List<TubeSite> by lazy { listOf(Xvideos, Xnxx, Pornhub, Youporn, Tube8, Xhamster, Redtube, Eporner, Tnaflix, Porntrex, Youjizz, Erome, Rule34Video, ThisVid, PornOne, PornHat, HqPorner, Porndig, PerfectGirls, OkXxx, PornGo, XFreeHd, ThreeMovs, AnySex, ZbPorn, Analdin, KatesTube, Txxx, HClips, Upornia, TubePornClassic, Vxxx, HdZog, ManySex, Empflix, PornWhite, SleazyNeasy, VikiPorn, AlotPorn, FreePorn8, BigTitsLust, DrTuber, Nuvid, IcePorn, HotMovs, InPorn, FetishShrine, Pornicom, TheGay, Shemalez) }
        /**
         * Sites the app no longer lists, searches or shows (you asked for XNXX to go). They stay in
         * [ALL] only so posts already saved from them can still play.
         */
        val RETIRED: Set<String> = setOf("xnxx.com", "xozilla.xxx", "nudez.com")
        private val KVS_SCREENS = Regex("""timeline_screens_url: '([^']+\{time\}[^']*)'""")
        private val KVS_INTERVAL = Regex("""timeline_screens_interval: '(\d+)'""")
        private val KVS_COUNT = Regex("""timeline_screens_count: '(\d+)'""")
        /** The sites the app actually uses. */
        val ACTIVE: List<TubeSite> by lazy { ALL.filter { it.host !in RETIRED } }

        fun byHost(host: String): TubeSite? = ALL.firstOrNull { site ->
            (listOf(site.host) + site.mirrors).any { host == it || host.endsWith(".$it") }
        }
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        internal fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("\\/", "/")

        /** "10:23" / "1:02:03" / "10 min" → seconds. */
        internal fun duration(raw: String?): Int? {
            val t = raw?.trim()?.lowercase() ?: return null
            // "12 min", "1 h 2 min", "10min", "45 sec"
            val h = Regex("""(\d+)\s*h(?:ours?|rs?)?\b""").find(t)?.groupValues?.get(1)?.toInt()
            val m = Regex("""(\d+)\s*m(?:in)?\b""").find(t)?.groupValues?.get(1)?.toInt()
            val sec = Regex("""(\d+)\s*s(?:ec)?\b""").find(t)?.groupValues?.get(1)?.toInt()
            if (h != null || m != null || sec != null) return (h ?: 0) * 3600 + (m ?: 0) * 60 + (sec ?: 0)
            val parts = t.split(':').mapNotNull { it.trim().toIntOrNull() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                1 -> parts[0]
                else -> null
            }
        }

        internal fun absolute(url: String, site: String): String {
            val u = unescape(url)
            return when {
                u.startsWith("http") -> u
                u.startsWith("//") -> "https:$u"
                u.startsWith("/") -> "https://www.$site$u"
                else -> u
            }
        }

        /** The JSON array (or object) that starts at the first '[' / '{' after [marker], string-aware. */
        internal fun jsonAfter(html: String, marker: String): String? {
            val i = html.indexOf(marker)
            if (i < 0) return null
            val start = html.indexOfAny(charArrayOf('[', '{'), i)
            if (start < 0) return null
            val open = html[start]; val close = if (open == '[') ']' else '}'
            var depth = 0; var j = start; var inStr = false; var esc = false
            while (j < html.length) {
                val c = html[j]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false }
                else when (c) { '"' -> inStr = true; open -> depth++; close -> { depth--; if (depth == 0) return html.substring(start, j + 1) } }
                j++
            }
            return null
        }

        /** Highest "quality":"720" style pick from a list of {videoUrl, quality} objects. */
        /** Tallest MP4 picked (1080, or 480 with Data saver on); adaptive streams are capped by the player. */
        @Volatile var maxHeight: Int = 1080
        private val KVS_FILE = Regex("""\b(video_(?:alt_)?url\d*)\s*:\s*'(https://[^']+)'""")
        private val KVS_TAGS = Regex("""\btags\s*:\s*'([^']*)'""")
        private val KVS_FILLER = setOf("in english", "hd", "sex", "hot", "hottest", "hard", "nude", "porn", "xxx", "video", "videos", "new", "best", "free", "room")

        internal fun bestOf(list: JsonArray, site: String = ""): TubeStream? = list.mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val raw = (o["videoUrl"] ?: o["url"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = absolute(raw, site).takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val q = o["quality"]?.let { q -> (q as? JsonPrimitive)?.contentOrNull?.filter(Char::isDigit)?.toIntOrNull() } ?: 0
                q to url
            }.let { all -> all.filter { it.first <= maxHeight }.ifEmpty { all.sortedBy { it.first }.take(1) } }.maxByOrNull { it.first }?.let { (_, url) -> TubeStream(unescape(url), if (url.contains(".m3u8")) MediaTypes.HLS_MIME else null) }

        /** Pornhub-family `"mediaDefinitions":[…]` found in a page's player setup. */
        internal fun mediaDefinitions(html: String): JsonArray? =
            jsonAfter(html, "\"mediaDefinitions\":")?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() }

        /** An entry that lists the real streams behind a second request rather than being one itself. */
        private fun isIndex(o: JsonObject): Boolean {
            val url = o["videoUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return o["quality"] is JsonArray || o["remote"]?.jsonPrimitive?.contentOrNull == "true" ||
                url.contains("/get_media") || url.contains("/media/")
        }

        /** Pick the stream from media definitions: a direct hls/mp4 entry with a real URL. */
        internal fun fromMediaDefinitions(defs: JsonArray, site: String): TubeStream? =
            bestOf(JsonArray(defs.mapNotNull { it as? JsonObject }.filterNot(::isIndex)), site)

        /** The hls index (preferred) or mp4 index URL to fetch, absolute. */
        internal fun indexUrl(defs: JsonArray, site: String): String? {
            val objs = defs.mapNotNull { it as? JsonObject }.filter(::isIndex)
            val pick = objs.firstOrNull { it["format"]?.jsonPrimitive?.contentOrNull == "hls" } ?: objs.firstOrNull() ?: return null
            return pick["videoUrl"]?.jsonPrimitive?.contentOrNull?.let { absolute(it, site) }?.takeIf { it.startsWith("http") }
        }
    }

    // ── xvideos ─────────────────────────────────────────────────────────────

    object Xvideos : XvEngine("xvideos.com", "XVideos") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.xvideos.com/new/${page + 1}"
            "top" -> "https://www.xvideos.com/best"
            else -> "https://www.xvideos.com/?k=${enc(section)}&p=$page"
        }
    }

    /** XNXX runs the same engine as XVideos (same listing blocks, same html5player). */
    object Xnxx : XvEngine("xnxx.com", "XNXX") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.xnxx.com/search/new/$page"
            "top" -> "https://www.xnxx.com/search/best/$page"
            else -> "https://www.xnxx.com/search/${enc(section).replace("+", "+")}/$page"
        }
    }

    sealed class XvEngine(host: String, label: String) : TubeSite(host, label) {
        /** `"video_tags":["blonde","milf",…]` in the player setup (XVideos and XNXX). */
        /** "mozaique_full.jpg": 100 frames (10×10) spread over the whole video, for scrubbing. */
        override fun timeline(pageHtml: String): dev.pfeed.domain.reco.VideoExtras.Timeline? {
            val sheet = Regex("""setThumbSlideBig\('(https://[^']+\.jpg)'""").find(pageHtml)?.groupValues?.get(1) ?: return null
            val sec = Regex("""og:duration" content="(\d+)"""").find(pageHtml)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return dev.pfeed.domain.reco.VideoExtras.spread(sheet, sec, cols = 10, rows = 10)
        }
        /** Comments come from a JSON call: `posts.posts.{id}.{name, message}`. */
        override fun commentsUrl(pageHtml: String): String? {
            if ("\"has_comments\":true" !in pageHtml) return null
            val id = Regex(""""id_video":(\d+)""").find(pageHtml)?.groupValues?.get(1) ?: return null
            return "https://www.$host/threads/video-comments/get-posts/top/$id/0/0"
        }
        override fun parseComments(body: kotlinx.serialization.json.JsonElement): List<Pair<String, String>> {
            val posts = ((body as? kotlinx.serialization.json.JsonObject)?.get("posts") as? kotlinx.serialization.json.JsonObject)
                ?.get("posts") as? kotlinx.serialization.json.JsonObject ?: return emptyList()
            return posts.values.mapNotNull { p ->
                val o = p as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun text(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                val msg = text("message")?.replace(Regex("<[^>]+>"), " ")?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                (text("name") ?: "someone") to msg
            }.take(40)
        }
        override fun pageTags(pageHtml: String): List<String> =
            Regex("""\"video_tags\"\s*:\s*\[([^\]]*)\]""").find(pageHtml)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().trim('"').replace('-', ' ') }?.filter { it.isNotEmpty() }?.take(30).orEmpty()

        /** `"video_models":{"406077745":{"name":"Duncan Saint",…},…}` in the page's settings. */
        override fun pagePeople(pageHtml: String): List<String> {
            val i = pageHtml.indexOf("\"video_models\"").takeIf { it >= 0 } ?: return emptyList()
            val block = pageHtml.substring(i, minOf(pageHtml.length, i + 3000)).substringBefore("}}")
            return XV_MODEL.findAll(block).map { unicode(it.groupValues[1]).trim() }.filter { it.length in 3..40 }.distinct().take(6).toList()
        }
        private val XV_MODEL = Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        /** A JSON string's escapes (\u00e9, \/) read. */
        private fun unicode(s: String): String =
            Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }.replace("\\/", "/").replace("\\\"", "\"")

        // Attribute order varies (data-id before or after class), hence the lookahead.
        private val BLOCK = Regex("""<div\b(?=[^>]*\bthumb-block)[^>]*\bdata-id="(\d+)"[^>]*>(.*?)<div[^>]*\bclass="thumb-under"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val HREF = Regex("""href="(/video[^"]+)"""")
        private val IMG = Regex("""\bdata-src="(https?://[^"]+)"""")
        private val TITLE = Regex("""<a[^>]*\btitle="([^"]*)"""")
        private val DUR = Regex("""class="duration"[^>]*>([^<]*)<""")
        // XNXX puts the length loose in the metadata line: "… 26.4M 100% 10min - 1080p"
        private val META = Regex("""class="metadata"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        private val TAGS = Regex("<[^>]+>")

        private fun lengthOf(under: String): Int? =
            duration(DUR.find(under)?.groupValues?.get(1))
                ?: META.find(under)?.groupValues?.get(1)?.replace(TAGS, " ")
                    ?.let { Regex("""\b(\d+\s*h\s*)?\d+\s*min\b|\b\d+\s*sec\b""").find(it)?.value }
                    ?.let(::duration)

        override fun parseList(body: String): List<TubeVideo> = BLOCK.findAll(body).mapNotNull { m ->
            val id = m.groupValues[1]
            val thumbPart = m.groupValues[2]
            val under = m.groupValues[3]
            val href = HREF.find(thumbPart)?.groupValues?.get(1) ?: HREF.find(under)?.groupValues?.get(1) ?: return@mapNotNull null
            TubeVideo(
                id = id,
                title = TITLE.find(under)?.groupValues?.get(1)?.let(::unescape)?.trim(),
                pageUrl = "https://www.$host" + unescape(href),
                thumb = IMG.find(thumbPart)?.groupValues?.get(1)?.let(::unescape),
                durationSec = lengthOf(under),
                // The uploader, channel or pornstar under the tile: followable like any creator.
                creator = NAME.find(under)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() },
                // Beside each thumbnail on the CDN: a short silent preview clip (about 200 KB).
                preview = PVV.find(thumbPart)?.groupValues?.get(1)
                    ?: IMG.find(thumbPart)?.groupValues?.get(1)?.let { PREVIEW_BASE.find(it) }?.let { it.groupValues[1] + "preview.mp4" },
                height = HD_MARK.find(thumbPart)?.groupValues?.get(1)?.toIntOrNull(),
            )
        }.toList()

        private val HD_MARK = Regex("""video-hd-mark">\s*(\d{3,4})p""")
        private val PVV = Regex("""data-pvv="(https://[^"]+\.mp4)"""")
        private val PREVIEW_BASE = Regex("""^(https://[a-z0-9-]+\.xvideos-cdn\.com/[0-9a-f-]{36}/\d+/)xv_\d+_t\.jpg$""")
        private val NAME = Regex("""<span class="name">([^<]+)</span>""")

        private val HLS = Regex("""setVideoHLS\(\s*['"]([^'"]+)['"]""")
        private val HIGH = Regex("""setVideoUrlHigh\(\s*['"]([^'"]+)['"]""")
        private val LOW = Regex("""setVideoUrlLow\(\s*['"]([^'"]+)['"]""")

        override fun stream(pageHtml: String): TubeStream? {
            HLS.find(pageHtml)?.groupValues?.get(1)?.let { return TubeStream(unescape(it), MediaTypes.HLS_MIME) }
            (HIGH.find(pageHtml) ?: LOW.find(pageHtml))?.groupValues?.get(1)?.let { return TubeStream(unescape(it)) }
            return null
        }

        override fun embedUrl(id: String) = "https://www.$host/embedframe/$id"
    }

    // ── Pornhub ─────────────────────────────────────────────────────────────

    object Pornhub : TubeSite("pornhub.com", "Pornhub") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.pornhub.com/video?o=cm&page=${page + 1}"
            "top" -> "https://www.pornhub.com/video?o=mv&t=w&page=${page + 1}"
            else -> "https://www.pornhub.com/video/search?search=${enc(section)}&page=${page + 1}"
        }

        private val ITEM = Regex("""<li[^>]*\bdata-video-vkey="([^"]+)"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        // Thumbs are image-proxy URLs (…/original_123.mp4/plain/…?hash=…), so no extension check.
        private val THUMB = Regex("""<img\b[^>]*\b(?:data-mediumthumb|data-src|data-thumb_url|src)="(https?://[^"]+)"""")
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

        override fun stream(pageHtml: String): TubeStream? = mediaDefinitions(pageHtml)?.let { fromMediaDefinitions(it, host) }
        private val COMMENT_TEXT = Regex("""<div class="commentMessage">\s*<span>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        private val COMMENT_AUTHOR = Regex("""class="[^"]*usernameLink[^"]*"[^>]*>([^<]+)<""")
        /** Each comment's text, with the last commenter's name between it and the comment before it. */
        override fun comments(pageHtml: String): List<Pair<String, String>> {
            var from = 0
            return COMMENT_TEXT.findAll(pageHtml).mapNotNull { m ->
                val author = COMMENT_AUTHOR.findAll(pageHtml.substring(maxOf(from, m.range.first - 4000), m.range.first)).lastOrNull()
                from = m.range.last
                val text = unescape(m.groupValues[1].replace(Regex("<[^>]+>"), "")).trim()
                author?.let { it.groupValues[1].trim() to text }?.takeIf { text.isNotBlank() }
            }.take(25).toList()
        }

        /** The pornstars under the player ("Pornstars" row), not the site's menus. */
        override fun pagePeople(pageHtml: String): List<String> {
            val i = pageHtml.indexOf("pornstarsWrapper").takeIf { it >= 0 } ?: return emptyList()
            // Up to the end of that row's own list of names.
            val block = pageHtml.substring(i, minOf(pageHtml.length, i + 4000)).let { b ->
                val list = b.indexOf("class=\"tags\"")
                val end = if (list >= 0) b.indexOf("</div>", list) else -1
                if (end > 0) b.substring(0, end) else b.substring(0, minOf(b.length, 1500))
            }
            return PH_STAR.findAll(block).map { unescape(it.groupValues[1]).trim() }.filter { it.length in 3..40 }.distinct().take(6).toList()
        }
        private val PH_STAR = Regex("""href="/pornstar/[^"]+"[^>]*>\s*(?:<img[^>]*>\s*)?([^<]+?)\s*<""")

        /** `'categories_in_video' : 'Amateur,Babe,MILF,…'` in the player setup. */
        override fun pageTags(pageHtml: String): List<String> =
            Regex("""'categories_in_video'\s*:\s*'([^']+)'""").find(pageHtml)?.groupValues?.get(1)?.split(',')
                ?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() && it != "hd" && it != "exclusive" && !it.startsWith("verified") }.orEmpty()
        override fun hotspots(pageHtml: String): List<Int>? =
            Regex("""\"hotspots\":\[([0-9,]+)\]""").find(pageHtml)?.groupValues?.get(1)?.split(',')?.mapNotNull { it.toIntOrNull() }
        override fun streamIndexUrl(pageHtml: String): String? = mediaDefinitions(pageHtml)?.let { indexUrl(it, host) }
        override fun streamFromIndex(indexJson: JsonElement): TubeStream? = (indexJson as? JsonArray)?.let { bestOf(it, host) }
        override fun embedUrl(id: String) = "https://www.pornhub.com/embed/$id"
    }

    // ── YouPorn / Tube8 (Pornhub network: <article data-video-id> listings, mediaDefinitions player) ──

    sealed class ArticleEngine(host: String, label: String, private val watchPath: String) : TubeSite(host, label) {
        private val ARTICLE = Regex("""<article\b[^>]*\bdata-video-id="(\d+)"[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
        private val LABEL = Regex("""\baria-label="([^"]*)"""")
        private val HREF = Regex("""<a\b[^>]*\bhref="(/(?:watch|porn-video)/\d+/?[^"]*)"""")
        private val POSTER = Regex("""\bdata-(?:poster|thumbnail|src)=['"](https?://[^'"]+)['"]""")
        private val IMG = Regex("""<img\b[^>]*\bsrc="(https?://[^"]+)"""")
        private val DUR = Regex("""(?:video-)?duration[^>]*>\s*(?:<span[^>]*>)?\s*([\d:]+)""")

        override fun parseList(body: String): List<TubeVideo> = ARTICLE.findAll(body).map { m ->
            val id = m.groupValues[1]
            val head = m.value.substringBefore('>')
            val block = m.groupValues[2]
            val href = HREF.find(block)?.groupValues?.get(1)?.let(::unescape)
            TubeVideo(
                id = id,
                title = LABEL.find(head)?.groupValues?.get(1)?.let(::unescape)?.trim(),
                pageUrl = if (href != null) "https://www.$host$href" else "https://www.$host$watchPath$id/",
                thumb = (POSTER.find(block) ?: IMG.find(block))?.groupValues?.get(1)?.let(::unescape),
                durationSec = duration(DUR.find(block)?.groupValues?.get(1)),
            )
        }.distinctBy { it.id }.toList()

        override fun stream(pageHtml: String): TubeStream? = mediaDefinitions(pageHtml)?.let { fromMediaDefinitions(it, host) }
        override fun streamIndexUrl(pageHtml: String): String? = mediaDefinitions(pageHtml)?.let { indexUrl(it, host) }
        override fun streamFromIndex(indexJson: JsonElement): TubeStream? = (indexJson as? JsonArray)?.let { bestOf(it, host) }
        override fun embedUrl(id: String) = "https://www.$host/embed/$id/"
    }

    object Youporn : ArticleEngine("youporn.com", "YouPorn", "/watch/") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.youporn.com/browse/time/?page=${page + 1}"
            "top" -> "https://www.youporn.com/top_rated/?page=${page + 1}"
            else -> "https://www.youporn.com/search/?query=${enc(section)}&page=${page + 1}"
        }
    }

    object Tube8 : ArticleEngine("tube8.com", "Tube8", "/porn-video/") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.tube8.com/latest/page/${page + 1}/"
            "top" -> "https://www.tube8.com/top.html/"
            else -> "https://www.tube8.com/searches.html?q=${enc(section)}&page=${page + 1}"
        }
    }

    // ── xHamster ────────────────────────────────────────────────────────────

    object Xhamster : TubeSite("xhamster.com", "xHamster") {
        override val mirrors = listOf("xhamster2.com", "xhamster.desi", "xhamster19.com")
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://xhamster.com/newest/${page + 1}"
            "top" -> "https://xhamster.com/best/weekly/${page + 1}"
            else -> "https://xhamster.com/search/${enc(section).replace("+", "%20")}?page=${page + 1}"
        }

        /** Listing pages carry their videos as JSON (`videoThumbProps` inside `window.initials`). */
        override fun parseList(body: String): List<TubeVideo> {
            // The list sits inside window.initials (layoutPage.videoListProps / searchResult); some
            // pages don't carry the key as plain text, so look for it in the parsed object too.
            val arr = jsonAfter(body, "\"videoThumbProps\":")?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: initials(body)?.let(::findThumbProps)
            val fromJson = arr?.mapNotNull { it as? JsonObject }?.mapNotNull { v ->
                val url = v["pageURL"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                TubeVideo(
                    id = videoId(url),
                    title = v["title"]?.jsonPrimitive?.contentOrNull,
                    pageUrl = url,
                    thumb = v["thumbURL"]?.jsonPrimitive?.contentOrNull,
                    durationSec = (v["duration"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                    // The channel, creator or pornstar it's credited to: followable like any creator.
                    creator = (v["landing"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
                    published = (v["created"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it > 1_000_000_000L }?.let { java.time.Instant.ofEpochSecond(it) },
                )
            }.orEmpty()
            return if (fromJson.isNotEmpty()) fromJson else parseListHtml(body)
        }

        // Mobile layout: <a href="https://xhamster.com/videos/slug-xhABC" class="thumb-image-container …" aria-label="Title"><img … src="…webp">
        private val A = Regex("""<a\b[^>]*\bhref="(https://xhamster\.com/videos/[^"]+)"[^>]*\bclass="[^"]*thumb-image-container[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val LABEL = Regex("""\baria-label="([^"]*)"""")
        private val IMG = Regex("""<img\b[^>]*\bsrc="(https?://[^"]+)"""")
        private val DUR = Regex("""(?:video-duration|duration)[^>]*>\s*([\d:]+)""")

        private fun parseListHtml(body: String): List<TubeVideo> = A.findAll(body).map { m ->
            val url = m.groupValues[1]
            val tag = m.value.substringBefore('>')
            TubeVideo(
                id = videoId(url),
                title = LABEL.find(tag)?.groupValues?.get(1)?.let(::unescape)?.trim(),
                pageUrl = url,
                thumb = IMG.find(m.groupValues[2])?.groupValues?.get(1),
                durationSec = duration(DUR.find(m.groupValues[2])?.groupValues?.get(1)),
            )
        }.distinctBy { it.id }.toList()

        /** …/videos/some-slug-xhABC or …/videos/xhABC → xhABC. */
        internal fun videoId(url: String): String = url.substringBefore('?').trimEnd('/').substringAfterLast('/').substringAfterLast('-')

        /** Some networks get a page whose initials hold only site-wide settings: no layout, no results. */
        override fun isShell(body: String): Boolean {
            val ini = initials(body) ?: return false
            return ini["layoutPage"] == null && ini["searchResult"] == null && findThumbProps(ini) == null
        }

        /** The first "videoThumbProps" array anywhere under [e]. */
        internal fun findThumbProps(e: kotlinx.serialization.json.JsonElement): JsonArray? = when (e) {
            is JsonObject -> (e["videoThumbProps"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: e.values.firstNotNullOfOrNull(::findThumbProps)
            is JsonArray -> e.firstNotNullOfOrNull(::findThumbProps)
            else -> null
        }

        internal fun initials(html: String): JsonObject? =
            jsonAfter(html, "window.initials")?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

        override fun stream(pageHtml: String): TubeStream? {
            val sources = (initials(pageHtml)?.get("xplayerSettings") as? JsonObject)?.get("sources") as? JsonObject
            if (sources != null) {
                (sources["hls"] as? JsonObject)?.values?.mapNotNull { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
                    ?.firstOrNull { it.startsWith("http") }?.let { return TubeStream(it, MediaTypes.HLS_MIME) }
                (sources["standard"] as? JsonObject)?.get("mp4")?.let { it as? JsonArray }?.let { bestOf(it, host) }?.let { return it }
                // Since 2025 the URLs come enciphered: standard.h264[] = {url: hex, quality: "auto"|"720p"…}.
                ((sources["standard"] as? JsonObject)?.get("h264") as? JsonArray)?.let { list -> fromEnciphered(list)?.let { return it } }
            }
            Regex(""""url":"(https?:[^"]+\.m3u8[^"]*)"""").find(pageHtml)?.groupValues?.get(1)?.let { return TubeStream(unescape(it), MediaTypes.HLS_MIME) }
            return null
        }

        override fun embedUrl(id: String) = "https://xhamster.com/embed/$id"

        /** The "auto" entry is the adaptive HLS playlist; otherwise the best MP4 up to 1080p. */
        internal fun fromEnciphered(list: JsonArray): TubeStream? {
            val entries = list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
                val raw = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = if (raw.startsWith("http")) raw else decipher(raw) ?: return@mapNotNull null
                if (!url.startsWith("http")) return@mapNotNull null
                (o["quality"]?.jsonPrimitive?.contentOrNull ?: "") to url
            }
            entries.firstOrNull { it.first == "auto" || it.second.contains(".m3u8") }?.let {
                return TubeStream(it.second, if (it.second.contains(".m3u8")) MediaTypes.HLS_MIME else null)
            }
            return entries.mapNotNull { (q, u) -> Regex("""\d+""").find(q)?.value?.toIntOrNull()?.let { it to u } }
                .filter { it.first <= maxHeight }.maxByOrNull { it.first }?.let { TubeStream(it.second) }
        }

        /**
         * Hex string → URL. Byte 0 picks a generator, bytes 1–4 are its seed (little-endian), and the
         * rest is the URL XORed with the generator's output, one byte per step.
         */
        internal fun decipher(hex: String): String? {
            if (hex.length < 12 || hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val seed = (bytes[1].toInt() and 0xff) or ((bytes[2].toInt() and 0xff) shl 8) or
                ((bytes[3].toInt() and 0xff) shl 16) or ((bytes[4].toInt() and 0xff) shl 24)
            val gen = ByteGenerator(bytes[0].toInt() and 0xff, seed)
            if (!gen.known) return null
            val out = StringBuilder(bytes.size - 5)
            for (i in 5 until bytes.size) out.append(((bytes[i].toInt() and 0xff) xor gen.next()).toChar())
            return out.toString()
        }

        private class ByteGenerator(private val algo: Int, private var s: Int) {
            val known get() = algo in 1..7

            fun next(): Int = step() and 0xff

            private fun step(): Int = when (algo) {
                1 -> { s = s * 1664525 + 1013904223; s }
                2 -> { var t = s xor (s shl 13); t = t xor (t ushr 17); t = t xor (t shl 5); s = t; t }
                3 -> {
                    s += 0x9e3779b9.toInt()
                    var t = s xor (s ushr 16); t *= 0x85ebca77.toInt(); t = t xor (t ushr 13); t *= 0xc2b2ae3d.toInt()
                    t xor (t ushr 16)
                }
                4 -> {
                    s += 0x6d2b79f5
                    var t = (s shl 7) or (s ushr 25); t += 0x9e3779b9.toInt(); t = t xor (t ushr 11)
                    t * 0x27d4eb2d
                }
                5 -> { var t = s xor (s shl 7); t = t xor (t ushr 9); t = t xor (t shl 8); t += 0xa5a5a5a5.toInt(); s = t; t }
                6 -> {
                    s = s * 0x2c9277b5 + 0xac564b05.toInt()
                    val t = s xor (s ushr 18)
                    t ushr ((s ushr 27) and 31)
                }
                7 -> {
                    s += 0x9e3779b9.toInt()
                    var e = s xor (s shl 5); e *= 0x7feb352d; e = e xor (e ushr 15)
                    e * 0x846ca68b.toInt()
                }
                else -> 0
            }
        }
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

        override fun stream(pageHtml: String): TubeStream? = mediaDefinitions(pageHtml)?.let { fromMediaDefinitions(it, host) }
        override fun streamIndexUrl(pageHtml: String): String? = mediaDefinitions(pageHtml)?.let { indexUrl(it, host) }
        override fun streamFromIndex(indexJson: JsonElement): TubeStream? = (indexJson as? JsonArray)?.let { bestOf(it, host) }
        override fun embedUrl(id: String) = "https://embed.redtube.com/?id=$id"
    }

    // ── TNAFlix, Porntrex, YouJizz (HTML listings; plain MP4s on the video page) ──

    /** Listing pages split into one chunk per video at each [marker] occurrence. */
    protected fun chunks(body: String, marker: Regex): List<Pair<String, String>> {
        val hits = marker.findAll(body).toList()
        return hits.mapIndexed { i, m ->
            m.groupValues[1] to body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 6000))
        }
    }

    /** KVS flashvars: video_url, video_alt_url, video_alt_url2… with their "720p" labels; the best up to [maxHeight]. */
    protected fun kvsBest(html: String): TubeStream? {
        val files = KVS_FILE.findAll(html).map { m ->
            val key = m.groupValues[1]
            val q = Regex(Regex.escape(key) + """_text\s*:\s*'(\d{3,4})p""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 360
            q to unescape(m.groupValues[2])
        // Members-only qualities are listed as a link to the login page: not a file.
        }.filter { it.second.startsWith("https://") && "login" !in it.second.substringBefore("?v-acctoken") }.toList()
        if (files.isEmpty()) return null
        val pick = files.filter { it.first <= maxHeight }.maxByOrNull { it.first } ?: files.minBy { it.first }
        return TubeStream(pick.second, "video/mp4")
    }

    /** Category/tag links in the video's own block (from "Categories:" on), without hashes and stray words. */
    protected fun kvsOwnTags(html: String, tag: Regex): List<String> {
        val i = html.indexOf("Categories:").takeIf { it >= 0 } ?: html.indexOf("Tags:").takeIf { it >= 0 } ?: return emptyList()
        return tag.findAll(html.substring(i, minOf(html.length, i + 3000))).map { it.groupValues[1] }
            .filter { it.length > 2 && !it.matches(Regex("[0-9a-f]{20,}")) }
            .map { it.replace('-', ' ') }.distinct().take(30).toList()
    }

    /** The tags in a KVS player's settings (tags: 'a, b, c'), without the site's filler words. */
    protected fun kvsPlayerTags(html: String): List<String> =
        KVS_TAGS.find(html)?.groupValues?.get(1)?.split(',')?.map { unescape(it).trim().lowercase() }
            ?.filter { it.length > 2 && it !in KVS_FILLER }?.distinct()?.take(20).orEmpty()

    /** Best "quality → url" pick up to 1080p (4K is too heavy for a phone feed). */
    protected fun best(options: List<Pair<Int, String>>): TubeStream? =
        options.filter { it.first in 1..maxHeight && it.second.startsWith("http") }.maxByOrNull { it.first }?.let { TubeStream(it.second, "video/mp4") }

    /** TNAFlix and EMPFlix: one company, one template (listing with preview clip and uploader; MP4s by height on the video page). */
    abstract class TnaNet(host: String, label: String) : TubeSite(host, label) {
        override fun listUrl(section: String, page: Int): String = when (section) {
            // The very newest uploads are often still processing (their files answer 404): start a page in.
            "new" -> "https://www.$host/new/${page + 2}"
            "top" -> "https://www.$host/toprated/${page + 1}"
            else -> "https://www.$host/search?what=${enc(section)}&page=${page + 1}"
        }
        private val esc = Regex.escape(host)
        private val MARK = Regex("""<div data-vid="(\d+)"""")
        private val HREF = Regex("""href="(https://www\.$esc/[^"]+/video\d+)"""")
        private val IMG = Regex("""<img[^>]*\bsrc="(https://[^"]+\.jpg[^"]*)"""")
        private val ALT = Regex("""<img[^>]*\balt="([^"]*)"""")
        private val DUR = Regex("""video-duration">\s*([\d:]+)""")
        private val TRAILER = Regex("""data-trailer="(https://[^"]+)"""")
        private val PROFILE = Regex("""href="https://www\.$esc/profile/[^"]+"[^>]*>\s*([^<]+?)\s*</a>""")
        private val QUALITY = Regex("""max-quality">\s*(\d{3,4})p""")
        private val SOURCE = Regex("""<source src="([^"]+)" type="video/mp4" size="(\d+)"""")

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (id, c) ->
            val href = HREF.find(c)?.groupValues?.get(1) ?: return@mapNotNull null
            TubeVideo(
                id, ALT.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim(), href, IMG.find(c)?.groupValues?.get(1)?.let(::unescape), duration(DUR.find(c)?.groupValues?.get(1)),
                creator = PROFILE.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifEmpty { null },
                preview = TRAILER.find(c)?.groupValues?.get(1)?.let(::unescape),
                height = QUALITY.find(c)?.groupValues?.get(1)?.toIntOrNull(),
            )
        }.distinctBy { it.id }

        override fun stream(pageHtml: String): TubeStream? =
            best(SOURCE.findAll(pageHtml).map { it.groupValues[2].toInt() to unescape(it.groupValues[1]) }.toList())
        override fun embedUrl(id: String) = "https://player.$host/video/$id"
    }

    object Tnaflix : TnaNet("tnaflix.com", "TNAFlix")
    object Empflix : TnaNet("empflix.com", "EMPFlix")

    object Porntrex : TubeSite("porntrex.com", "Porntrex") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.porntrex.com/latest-updates/${page + 1}/"
            "top" -> "https://www.porntrex.com/top-rated/${page + 1}/"
            else -> "https://www.porntrex.com/search/${enc(section).replace("+", "-")}/${page + 1}/"
        }
        private val MARK = Regex("""data-item-id="(\d+)"""")
        private val HREF = Regex("""href="(https://www\.porntrex\.com/video/[^"]+)"""")
        private val QUALITY = Regex("""<span class="quality">(\d{3,4}p|4[Kk])</span>""")
        private val IMG = Regex("""data-src="(//[^"]+\.jpg[^"]*)"""")
        private val ALT = Regex("""\balt="([^"]*)"""")
        private val DUR = Regex("""class="durations">(?:<i[^>]*></i>)?\s*([\d:]+)""")
        // KVS flashvars: video_url / video_alt_url / video_alt_url2… with matching *_text "720p HD"
        private val URLS = Regex("""\b(video(?:_alt)?_url\d*):\s*'(https://[^']+)'""")
        private fun textOf(html: String, key: String) =
            Regex("""\b${key}_text:\s*'(\d+)p""").find(html)?.groupValues?.get(1)?.toIntOrNull()

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (id, c) ->
            val href = HREF.find(c)?.groupValues?.get(1) ?: return@mapNotNull null
            TubeVideo(
                id, ALT.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim(), href, IMG.find(c)?.groupValues?.get(1)?.let { "https:" + unescape(it) }, duration(DUR.find(c)?.groupValues?.get(1)),
                height = QUALITY.find(c)?.groupValues?.get(1)?.let { q -> if (q.equals("4k", true)) 2160 else q.removeSuffix("p").toIntOrNull() },
            )
        }.distinctBy { it.id }

        override fun stream(pageHtml: String): TubeStream? =
            best(URLS.findAll(pageHtml).mapNotNull { m -> textOf(pageHtml, m.groupValues[1])?.let { it to m.groupValues[2] } }.toList())
        override fun embedUrl(id: String) = "https://www.porntrex.com/embed/$id"
    }

    /**
     * PornHat (a KVS site): listings carry models and studios but not tags; the video page has plain
     * <source> tags labelled 360p…720p (its get_file links are signed per visit, so they're read
     * from the page each time) and the full tag list.
     */
    object PornHat : TubeSite("pornhat.com", "PornHat") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> if (page == 0) "https://www.pornhat.com/" else "https://www.pornhat.com/${page + 1}/"
            "top" -> "https://www.pornhat.com/top-rated/${if (page == 0) "" else "${page + 1}/"}"
            else -> "https://www.pornhat.com/search/${enc(section.lowercase()).replace("+", "-")}/${if (page == 0) "" else "${page + 1}/"}"
        }
        private val MARK = Regex("""class="item thumb-bl thumb-bl-video[^"]*"""")
        private val HREF = Regex("""href="(/video/[^"]+/)"\s+title="([^"]*)"""")
        private val IMG = Regex("""data-original="(https://[^"]+\.jpg)"""")
        private val ID = Regex("""videos_screenshots/\d+/(\d+)/""")
        private val DUR = Regex("""fa-clock-o"></i>\s*<span>(\d{1,2}:\d{2}(?::\d{2})?)</span>""")
        private val AGO = Regex("""fa-calendar-o"></i>\s*<span>(\d+)\s+(minute|hour|day|week|month|year)s?\s+ago</span>""")
        private val MODEL = Regex("""href="/models/[^"]+/"\s+title="([^"]+)"""")
        private val STUDIO = Regex("""href="/sites/[^"]+/"\s+title="([^"]+)"""")
        private val SOURCE = Regex("""<source src="(https://[^"]+)"[^>]*label="(\d+)p"""")
        private val TAG = Regex("""href="(?:https://www\.pornhat\.com)?/tags/([a-z0-9-]+)/"""")

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList()
            return hits.mapIndexedNotNull { i, m ->
                val c = body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 6000))
                val link = HREF.find(c) ?: return@mapIndexedNotNull null
                val thumb = IMG.find(c)?.groupValues?.get(1)
                val id = thumb?.let { ID.find(it)?.groupValues?.get(1) } ?: link.groupValues[1].trim('/').substringAfterLast('/')
                TubeVideo(
                    id = id,
                    title = unescape(link.groupValues[2]).trim().ifEmpty { null },
                    pageUrl = "https://www.pornhat.com" + link.groupValues[1],
                    thumb = thumb,
                    durationSec = duration(DUR.find(c)?.groupValues?.get(1)),
                    published = AGO.find(c)?.let { ago(it.groupValues[1].toLong(), it.groupValues[2]) },
                    creator = (MODEL.find(c) ?: STUDIO.find(c))?.groupValues?.get(1)?.let(::unescape),
                )
            }.distinctBy { it.id }
        }

        // The ".mp4" links redirect to an HLS playlist on the CDN: play them as HLS.
        override fun stream(pageHtml: String): TubeStream? =
            best(SOURCE.findAll(pageHtml).map { it.groupValues[2].toInt() to unescape(it.groupValues[1]) }.toList())
                ?.copy(mime = MediaTypes.HLS_MIME)
        override fun pageTags(pageHtml: String): List<String> =
            TAG.findAll(pageHtml).map { it.groupValues[1].replace('-', ' ') }.filter { it != "hd" }.distinct().take(30).toList()
        override fun embedUrl(id: String) = "https://www.pornhat.com/embed/$id"

        /** "2 hours ago" → roughly when. */
        private fun ago(n: Long, unit: String): java.time.Instant = java.time.Instant.now().minusSeconds(
            n * when (unit) { "minute" -> 60L; "hour" -> 3_600L; "day" -> 86_400L; "week" -> 604_800L; "month" -> 2_592_000L; else -> 31_536_000L },
        )
    }

    /**
     * HQPorner: studio scenes in HD and 4K. Listings carry title, thumbnail and length; the tags
     * (categories) and the performer are on the video page, which frames a mydaddy.cc player whose
     * page lists 360/720/1080 MP4s.
     */
    object HqPorner : TubeSite("hqporner.com", "HQPorner") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://hqporner.com/hdporn/${page + 1}"
            "top" -> if (page == 0) "https://hqporner.com/top/week" else "https://hqporner.com/top/week/${page + 1}"
            else -> "https://hqporner.com/?q=${enc(section)}" + if (page > 0) "&p=${page + 1}" else ""
        }
        override val mirrors = listOf("m.hqporner.com")
        private val MARK = Regex("""<a href="(/hdporn/(\d+)-[^"]+\.html)"[^>]*>\s*<img src="([^"]+)"[^>]*\balt="([^"]*)"""")
        private val DUR = Regex("""fa-clock-o"[^>]*></i>\s*([0-9hms ]+?)\s*</span>""")
        private val CATEGORY = Regex("""href="/category/([a-z0-9-]+)"""")
        private val ACTRESS = Regex("""href="/actress/([a-z0-9-]+)"""")
        private val PLAYER = Regex("""(//mydaddy\.cc/video/[0-9a-f]+/)""")
        private val MP4 = Regex("""(//[a-z0-9.]+\.bigcdn\.cc/pubs/[^"'\\ ]+/(\d{3,4})\.mp4)""")

        // Pages are big (300–500 KB): tags are read when a video is played, not before listing it.
        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList()
            return hits.mapIndexed { i, m ->
                val after = body.substring(m.range.last, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.last + 3000))
                TubeVideo(
                    id = m.groupValues[2],
                    title = unescape(m.groupValues[4]).trim().ifEmpty { null },
                    pageUrl = "https://hqporner.com" + m.groupValues[1],
                    thumb = absolute(m.groupValues[3], host),
                    durationSec = duration(DUR.find(after)?.groupValues?.get(1)),
                )
            }.distinctBy { it.id }
        }

        override fun pageTags(pageHtml: String): List<String> =
            (CATEGORY.findAll(pageHtml).map { it.groupValues[1] }.filterNot { it.first().isDigit() } +
                ACTRESS.findAll(pageHtml).map { it.groupValues[1] })
                .map { it.removeSuffix("-porn").replace('-', ' ') }.distinct().take(30).toList()

        override fun playerUrl(pageHtml: String): String? = PLAYER.find(pageHtml)?.groupValues?.get(1)?.let { "https:$it" }
        override fun stream(pageHtml: String): TubeStream? =
            best(MP4.findAll(pageHtml).map { it.groupValues[2].toInt() to "https:" + it.groupValues[1] }.toList())
        override fun embedUrl(id: String) = "https://hqporner.com/hdporn/$id.html"
    }

    /**
     * Porndig: studio scenes, many in 4K. Listings give title, length and thumbnail; the video page
     * has the performer, studio and tags, and frames a player page listing H.264 (and AV1) MP4s by
     * height, of which the H.264 ones are used (AV1 isn't decoded by many phones).
     */
    object Porndig : TubeSite("porndig.com", "Porndig") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new", "top" -> "https://www.porndig.com/video/"
            else -> "https://www.porndig.com/search/${enc(section.lowercase()).replace("+", "-")}"
        }
        private val MARK = Regex("""<section id="multifilter_videos_(\d+)"[^>]*data-post_duration="(\d+)"""")
        private val LINK = Regex("""<a href="(/videos/\d+/[^"]+\.html)">([^<]+)</a>""")
        private val IMG = Regex("""class="img_sfw_class[^"]*" src="(https://image-cdn\.porndig\.com/[^"]+\.jpg)"""")
        private val CHANNEL = Regex("""href="/channels/\d+/([a-z0-9-]+)"""")
        private val PERSON = Regex("""href="/(?:pornstars|studios)/\d+/([a-z0-9-]+)\.html"""")
        private val PLAYER = Regex("""<iframe[^>]*\bsrc="(https://videos\.porndig\.com/player/index/[0-9/]+)"""")
        private val MP4 = Regex("""(https:\\?/\\?/video-cdn\.porndig\.com\\?/videos\\?/[^"]+?_h264_\d+_(\d{3,4})\.mp4[^"]*)"""")

        // Pages are big (300–500 KB): tags are read when a video is played, not before listing it.
        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (id, c) ->
            val link = LINK.find(c) ?: return@mapNotNull null
            TubeVideo(
                id = id,
                title = unescape(link.groupValues[2]).trim(),
                pageUrl = "https://www.porndig.com" + link.groupValues[1],
                thumb = IMG.find(c)?.groupValues?.get(1),
                durationSec = MARK.find(c)?.groupValues?.get(2)?.toIntOrNull(),
                preview = Regex("""data-vid='(https://[^']+\.mp4)'""").find(c)?.groupValues?.get(1),
            )
        }.distinctBy { it.id }

        /** The video's own tags come after its performer/studio links (the site-wide menu comes before). */
        override fun pageTags(pageHtml: String): List<String> {
            val first = PERSON.find(pageHtml) ?: return emptyList()
            val own = pageHtml.substring(first.range.first)
            val people = PERSON.findAll(own).map { it.groupValues[1] }.take(4).toList()
            return (CHANNEL.findAll(own).map { it.groupValues[1] }.take(20).toList() + people)
                .filterNot { it == "full-hd" || it == "uhd-4k" || it == "hd" }
                .map { it.replace('-', ' ') }.distinct()
        }

        override fun playerUrl(pageHtml: String): String? = PLAYER.find(pageHtml)?.groupValues?.get(1)
        override fun stream(pageHtml: String): TubeStream? =
            best(MP4.findAll(pageHtml).map { it.groupValues[2].toInt() to it.groupValues[1].replace("\\/", "/") }.toList())
        override fun embedUrl(id: String) = "https://www.porndig.com/videos/$id/"
    }

    /** PerfectGirls: the same KVS template as PornHat (studios, performers, tags on the video page). */
    object PerfectGirls : TubeSite("perfectgirls.xxx", "PerfectGirls") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> if (page == 0) "https://www.perfectgirls.xxx/" else "https://www.perfectgirls.xxx/${page + 1}/"
            "top" -> "https://www.perfectgirls.xxx/top-rated/${if (page == 0) "" else "${page + 1}/"}"
            else -> "https://www.perfectgirls.xxx/search/${enc(section.lowercase()).replace("+", "-")}/${if (page == 0) "" else "${page + 1}/"}"
        }
        private val PREVIEW_CLIP = Regex("""data-preview-custom="(https://[^"]+\.mp4/?)"""")
        private val MARK = Regex("""class="\s*item thumb-bl thumb-bl-video[^"]*"""")
        private val HREF = Regex("""href="(/video/(\d+)/)"\s+title="([^"]*)"""")
        private val IMG = Regex("""data-original="(https://[^"]+\.jpg)"""")
        private val DUR = Regex("""fa-clock-o"></i>\s*<span>(\d{1,2}:\d{2}(?::\d{2})?)</span>""")
        private val MODEL = Regex("""href="/pornstars/[^"]+/"\s+title="([^"]+)"""")
        private val STUDIO = Regex("""href="/channels/[^"]+/"\s+title="([^"]+)"""")
        private val SOURCE = Regex("""<source src="(https://[^"]+)"[^>]*label="(\d+)p"""")
        private val TAG = Regex("""href="(?:https://www\.perfectgirls\.xxx)?/tags/([a-z0-9-]+)/"""")

        // Pages are big (300–500 KB): tags are read when a video is played, not before listing it.
        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList()
            return hits.mapIndexedNotNull { i, m ->
                val c = body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 6000))
                val link = HREF.find(c) ?: return@mapIndexedNotNull null
                TubeVideo(
                    id = link.groupValues[2],
                    title = unescape(link.groupValues[3]).trim().ifEmpty { null },
                    pageUrl = "https://www.perfectgirls.xxx" + link.groupValues[1],
                    thumb = IMG.find(c)?.groupValues?.get(1),
                    durationSec = duration(DUR.find(c)?.groupValues?.get(1)),
                    creator = (MODEL.find(c) ?: STUDIO.find(c))?.groupValues?.get(1)?.let(::unescape),
                    preview = PREVIEW_CLIP.find(c)?.groupValues?.get(1),
                )
            }.distinctBy { it.id }
        }

        override fun stream(pageHtml: String): TubeStream? =
            best(SOURCE.findAll(pageHtml).map { it.groupValues[2].toInt() to unescape(it.groupValues[1]) }.toList())
        override fun pageTags(pageHtml: String): List<String> =
            TAG.findAll(pageHtml).map { it.groupValues[1] }.filterNot { it == "hd" || it.startsWith("best-") || it.startsWith("hottest-") }
                .map { it.replace('-', ' ') }.distinct().take(30).toList()
        override fun embedUrl(id: String) = "https://www.perfectgirls.xxx/embed/$id"
    }

    /** OK.XXX: a large KVS tube (the PornHat template: models, sites, and tags on the video page). */
    object OkXxx : TubeSite("ok.xxx", "OK.XXX") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> if (page == 0) "https://ok.xxx/" else "https://ok.xxx/latest-updates/${page + 1}/"
            "top" -> "https://ok.xxx/top-rated/${if (page == 0) "" else "${page + 1}/"}"
            else -> "https://ok.xxx/search/${enc(section.lowercase()).replace("+", "-")}/${if (page == 0) "" else "${page + 1}/"}"
        }
        private val PREVIEW_CLIP = Regex("""data-preview-custom="(https://[^"]+\.mp4/?)"""")
        private val MARK = Regex("""class="\s*item thumb-bl thumb-bl-video[^"]*"""")
        private val HREF = Regex("""href="(/video/(\d+)/)"\s+title="([^"]*)"""")
        private val IMG = Regex("""data-original="(https://[^"]+\.jpg)"""")
        private val DUR = Regex("""fa-clock-o"></i>\s*<span>(\d{1,2}:\d{2}(?::\d{2})?)</span>""")
        private val MODEL = Regex("""href="/(?:models|pornstars)/[^"]+/"\s+title="([^"]+)"""")
        private val STUDIO = Regex("""href="/(?:sites|channels)/[^"]+/"\s+title="([^"]+)"""")
        private val SOURCE = Regex("""<source src="(https://[^"]+)"[^>]*label="(\d+)p"""")
        private val TAG = Regex("""href="(?:https://ok\.xxx)?/tags/([a-z0-9-]+)/"""")

        // Pages are big (300–500 KB): tags are read when a video is played, not before listing it.
        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList()
            return hits.mapIndexedNotNull { i, m ->
                val c = body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 6000))
                val link = HREF.find(c) ?: return@mapIndexedNotNull null
                TubeVideo(
                    id = link.groupValues[2],
                    title = unescape(link.groupValues[3]).trim().ifEmpty { null },
                    pageUrl = "https://ok.xxx" + link.groupValues[1],
                    thumb = IMG.find(c)?.groupValues?.get(1),
                    durationSec = duration(DUR.find(c)?.groupValues?.get(1)),
                    creator = (MODEL.find(c) ?: STUDIO.find(c))?.groupValues?.get(1)?.let(::unescape),
                    preview = PREVIEW_CLIP.find(c)?.groupValues?.get(1),
                )
            }.distinctBy { it.id }
        }

        override fun stream(pageHtml: String): TubeStream? =
            best(SOURCE.findAll(pageHtml).map { it.groupValues[2].toInt() to unescape(it.groupValues[1]) }.toList())
        override fun pageTags(pageHtml: String): List<String> =
            TAG.findAll(pageHtml).map { it.groupValues[1] }.filterNot { it == "hd" || it.startsWith("best-") || it.startsWith("hottest-") }
                .map { it.replace('-', ' ') }.distinct().take(30).toList()
        override fun embedUrl(id: String) = "https://ok.xxx/embed/$id"
    }

    /** PornGo: a KVS tube with performers in the listing and categories and one MP4 on the video page. */
    object PornGo : TubeSite("porngo.com", "PornGo") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> if (page == 0) "https://www.porngo.com/" else "https://www.porngo.com/latest-updates/${page + 1}/"
            "top" -> "https://www.porngo.com/top-rated/${if (page == 0) "" else "${page + 1}/"}"
            else -> "https://www.porngo.com/search/${enc(section.lowercase()).replace("+", "-")}/${if (page == 0) "" else "${page + 1}/"}"
        }
        private val MARK = Regex("""<a href="(https://www\.porngo\.com/videos/(\d+)/[^"]+/)" class="thumb__top""")
        private val IMG = Regex("""<img src="(https://[^"]+\.jpg)"[^>]*\balt="([^"]*)"""")
        private val DUR = Regex("""thumb__duration">([\d:]+)<""")
        private val MODEL = Regex("""href="https://www\.porngo\.com/models/[^"]+/" class="thumb-models__link">([^<]+)<""")
        private val SOURCE = Regex("""<source src='(https://www\.porngo\.com/get_file/[^']+)'[^>]*type='video/mp4'""")
        private val CATEGORY = Regex("""href="https://www\.porngo\.com/categories/([a-z0-9-]+)/"""")

        // Pages are big (300–500 KB): tags are read when a video is played, not before listing it.
        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (_, c) ->
            val m = MARK.find(c) ?: return@mapNotNull null
            val img = IMG.find(c)
            TubeVideo(
                id = m.groupValues[2],
                title = img?.groupValues?.get(2)?.let(::unescape)?.trim()?.ifEmpty { null },
                pageUrl = m.groupValues[1],
                thumb = img?.groupValues?.get(1),
                durationSec = duration(DUR.find(c)?.groupValues?.get(1)),
                creator = MODEL.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim(),
                preview = Regex("""data-preview="(https://[^"]+\.mp4)"""").find(c)?.groupValues?.get(1),
            )
        }.distinctBy { it.id }

        override fun stream(pageHtml: String): TubeStream? =
            SOURCE.find(pageHtml)?.groupValues?.get(1)?.let { TubeStream(unescape(it), "video/mp4") }
        override fun pageTags(pageHtml: String): List<String> =
            CATEGORY.findAll(pageHtml).map { it.groupValues[1].replace(Regex("\\d+$"), "") }
                .filterNot { it == "hd-videos" || it == "hot" || it == "hottest" || it == "sex" }
                .map { it.replace('-', ' ') }.distinct().take(30).toList()
        override fun embedUrl(id: String) = "https://www.porngo.com/embed/$id"
    }

    /** 3Movs: a KVS tube; HQ/LQ MP4s in the player config, pornstars and tags on the video page. */
    object ThreeMovs : TubeSite("3movs.com", "3Movs") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.3movs.com/latest-updates/${if (page == 0) "" else "${page + 1}/"}"
            "top" -> "https://www.3movs.com/top-rated/${if (page == 0) "" else "${page + 1}/"}"
            // Search has one page of results; later "pages" sort them differently instead.
            else -> when (page) {
                0 -> "https://www.3movs.com/search_videos/?q=${enc(section)}"
                1 -> "https://www.3movs.com/search_videos/most-viewed/all-time/?q=${enc(section)}"
                else -> "https://www.3movs.com/search_videos/most-viewed/month/?q=${enc(section)}"
            }
        }
        private val MARK = Regex("""class="item thumb\s""")
        private val HREF = Regex("""class="wrap_image" href="https://www\.3movs\.com/videos/(\d+)/[^"]*" title="([^"]*)"""")
        private val IMG = Regex("""data-src="(https://img\.3movs\.com/[^"]+\.jpg)"""")
        private val CLIP = Regex("""data-preview="(https://www\.3movs\.com/get_file/[^"]+\.mp4/?)"""")
        private val DUR = Regex("""<div class="time">(\d{1,2}:\d{2}(?::\d{2})?)</div>""")
        private val URL_HQ = Regex("""video_url: '(https://[^']+)'""")
        private val URL_LQ = Regex("""video_alt_url: '(https://[^']+)'""")
        private val TAG = Regex("""href="https://www\.3movs\.com/tags/([a-z0-9-]+)/"""")

        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList()
            return hits.mapIndexedNotNull { i, m ->
                val c = body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 5000))
                val link = HREF.find(c) ?: return@mapIndexedNotNull null
                TubeVideo(
                    id = link.groupValues[1],
                    title = unescape(link.groupValues[2]).trim().ifEmpty { null },
                    pageUrl = HREF_URL.find(c)!!.groupValues[1],
                    thumb = IMG.find(c)?.groupValues?.get(1),
                    durationSec = duration(DUR.find(c)?.groupValues?.get(1)),
                    preview = CLIP.find(c)?.groupValues?.get(1),
                )
            }.distinctBy { it.id }
        }
        private val HREF_URL = Regex("""class="wrap_image" href="(https://www\.3movs\.com/videos/\d+/[^"]*)"""")

        override fun stream(pageHtml: String): TubeStream? =
            (URL_HQ.find(pageHtml) ?: URL_LQ.find(pageHtml))?.let { TubeStream(it.groupValues[1], "video/mp4") }
        override fun pageTags(pageHtml: String): List<String> =
            TAG.findAll(pageHtml).map { it.groupValues[1].replace('-', ' ') }.distinct().take(30).toList()
        override fun embedUrl(id: String) = "https://www.3movs.com/embed/$id"
    }

    /** AnySex: a big KVS tube; 240/480/720p sources on the video page, channels and models on every tile. */
    object AnySex : TubeSite("anysex.com", "AnySex") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://anysex.com/videos/new/${if (page == 0) "" else "${page + 1}/"}"
            "top" -> "https://anysex.com/videos/top-rated/${if (page == 0) "" else "${page + 1}/"}"
            else -> "https://anysex.com/search/${if (page == 0) "" else "${page + 1}/"}?q=${enc(section.lowercase()).replace("+", "-")}"
        }
        private val MARK = Regex("""<div class="item\s[^"]*"\s+data-video-id="(\d+)"""")
        private val HREF = Regex("""<a href="(https://anysex\.com/video/\d+/[^"]*)"""")
        private val IMG = Regex("""<img\s+src="(https://i\d*\.anysex\.com/[^"]+\.jpg)"""")
        private val CLIP = Regex("""data-preview="(https://preview\.anysex\.com/[^"]+\.mp4)"""")
        private val IS_HD = Regex("""class="is-hd hd(\d{3,4})""")
        private val DUR = Regex("""<div class="duration">(\d{1,2}:\d{2}(?::\d{2})?)</div>""")
        private val TITLE = Regex("""<strong class="title">\s*(.*?)\s*</strong>""", RegexOption.DOT_MATCHES_ALL)
        private val AUTHOR = Regex("""class="author-link"><a href="https://anysex\.com/(?:models|channels)/[^"]+/">(?:<i[^>]*></i>)?([^<]+)<""")
        private val SOURCE = Regex("""<source id="video_source_\d+" src="(https://[^"]+)" type="video/mp4" title="(\d+)p"""")
        private val TAG = Regex("""href="https://anysex\.com/tags/([a-z0-9-]+)/"""")

        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList()
            return hits.mapIndexedNotNull { i, m ->
                val c = body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 5000))
                val link = HREF.find(c) ?: return@mapIndexedNotNull null
                TubeVideo(
                    id = m.groupValues[1],
                    title = TITLE.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifEmpty { null },
                    pageUrl = link.groupValues[1],
                    thumb = IMG.find(c)?.groupValues?.get(1),
                    durationSec = duration(DUR.find(c)?.groupValues?.get(1)),
                    creator = AUTHOR.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifEmpty { null },
                    preview = CLIP.find(c)?.groupValues?.get(1),
                    height = IS_HD.find(c)?.groupValues?.get(1)?.toIntOrNull(),
                )
            }.distinctBy { it.id }
        }

        override fun stream(pageHtml: String): TubeStream? =
            best(SOURCE.findAll(pageHtml).map { it.groupValues[2].toInt() to unescape(it.groupValues[1]) }.toList())
        override fun pageTags(pageHtml: String): List<String> =
            TAG.findAll(pageHtml).map { it.groupValues[1] }.filterNot { it in setOf("porn", "sex", "xxx", "hd") }
                .map { it.replace('-', ' ') }.distinct().take(30).toList()
        override fun embedUrl(id: String) = "https://anysex.com/embed/$id/"
    }

    /** ZB Porn: a KVS tube; preview clips on every tile, a few MP4s on the video page (the best by bitrate wins). */
    object ZbPorn : TubeSite("zbporn.com", "ZB Porn") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://zbporn.com/latest-updates/${if (page == 0) "" else "${page + 1}/"}"
            "top" -> "https://zbporn.com/top-rated/${if (page == 0) "" else "${page + 1}/"}"
            else -> "https://zbporn.com/search/${enc(section.lowercase()).replace("+", "-")}/${if (page == 0) "" else "${page + 1}/"}"
        }
        private val MARK = Regex("""<a class="th-image-link" href="(https://zbporn\.com/videos/(\d+)/[^"]*)" title="([^"]*)"""")
        private val CLIP = Regex("""data-preview="(https://[^"]+\.mp4)"""")
        private val IMG = Regex("""<img src="(https://[^"]+\.jpg)"""")
        private val DUR = Regex("""<span class="th-duration">(\d{1,2}:\d{2}(?::\d{2})?)</span>""")
        private val FILE = Regex("""video(?:_alt)?_url\d*: '(https://[^']+)'""")
        private val BITRATE = Regex("""[?&]br=(\d+)""")
        private val TAG = Regex("""href="https://zbporn\.com/(?:tags|categories)/([a-z0-9-]+)/"""")

        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList()
            return hits.mapIndexed { i, m ->
                val c = body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 4000))
                TubeVideo(
                    id = m.groupValues[2],
                    title = unescape(m.groupValues[3]).trim().ifEmpty { null },
                    pageUrl = m.groupValues[1],
                    thumb = IMG.find(c)?.groupValues?.get(1),
                    durationSec = duration(DUR.find(c)?.groupValues?.get(1)),
                    preview = CLIP.find(m.value + c.take(600))?.groupValues?.get(1),
                )
            }.distinctBy { it.id }
        }

        override fun stream(pageHtml: String): TubeStream? =
            FILE.findAll(pageHtml).map { it.groupValues[1] }.distinct()
                .maxByOrNull { BITRATE.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
                ?.let { TubeStream(it, "video/mp4") }
        override fun pageTags(pageHtml: String): List<String> =
            TAG.findAll(pageHtml).map { it.groupValues[1].replace('-', ' ') }.distinct().take(30).toList()
        override fun embedUrl(id: String) = "https://zbporn.com/embed/$id/"
    }

    /**
     * KVS's stock theme (Analdin): each listing link carries its preview clip (vthumb), the
     * thumbnail is lazy-loaded (data-original), the length sits in a comment; the video page has
     * video_url / video_alt_url with their quality, and the video's own categories under "Categories:".
     */
    abstract class KvsStock(host: String, label: String) : TubeSite(host, label) {
        private val base = "https://www.$host"
        private fun pg(page: Int) = if (page == 0) "" else "${page + 1}/"
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "$base/latest-updates/${pg(page)}"
            "top" -> "$base/top-rated/${pg(page)}"
            else -> "$base/search/${enc(section.lowercase().trim()).replace("+", "-")}/${pg(page)}"
        }
        private val esc = Regex.escape(host)
        private val MARK = Regex("""href="(https://www\.$esc/videos/(\d+)/[^"]*)"[^>]*?\svthumb="(https://[^"]+)"""")
        private val IMG = Regex("""data-original="(https://[^"]+\.jpg)"""")
        private val TITLE = Regex("""<strong class="title">\s*([^<]+?)\s*</strong>""")
        private val ALT = Regex("""\balt="([^"]+)"""")
        private val DUR = Regex("""class="duration">\s*([^<]+?)\s*<""")
        private val TAG = Regex("""href="https://www\.$esc/(?:tags|categories)/([a-z0-9-]+)/"""")

        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (_, c) ->
            val m = MARK.find(c) ?: return@mapNotNull null
            TubeVideo(
                id = m.groupValues[2],
                title = (ALT.find(c)?.groupValues?.get(1) ?: TITLE.find(c)?.groupValues?.get(1))?.let(::unescape)?.trim()?.removeSuffix("...")?.trim()?.ifEmpty { null },
                pageUrl = m.groupValues[1],
                thumb = IMG.find(c)?.groupValues?.get(1),
                durationSec = duration(DUR.find(c)?.groupValues?.get(1)?.replace(Regex("""(\d+)m:(\d+)s"""), "$1:$2")),
                preview = m.groupValues[3].trimEnd('/'),
            )
        }.distinctBy { it.id }

        override fun stream(pageHtml: String): TubeStream? = kvsBest(pageHtml)

        /** The video's own categories and tags (not the site menu further down). */
        override fun pageTags(pageHtml: String): List<String> = kvsOwnTags(pageHtml, TAG)

        override fun embedUrl(id: String) = "$base/embed/$id/"
    }

    /** Analdin: studio scenes, KVS stock theme, with a scrub-preview timeline. */
    object Analdin : KvsStock("analdin.com", "Analdin")

    /**
     * One family of tubes (KatesTube, PornWhite, SleazyNeasy, VikiPorn): listing with length, date,
     * uploader and preview clip; KVS files with their quality, and the video's own categories.
     */
    abstract class MjNet(host: String, label: String) : TubeSite(host, label) {
        private val base = "https://www.$host"
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "$base/latest-updates/${if (page == 0) "" else "${page + 1}/"}"
            "top" -> "$base/top-rated/${if (page == 0) "" else "${page + 1}/"}"
            else -> "$base/search/?q=${enc(section)}" + if (page > 0) "&from_videos=${page + 1}" else ""
        }
        private val esc = Regex.escape(host)
        private val MARK = Regex("""<a (?:class="ponn" )?href="(https://www\.$esc/videos/(\d+)/[^"]*)" itemprop="url">""")
        private val CLIP = Regex("""data-src="(https://[^"]+_preview\.mp4)"""")
        private val POSTER = Regex("""data-poster="(https://[^"]+)"""")
        private val NAME = Regex("""<meta itemprop="name" content="([^"]*)"""")
        private val DATE = Regex("""<meta itemprop="uploadDate" content="([^"]+)"""")
        private val LEN = Regex("""<span class="length">\s*(?:<i>)?\s*([\d:]+)""")
        private val BY = Regex("""thumb-submitter">\s*<a [^>]*>\s*([^<]+?)\s*</a>""")
        private val QUALITY = Regex("""btn-quality">\s*(\d{3,4})p""")
        private val TAG = Regex("""href="https://www\.$esc/(?:tags|categories)/([a-z0-9-]+)/"""")

        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList().distinctBy { it.groupValues[2] }
            return hits.mapIndexed { i, m ->
                val c = body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 6000))
                TubeVideo(
                    id = m.groupValues[2],
                    title = NAME.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifEmpty { null },
                    pageUrl = m.groupValues[1],
                    thumb = POSTER.find(c)?.groupValues?.get(1),
                    durationSec = duration(LEN.find(c)?.groupValues?.get(1)),
                    published = DATE.find(c)?.groupValues?.get(1)?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() },
                    creator = BY.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifEmpty { null },
                    preview = CLIP.find(c)?.groupValues?.get(1),
                    height = QUALITY.find(c)?.groupValues?.get(1)?.toIntOrNull(),
                )
            }
        }

        override fun stream(pageHtml: String): TubeStream? = kvsBest(pageHtml)
        override fun pageTags(pageHtml: String): List<String> = kvsOwnTags(pageHtml, TAG).ifEmpty { kvsPlayerTags(pageHtml) }
        override fun embedUrl(id: String) = "$base/embed/$id"
    }

    /** KatesTube: amateur and studio uploads. */
    object KatesTube : MjNet("katestube.com", "KatesTube")
    object PornWhite : MjNet("pornwhite.com", "PornWhite")
    object SleazyNeasy : MjNet("sleazyneasy.com", "SleazyNeasy")
    object VikiPorn : MjNet("vikiporn.com", "VikiPorn")
    /** Fetish Shrine: fetish and kink, from BDSM to feet. */
    object FetishShrine : MjNet("fetishshrine.com", "Fetish Shrine")

    /** Pornicom: studio scenes and amateur uploads, up to 1080p, with preview clips. */
    object Pornicom : TubeSite("pornicom.com", "Pornicom") {
        private const val BASE = "https://www.pornicom.com"
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "$BASE/latest-updates/${if (page == 0) "" else "${page + 1}/"}"
            "top" -> "$BASE/top-rated/${if (page == 0) "" else "${page + 1}/"}"
            else -> "$BASE/search/?q=${enc(section)}" + if (page > 0) "&from_videos=${page + 1}" else ""
        }
        private val MARK = Regex("""<a href="(https://www\.pornicom\.com/videos/(\d+)/[^"]*)" class="thumb"""")
        private val POSTER = Regex("""data-poster="(https://[^"]+)"""")
        private val CLIP = Regex("""data-src="(https://[^"]+_preview\.mp4/?)"""")
        private val ALT = Regex("""<img class="thumb" [^>]*?alt="([^"]*)"""")
        private val LEN = Regex("""class="time">\s*([\d:]+)""")
        private val DATE = Regex("""itemprop="datePublished" content="(\d{4}-\d\d-\d\d)"""")
        private val BY = Regex("""thumb-submitter">\s*<a [^>]*>\s*([^<]+?)\s*</a>""")
        private val TAG = Regex("""href="/categories/([a-z0-9-]+)/"""")

        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList().distinctBy { it.groupValues[2] }
            return hits.mapIndexed { i, m ->
                val c = body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 6000))
                TubeVideo(
                    id = m.groupValues[2],
                    // Titles are uploaded in lower case: a capital to start reads better.
                    title = ALT.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifEmpty { null }
                        ?.let { t -> if (t == t.lowercase()) t.replaceFirstChar { it.uppercase() } else t },
                    pageUrl = m.groupValues[1],
                    thumb = POSTER.find(c)?.groupValues?.get(1),
                    durationSec = duration(LEN.find(c)?.groupValues?.get(1)),
                    published = DATE.find(c)?.groupValues?.get(1)?.let { runCatching { java.time.LocalDate.parse(it).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull() },
                    creator = BY.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifEmpty { null },
                    preview = CLIP.find(c)?.groupValues?.get(1),
                )
            }
        }

        override fun stream(pageHtml: String): TubeStream? = kvsBest(pageHtml)
        override fun pageTags(pageHtml: String): List<String> = kvsOwnTags(pageHtml, TAG).ifEmpty { kvsPlayerTags(pageHtml) }
        override fun embedUrl(id: String) = "$BASE/embed/$id"
    }

    /**
     * One network of tube sites (TXXX, HClips, Upornia…) behind the same JSON API: listings and
     * search come as JSON with title, length, date, categories, thumbnail and preview clip; the file
     * comes from videofile.php, its path written in "base164" (base64 with a few letters swapped for
     * look-alike Cyrillic ones, "." for "+", "," for "/" and "~" for "=").
     */
    abstract class TxxxNet(host: String, label: String) : TubeSite(host, label) {
        private fun api(sort: String, page: Int) = "https://$host/api/json/videos2/86400/str/$sort/60/..${page + 1}.all...json"
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> api("latest-updates", page)
            "top" -> api("most-popular", page)
            else -> "https://$host/api/videos2.php?params=86400/str/relevance/60/search..${page + 1}.all..&s=${enc(section)}"
        }

        override val checkTagsFirst = false

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        override fun parseList(body: String): List<TubeVideo> {
            val videos = runCatching { json.parseToJsonElement(body).jsonObject["videos"] as? JsonArray }.getOrNull() ?: return emptyList()
            return videos.mapNotNull { it as? JsonObject }.mapNotNull { v ->
                val id = v.str("video_id") ?: return@mapNotNull null
                val dir = v.str("dir") ?: return@mapNotNull null
                if (v.str("is_private") == "1") return@mapNotNull null
                TubeVideo(
                    id = id,
                    // Many uploads are titled all in lower case: a capital to start reads better.
                    title = v.str("title")?.let(::unescape)?.trim()?.ifEmpty { null }
                        ?.let { t -> if (t == t.lowercase()) t.replaceFirstChar { it.uppercase() } else t },
                    pageUrl = "https://$host/videos/$id/$dir/",
                    thumb = v.str("scr"),
                    durationSec = duration(v.str("duration")),
                    tags = cleanTags(v.str("categories")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()).take(MAX_TAGS),
                    published = v.str("post_date")?.let { runCatching { java.time.LocalDateTime.parse(it.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC) }.getOrNull() },
                    creator = creator(v),
                    performers = models(v),
                    preview = v.str("pv")?.let { if (it.startsWith("http")) it else "https://$it" },
                    height = v.str("file_dimensions")?.substringAfter('x')?.toIntOrNull()
                        ?: (v["props"] as? JsonObject)?.str("hd")?.takeIf { it == "1" }?.let { 720 },
                )
            }
        }

        /**
         * The studio when the upload says which, else the uploader. Many uploads come from the
         * network's own accounts ("_psevdo_…") under a made-up person's name: no creator then,
         * rather than a name that would pass for a performer's.
         */
        private fun creator(v: JsonObject): String? {
            v.str("content_source_name")?.let(::unescape)?.trim()?.ifEmpty { null }?.let { return it }
            if (v.str("username")?.startsWith("_psevdo_") == true) return null
            return v.str("display_name") ?: v.str("username")
        }

        /** Credited models: "Name One,Name Two", or "slug|id|Name|count|picture" entries. */
        private fun models(v: JsonObject): List<String> = modelNames(v.str("models_data") ?: v.str("models"))

        override fun relatedUrl(id: String): String? = id.toLongOrNull()?.let { n ->
            "https://$host/api/json/videos_related2/432000/50/${n / 1_000_000 * 1_000_000}/${n / 1000 * 1000}/$id.all.1.json"
        }

        /** The file list, not the (script-built) video page. */
        override fun streamPageUrl(pageUrl: String, id: String) = "https://$host/api/videofile.php?video_id=$id&lifetime=8640000"

        override fun stream(pageHtml: String): TubeStream? {
            val files = runCatching { json.parseToJsonElement(pageHtml).jsonArray }.getOrNull() ?: return null
            val options = files.mapNotNull { it as? JsonObject }.mapNotNull { f ->
                val path = f.str("video_url")?.let(::base164)?.takeIf { it.startsWith("/") || it.startsWith("http") } ?: return@mapNotNull null
                (FORMAT_HEIGHT[f.str("format")] ?: 480) to (if (path.startsWith("http")) path else "https://$host$path")
            }
            // The best up to the quality setting; if every file is above it, the smallest there is.
            return (options.filter { it.first <= maxHeight }.maxByOrNull { it.first } ?: options.minByOrNull { it.first })
                ?.let { TubeStream(it.second, "video/mp4") }
        }

        override fun embedUrl(id: String) = "https://$host/embed/$id"

        companion object {
            private const val MAX_TAGS = 12
            private val FORMAT_HEIGHT = mapOf("_4k.mp4" to 2160, "_fhd.mp4" to 1080, "_hq.mp4" to 720, ".mp4" to 480, "_sd.mp4" to 480, "_lq.mp4" to 240)
            private val LOOKALIKES = mapOf('А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'М' to 'M', 'Н' to 'H', 'О' to 'O', 'Р' to 'P', 'Т' to 'T', 'Х' to 'X', 'К' to 'K', '.' to '+', ',' to '/', '~' to '=')

            /**
             * Uploaders here pile on nationalities for traffic ("Bangladeshi, Estonian, Greek, Swiss…"
             * on one video). Three or more of them on a video say nothing about it: they're dropped,
             * so your taste and the searches made for you aren't steered by them. One or two stay.
             */
            internal fun cleanTags(tags: List<String>): List<String> =
                if (tags.count { it in NATIONALITIES } >= 3) tags.filter { it !in NATIONALITIES } else tags

            private val NATIONALITIES = setOf(
                "american", "argentinian", "australian", "austrian", "bangladeshi", "belgian", "brazilian", "british", "bulgarian",
                "canadian", "chilean", "chinese", "colombian", "croatian", "cuban", "czech", "danish", "dutch", "egyptian", "english",
                "estonian", "filipina", "filipino", "finnish", "french", "german", "greek", "hungarian", "indian", "indonesian",
                "iranian", "irish", "israeli", "italian", "jamaican", "japanese", "korean", "latvian", "lithuanian", "malaysian",
                "mexican", "moroccan", "nepali", "norwegian", "pakistani", "persian", "peruvian", "polish", "portuguese", "romanian",
                "russian", "scottish", "serbian", "slovak", "slovenian", "spanish", "swedish", "swiss", "thai", "turkish",
                "ukrainian", "venezuelan", "vietnamese", "welsh",
            )

            internal fun modelNames(raw: String?): List<String> {
                val r = raw?.trim().orEmpty()
                if (r.isEmpty()) return emptyList()
                val names = if ('|' in r) PIPED.findAll(r).map { it.groupValues[1] }.toList() else r.split(',', ';')
                return names.map { unescape(it).trim() }.filter { it.length in 3..40 }.distinct().take(6)
            }
            private val PIPED = Regex("""(?:^|[,;])\s*[^|,;]+\|\d+\|([^|]+)\|""")

            /** "base164" → the text it stands for, or null if it isn't that. */
            internal fun base164(e: String): String? = runCatching {
                String(java.util.Base64.getDecoder().decode(e.map { LOOKALIKES[it] ?: it }.joinToString("")), Charsets.UTF_8)
            }.getOrNull()
        }
    }

    /** TXXX: the network's biggest catalogue (millions of uploads). */
    object Txxx : TxxxNet("txxx.com", "TXXX")
    object HClips : TxxxNet("hclips.com", "HClips")
    object Upornia : TxxxNet("upornia.com", "Upornia")
    /** TubePornClassic: vintage and classic films. */
    object TubePornClassic : TxxxNet("tubepornclassic.com", "TubePornClassic")
    object Vxxx : TxxxNet("vxxx.com", "VXXX")
    /** HDZog: HD uploads. */
    object HdZog : TxxxNet("hdzog.com", "HDZog")
    object ManySex : TxxxNet("manysex.com", "ManySex")
    object HotMovs : TxxxNet("hotmovs.com", "HotMovs")
    object InPorn : TxxxNet("inporn.com", "InPorn")
    /** TheGay: gay scenes (off to start). */
    object TheGay : TxxxNet("thegay.com", "TheGay") { override val startsOff = true }
    /** Shemalez: trans scenes (off to start). */
    object Shemalez : TxxxNet("shemalez.com", "Shemalez") { override val startsOff = true }

    /** AlotPorn: full-length scenes (often 30 min and more), with preview clips; SD/HD MP4s and genres on the video page. */
    object AlotPorn : TubeSite("alotporn.com", "AlotPorn") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.alotporn.com/latest-updates/${if (page == 0) "" else "${page + 1}/"}"
            "top" -> "https://www.alotporn.com/top-rated/${if (page == 0) "" else "${page + 1}/"}"
            else -> "https://www.alotporn.com/search/?q=${enc(section)}" + if (page > 0) "&from_videos=${page + 1}" else ""
        }
        private val MARK = Regex("""<a href="(https://www\.alotporn\.com/video/(\d+)/[^"]*)" title="([^"]*)"""")
        private val IMG = Regex("""<img [^>]*\bsrc="(https://[^"]+\.jpg)"""")
        private val CLIP = Regex("""data-preview="(https://[^"]+\.mp4/?)"""")
        private val DUR = Regex("""<div class="time">\s*([\d:]+)\s*</div>""")
        private val SOURCE = Regex("""<source src='(https://[^']+_(\d{3,4})p\.mp4)'""")
        private val GENRE = Regex(""""genre":\s*\[([^\]]*)\]""")
        private val QUOTED = Regex(""""([^"]+)"""")

        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList().distinctBy { it.groupValues[2] }
            return hits.mapIndexed { i, m ->
                val c = body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 4000))
                TubeVideo(
                    id = m.groupValues[2],
                    title = unescape(m.groupValues[3]).trim().ifEmpty { null },
                    pageUrl = m.groupValues[1],
                    thumb = IMG.find(c)?.groupValues?.get(1),
                    durationSec = duration(DUR.find(c)?.groupValues?.get(1)),
                    preview = CLIP.find(c)?.groupValues?.get(1),
                )
            }
        }

        override fun stream(pageHtml: String): TubeStream? {
            val options = SOURCE.findAll(pageHtml).map { it.groupValues[2].toInt() to it.groupValues[1] }.toList()
            return (options.filter { it.first <= maxHeight }.maxByOrNull { it.first } ?: options.minByOrNull { it.first })
                ?.let { TubeStream(it.second, "video/mp4") }
        }

        override fun pageTags(pageHtml: String): List<String> =
            GENRE.find(pageHtml)?.groupValues?.get(1)?.let { QUOTED.findAll(it).map { g -> unescape(g.groupValues[1]).lowercase().replace("+18", "").trim() }.filter { t -> t.isNotEmpty() }.toList() }.orEmpty()

        override fun embedUrl(id: String) = "https://www.alotporn.com/embed/$id"
    }

    /**
     * KVS's default theme (FreePorn8 and the many sites like it): each tile is a titled link with the
     * thumbnail, a preview clip (data-preview), the length and title; KVS files and categories on the video page.
     */
    abstract class KvsDefault(host: String, label: String, private val www: Boolean = true) : TubeSite(host, label) {
        private val base = if (www) "https://www.$host" else "https://$host"
        private fun pg(page: Int) = if (page == 0) "" else "${page + 1}/"
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "$base/latest-updates/${pg(page)}"
            "top" -> "$base/top-rated/${pg(page)}"
            else -> "$base/search/${enc(section.lowercase().trim()).replace("+", "-")}/${pg(page)}"
        }
        private val esc = Regex.escape(host)
        private val MARK = Regex("""<a href="(https://(?:www\.)?$esc/videos/(\d+)/[^"]*)" title="([^"]*)"""")
        private val IMG = Regex("""<img [^>]*?(?:data-original|src)="(https://[^"]+\.jpg)"""")
        private val CLIP = Regex("""data-preview="(https://[^"]+\.mp4/?)"""")
        private val DUR = Regex("""class="duration">\s*([\d:]+)""")
        private val TAG = Regex("""href="https://(?:www\.)?$esc/(?:tags|categories)/([a-z0-9-]+)/"""")

        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = MARK.findAll(body).toList().distinctBy { it.groupValues[2] }
            return hits.mapIndexed { i, m ->
                val c = body.substring(m.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, m.range.first + 4000))
                TubeVideo(
                    id = m.groupValues[2],
                    title = unescape(m.groupValues[3]).trim().ifEmpty { null },
                    pageUrl = m.groupValues[1],
                    thumb = IMG.find(c)?.groupValues?.get(1),
                    durationSec = duration(DUR.find(c)?.groupValues?.get(1)),
                    preview = CLIP.find(c)?.groupValues?.get(1),
                )
            }
        }

        override fun stream(pageHtml: String): TubeStream? = kvsBest(pageHtml)
        override fun pageTags(pageHtml: String): List<String> = kvsOwnTags(pageHtml, TAG)
        override fun embedUrl(id: String) = "$base/embed/$id"
    }

    object FreePorn8 : KvsDefault("freeporn8.com", "FreePorn8")
    object BigTitsLust : KvsDefault("bigtitslust.com", "BigTitsLust")

    /**
     * DrTuber, Nuvid and IcePorn: one company's tubes. Their mobile listings carry title, length,
     * thumbnail and a preview clip; the files come from the player's own JSON (low and high quality).
     */
    abstract class DrNet(host: String, label: String) : TubeSite(host, label) {
        private val m = "https://m.$host"
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "$m/videos" + if (page == 0) "" else "/${page + 1}"
            // No separate top list on the mobile site: a few pages further in, so it isn't the same videos.
            "top" -> "$m/videos/${page + 4}"
            else -> "$m/search/videos/${enc(section.lowercase().trim())}" + if (page == 0) "" else "/${page + 1}"
        }
        private val HREF = Regex("""href="/video/(\d+)/([^"]+)"""")
        private val IMG = Regex("""<img [^>]*\bsrc="(https://[^"]+/320_240/\d+\.jpg)"""")
        private val ALT = Regex("""\balt="([^"]*)"""")
        private val WEBM = Regex("""data-webm="(https://[^"]+\.mp4)"""")
        private val HD_MARK = Regex("""class="hd[^"]*">\s*HD\s*<""")
        private val DUR = Regex(""">\s*(\d{1,2}:\d{2}(?::\d{2})?)\s*<""")

        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> {
            val hits = HREF.findAll(body).toList().distinctBy { it.groupValues[1] }
            return hits.mapIndexed { i, h ->
                val c = body.substring(h.range.first, if (i + 1 < hits.size) hits[i + 1].range.first else minOf(body.length, h.range.first + 3000))
                val img = IMG.find(c)
                TubeVideo(
                    id = h.groupValues[1],
                    title = img?.let { ALT.find(c.substring(it.range.first))?.groupValues?.get(1) }?.let(::unescape)?.trim()?.ifEmpty { null },
                    pageUrl = "https://www.$host/video/${h.groupValues[1]}/${h.groupValues[2]}",
                    thumb = img?.groupValues?.get(1),
                    durationSec = duration(img?.let { DUR.find(c, it.range.last)?.groupValues?.get(1) }),
                    preview = WEBM.find(c)?.groupValues?.get(1),
                    height = if (HD_MARK.containsMatchIn(c)) 720 else null,
                )
            }.filter { it.thumb != null }
        }

        private val VID = Regex("""/video/(\d+)/""")

        /** The player's JSON holds the files; it answers only with the video's page as the referrer. */
        override fun streamIndexUrl(pageHtml: String): String? =
            (CANONICAL.find(pageHtml) ?: VIDEO_ID.find(pageHtml) ?: VID.find(pageHtml))?.groupValues?.get(1)?.let { configUrl(it) }
        private val CANONICAL = Regex("""rel="canonical" href="https://www\.[^/]+/video/(\d+)/""")
        private val VIDEO_ID = Regex("""video_id["':= ]+(\d+)""")

        internal fun configUrl(id: String) = "https://www.$host/player_config_json/?vid=$id&aid=0&domain_id=0&embed=0&ref=null&check_speed=0"

        override fun stream(pageHtml: String): TubeStream? = null

        override fun streamFromIndex(indexJson: JsonElement): TubeStream? = files(indexJson)

        internal fun files(config: JsonElement): TubeStream? {
            val files = runCatching { config.jsonObject["files"] as? JsonObject }.getOrNull() ?: return null
            val options = listOf("4k" to 2160, "hq" to 720, "lq" to 360).mapNotNull { (k, h) ->
                (files[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }?.let { h to it }
            }
            return (options.filter { it.first <= maxHeight }.maxByOrNull { it.first } ?: options.minByOrNull { it.first })
                ?.let { TubeStream(it.second, "video/mp4") }
        }

        override fun embedUrl(id: String) = "https://www.$host/embed/$id"

        private val CATEGORY = Regex("""href="/categories/([a-z0-9-]+)"""")
        /** Single-word tags only: the rest are pairs of the same words ("amateur-bbc"). */
        private val ONE_WORD_TAG = Regex("""href="/tags/([a-z0-9]+)"""")

        /** The video's categories and its one-word tags (DrTuber names them on the video page). */
        override fun pageTags(pageHtml: String): List<String> =
            (CATEGORY.findAll(pageHtml).map { it.groupValues[1].replace('-', ' ') } + ONE_WORD_TAG.findAll(pageHtml).map { it.groupValues[1] })
                .distinct().take(20).toList()
    }

    object DrTuber : DrNet("drtuber.com", "DrTuber")
    object Nuvid : DrNet("nuvid.com", "Nuvid")
    object IcePorn : DrNet("iceporn.com", "IcePorn")

    /** XFreeHD: amateur and studio uploads; listing with title, length and thumbnail, tags and SD/HD MP4s on the video page. */
    object XFreeHd : TubeSite("xfreehd.com", "XFreeHD") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new", "top" -> if (page == 0) "https://beta.xfreehd.com/" else "https://beta.xfreehd.com/page=${page + 1}"
            else -> "https://beta.xfreehd.com/tag/${enc(section.lowercase())}" + if (page > 0) "/page=${page + 1}" else ""
        }
        private val MARK = Regex("""<a class="video-link" href="(https://beta\.xfreehd\.com/video/(\d+)/[^"]+)"""")
        private val IMG = Regex("""data-src="(https://image\.xfreehd\.com/[^"]+\.jpg)"""")
        private val DUR = Regex("""duration-new">\s*([0-9hms: ]+?)\s*</div>""")
        private val TITLE = Regex("""video-title-new[^"]*">([^<]+)<""")
        private val SOURCE = Regex("""<source src="(https://[^"]+\.mp4)" title="(SD|HD)"""")
        private val TAG = Regex("""href="https://beta\.xfreehd\.com/tag/([^"]+)"""")

        // Pages are big (300–500 KB): tags are read when a video is played, not before listing it.
        override val checkTagsFirst = false

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (_, c) ->
            val m = MARK.find(c) ?: return@mapNotNull null
            TubeVideo(
                id = m.groupValues[2],
                title = TITLE.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim()?.trimEnd('-', ' ')?.ifEmpty { null },
                pageUrl = m.groupValues[1],
                thumb = IMG.find(c)?.groupValues?.get(1),
                durationSec = duration(DUR.find(c)?.groupValues?.get(1)),
            )
        }.distinctBy { it.id }

        override fun stream(pageHtml: String): TubeStream? {
            val all = SOURCE.findAll(pageHtml).map { it.groupValues[2] to it.groupValues[1] }.toList()
            // HD is 720p or more; with Data saver on, SD.
            val pick = (if (maxHeight >= 720) all.firstOrNull { it.first == "HD" } else null) ?: all.firstOrNull { it.first == "SD" } ?: all.firstOrNull()
            return pick?.let { TubeStream(it.second, "video/mp4") }
        }
        override fun pageTags(pageHtml: String): List<String> =
            TAG.findAll(pageHtml).map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8").lowercase() }
                .filterNot { it == "hd" || it == "hot" || it == "sexy" || it == "sex" }.distinct().take(30).toList()
        override fun embedUrl(id: String) = "https://beta.xfreehd.com/embed/$id"
    }

    object Youjizz : TubeSite("youjizz.com", "YouJizz") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.youjizz.com/newest-clips/${page + 1}.html"
            "top" -> "https://www.youjizz.com/top-rated-week/${page + 1}.html"
            else -> "https://www.youjizz.com/search/${enc(section).replace("+", "-")}-${page + 1}.html"
        }
        private val MARK = Regex("""<a data-video-id="(\d+)"""")
        private val HREF = Regex("""href=['"](/videos/[^'"]+\.html)['"]""")
        private val IMG = Regex("""data-original="(//[^"]+)"""")
        private val TITLE = Regex("""agego-content-title">([^<]+)<""")
        private val DUR = Regex("""class="time">.*?([\d:]{4,})""", RegexOption.DOT_MATCHES_ALL)

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (id, c) ->
            val href = HREF.find(c)?.groupValues?.get(1) ?: return@mapNotNull null
            TubeVideo(id, TITLE.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim(), "https://www.youjizz.com$href", IMG.find(c)?.groupValues?.get(1)?.let { "https:" + unescape(it) }, duration(DUR.find(c)?.groupValues?.get(1)))
        }.distinctBy { it.id }

        override fun stream(pageHtml: String): TubeStream? {
            val arr = jsonAfter(pageHtml, "dataEncodings")?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return null
            return best(arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
                val f = o["filename"]?.jsonPrimitive?.contentOrNull?.let(::unescape)?.takeIf { !it.contains(".m3u8") } ?: return@mapNotNull null
                val q = o["quality"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                q to (if (f.startsWith("//")) "https:$f" else f)
            })
        }
        override fun embedUrl(id: String) = "https://www.youjizz.com/videos/embed/$id"
    }

    /**
     * Erome: amateur albums (often one person's set of clips). Only albums with videos are listed;
     * each plays its first clip. Files need the site as Referer (sent by the browser headers).
     */
    object Erome : TubeSite("erome.com", "Erome") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.erome.com/explore/new?page=${page + 1}"
            "top" -> "https://www.erome.com/explore?page=${page + 1}"
            else -> "https://www.erome.com/search?q=${enc(section)}&page=${page + 1}"
        }
        private val MARK = Regex("""id="album-(\d+)"""")
        private val HREF = Regex("""href="(https://www\.erome\.com/a/([A-Za-z0-9]+))"""")
        private val IMG = Regex("""class="album-thumbnail[^"]*"\s+src="(https://[^"]+)"""")
        private val TITLE = Regex("""class="album-title"[^>]*>([^<]*)<""")
        private val SOURCE = Regex("""<source src="(https://[^"]+\.mp4)"[^>]*?(?:res=["'](\d+)["'])?""")

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (_, c) ->
            if (!c.contains("album-videos")) return@mapNotNull null // pictures only
            val m = HREF.find(c) ?: return@mapNotNull null
            TubeVideo(m.groupValues[2], TITLE.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifBlank { null }, m.groupValues[1], IMG.find(c)?.groupValues?.get(1)?.let(::unescape), null)
        }.distinctBy { it.id }

        override fun stream(pageHtml: String): TubeStream? =
            SOURCE.find(pageHtml)?.groupValues?.get(1)?.let { TubeStream(unescape(it), "video/mp4") }
        override fun embedUrl(id: String) = "https://www.erome.com/a/$id"
    }

    /** Rule34Video: hentai, 3D and animated porn (KVS engine, like Porntrex). Tags checked first. */
    object Rule34Video : TubeSite("rule34video.com", "Rule34Video") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://rule34video.com/latest-updates/${page + 1}/"
            "top" -> "https://rule34video.com/most-popular/${page + 1}/"
            else -> "https://rule34video.com/search/${enc(section).replace("+", "-")}/?from_videos=${page + 1}"
        }
        private val MARK = Regex("""data-video-card-id="(\d+)"""")
        private val HREF = Regex("""href="(https://rule34video\.com/video/\d+/[^"]*)"[^>]*\btitle="([^"]*)"""")
        private val IMG = Regex("""<img class="thumb[^"]*"[^>]*\bsrc="(https://[^"]+)"""")
        private val DUR = Regex("""class="time"[^>]*>\s*([\d:]+)""")
        private val URLS = Regex("""\b(video(?:_alt)?_url\d*):\s*'(https://[^']+)'""")
        private val FLASH_LIST = Regex("""\bvideo_(?:tags|categories):\s*'([^']*)'""")

        override val checkTagsFirst = true

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (id, c) ->
            val m = HREF.find(c) ?: return@mapNotNull null
            TubeVideo(id, unescape(m.groupValues[2]).trim(), m.groupValues[1], IMG.find(c)?.groupValues?.get(1)?.let(::unescape), duration(DUR.find(c)?.groupValues?.get(1)))
        }.distinctBy { it.id }

        override fun pageTags(pageHtml: String): List<String> =
            FLASH_LIST.findAll(pageHtml).flatMap { it.groupValues[1].split(',') }.map { it.trim().replace(' ', '_') }.filter { it.isNotEmpty() }.distinct().toList()

        override fun stream(pageHtml: String): TubeStream? = best(URLS.findAll(pageHtml).mapNotNull { m ->
            Regex("""\b${m.groupValues[1]}_text:\s*'(\d+)p""").find(pageHtml)?.groupValues?.get(1)?.toIntOrNull()?.let { it to m.groupValues[2] }
        }.toList())
        override fun embedUrl(id: String) = "https://rule34video.com/embed/$id"
    }

    /** ThisVid: amateur and home-made (KVS engine; its files carry a short-lived access token, fetched per play). */
    object ThisVid : TubeSite("thisvid.com", "ThisVid") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://thisvid.com/latest-updates/${page + 1}/"
            "top" -> "https://thisvid.com/most-popular/${page + 1}/"
            else -> "https://thisvid.com/search/${page + 1}/?q=${enc(section)}"
        }
        private val MARK = Regex("""<a\b[^>]*\bhref="https://thisvid\.com/videos/([^/"]+)/"""")
        private val TITLE = Regex("""\btitle="([^"]+)"""")
        private val TITLE_SPAN = Regex("""class="title"[^>]*>([^<]+)<""")
        private val IMG = Regex("""(?:data-original|data-src|src)="((?:https:)?//[^"]+videos_screenshots[^"]+\.jpg)"""")
        private val DUR = Regex("""class="duration"[^>]*>\s*([\d:]+)""")
        private val URLS = Regex("""\b(video(?:_alt)?_url\d*):\s*'(https://[^']+)'""")
        private val FLASH_LIST = Regex("""\bvideo_(?:tags|categories):\s*'([^']*)'""")

        override val checkTagsFirst = true

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (slug, c) ->
            // Private videos (friends only) can't be played: their tiles say so.
            if (c.substringBefore("</a>").contains("private", ignoreCase = true)) return@mapNotNull null
            val title = (TITLE.find(c.substringBefore('>'))?.groupValues?.get(1) ?: TITLE_SPAN.find(c)?.groupValues?.get(1))?.let(::unescape)?.trim()
            val thumb = IMG.find(c)?.groupValues?.get(1)?.let { if (it.startsWith("//")) "https:$it" else it }
            TubeVideo(slug, title, "https://thisvid.com/videos/$slug/", thumb, duration(DUR.find(c)?.groupValues?.get(1)), height = if (c.contains("""<span class="quality">HD</span>""")) 720 else null)
        }.distinctBy { it.id }

        override fun pageTags(pageHtml: String): List<String> =
            FLASH_LIST.findAll(pageHtml).flatMap { it.groupValues[1].split(',') }.map { it.trim().lowercase().replace(' ', '_') }.filter { it.isNotEmpty() }.distinct().toList()

        override fun stream(pageHtml: String): TubeStream? {
            val found = URLS.findAll(pageHtml).map { m ->
                val q = Regex("""\b${m.groupValues[1]}_text:\s*'(\d+)p""").find(pageHtml)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""_(\d{3,4})p\.mp4""").find(m.groupValues[2])?.groupValues?.get(1)?.toIntOrNull()
                    ?: 480 // the unlabelled main file
                q to m.groupValues[2]
            }.toList()
            return best(found)
        }
        override fun embedUrl(id: String) = "https://thisvid.com/embed/$id/"
    }

    /** PornOne (formerly vPorn): long full scenes, MP4s up to 1080p right in the page. */
    object PornOne : TubeSite("pornone.com", "PornOne") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://pornone.com/newest/${if (page > 0) "${page + 1}/" else ""}"
            // No public "top" listing: popular sections use the newest page too.
            "top" -> "https://pornone.com/newest/${if (page > 0) "${page + 1}/" else ""}"
            else -> "https://pornone.com/search?q=${enc(section)}${if (page > 0) "&page=${page + 1}" else ""}"
        }
        private val MARK = Regex("""<a href="https://pornone\.com/[^/"]+/[^/"]+/(\d+)/[^"]*"[^>]*\bvideocard""")
        private val HREF = Regex("""href="(https://pornone\.com/[^/"]+/([^/"]+)/\d+/)""")
        private val IMG = Regex("""(https://th[^"\s]*\.pornone\.com/t/[^"\s]+\.jpg)""")
        private val DUR = Regex("""durlabel"[^>]*>(?:\s*<img[^>]*>)?\s*([\d:]{4,})""")
        private val MP4 = Regex("""(https://[^"'\s]+\.pornone\.com/[^"'\s]+_(\d+)x(\d+)_\d+k\.mp4[^"'\s]*)""")

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (id, c) ->
            val m = HREF.find(c) ?: return@mapNotNull null
            // The tile's text is mostly buttons ("Add to…"): the address's slug is the reliable title.
            val title = m.groupValues[2].replace('-', ' ').trim().replaceFirstChar { it.uppercase() }
            TubeVideo(id, title, m.groupValues[1], IMG.find(c)?.groupValues?.get(1), duration(DUR.find(c)?.groupValues?.get(1)))
        }.distinctBy { it.id }

        override fun stream(pageHtml: String): TubeStream? =
            best(MP4.findAll(pageHtml).mapNotNull { m -> m.groupValues[3].toIntOrNull()?.let { h -> h to unescape(m.groupValues[1]) } }.toList())
        override fun embedUrl(id: String) = "https://pornone.com/embed/$id/"
    }

    // ── Eporner (official API; plays its MP4s via the embed page's hash) ───

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

        // The video page is an age check; the embed page carries the player's id and hash, and the
        // player asks /xhr/video for its MP4s with that hash re-encoded (four 8-hex-digit chunks, each
        // in base 36). Checked live: returns 360p–1080p MP4 links.
        override fun streamPageUrl(pageUrl: String, id: String) = embedUrl(id)

        private val VID = Regex("""EP\.video\.player\.vid\s*=\s*'([^']+)'""")
        private val HASH = Regex("""EP\.video\.player\.hash\s*=\s*'([0-9a-f]{32})'""")

        internal fun encodeHash(hash: String): String =
            (0 until 32 step 8).joinToString("") { java.lang.Long.toString(hash.substring(it, it + 8).toLong(16), 36) }

        override fun streamIndexUrl(pageHtml: String): String? {
            val vid = VID.find(pageHtml)?.groupValues?.get(1) ?: return null
            val hash = HASH.find(pageHtml)?.groupValues?.get(1) ?: return null
            return "https://www.eporner.com/xhr/video/$vid?hash=${encodeHash(hash)}&domain=www.eporner.com&fallback=false&embed=true&supportedFormats=mp4"
        }

        override fun streamFromIndex(indexJson: JsonElement): TubeStream? {
            val mp4 = ((indexJson as? JsonObject)?.get("sources") as? JsonObject)?.get("mp4") as? JsonObject ?: return null
            return mp4.entries
                .mapNotNull { (label, v) ->
                    val src = ((v as? JsonObject)?.get("src") as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                    (Regex("""\d+""").find(label)?.value?.toIntOrNull() ?: 0) to src
                }
                // Up to 1080p: 4K files are huge for a phone feed.
                .filter { it.first <= maxHeight }
                .maxByOrNull { it.first }
                ?.let { TubeStream(it.second, "video/mp4") }
        }
    }
}

/** Feed items from a site's listing. */
internal fun TubeSite.items(videos: List<TubeVideo>, section: String, page: Int = 0): List<MediaItem> {
    // Listings rarely say when a video went up, but a "newest" page is newest-first: date it by its
    // place in the list, so Latest can show tube videos instead of sorting them to the very end.
    val now = java.time.Instant.now()
    return videos.mapIndexed { i, v ->
        val guessed = v.published == null && section == "new"
        item(v, section, v.published ?: if (guessed) now.minusSeconds(((page * 60L) + i) * 300L) else null)
            .let { if (guessed) it.copy(extras = it.extras + (MediaItem.APPROX_DATE to "1")) else it }
    }.also { dev.pfeed.domain.reco.CoStars.seen(it) }
}

private fun TubeSite.item(v: TubeVideo, section: String, published: java.time.Instant?): MediaItem =
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
        publishedAt = published,
        extras = buildMap { put("resolve", "tube"); put("vid", v.id); v.preview?.let { put(PREVIEW, it) }; v.height?.let { put(HEIGHT, it.toString()) }
            if (v.performers.isNotEmpty()) put(dev.pfeed.domain.reco.Mentions.PERFORMERS, v.performers.joinToString(",")) },
    )

/** In a tube post's extras: its site's short preview clip. */
const val PREVIEW = "preview"
/** Extras key: the video's best height as its listing gives it ("720", "1080"). */
const val HEIGHT = "height"
