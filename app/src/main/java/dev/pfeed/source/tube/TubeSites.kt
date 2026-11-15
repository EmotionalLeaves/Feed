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
    /**
     * Same site under other domains, tried in order when the main one answers with nothing usable
     * (xHamster sometimes serves a bot check instead of its listing; its mirrors don't at the same time).
     */
    open val mirrors: List<String> = emptyList()

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

    /** Tags (and categories) named on a video page, for [checkTagsFirst] sites. */
    open fun pageTags(pageHtml: String): List<String> = emptyList()
    open fun streamFromIndex(indexJson: JsonElement): TubeStream? = null

    companion object {
        // Lazy: the nested objects extend TubeSite, so building this eagerly during the companion's
        // initialisation re-enters the class initialiser and reads a null list.
        val ALL: List<TubeSite> by lazy { listOf(Xvideos, Xnxx, Pornhub, Youporn, Tube8, Xhamster, Redtube, Eporner, Tnaflix, Porntrex, Youjizz, Erome, Rule34Video) }
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
            val m = Regex("""(\d+)\s*min""").find(t)?.groupValues?.get(1)?.toInt()
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
                ?: initials(body)?.let(::findThumbProps)
            val fromJson = arr?.mapNotNull { it as? JsonObject }?.mapNotNull { v ->
                val url = v["pageURL"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                TubeVideo(
                    id = videoId(url),
                    title = v["title"]?.jsonPrimitive?.contentOrNull,
                    pageUrl = url,
                    thumb = v["thumbURL"]?.jsonPrimitive?.contentOrNull,
                    durationSec = (v["duration"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
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

    /** Best "quality → url" pick up to 1080p (4K is too heavy for a phone feed). */
    protected fun best(options: List<Pair<Int, String>>): TubeStream? =
        options.filter { it.first in 1..maxHeight && it.second.startsWith("http") }.maxByOrNull { it.first }?.let { TubeStream(it.second, "video/mp4") }

    object Tnaflix : TubeSite("tnaflix.com", "TNAFlix") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            // The very newest uploads are often still processing (their files answer 404): start a page in.
            "new" -> "https://www.tnaflix.com/new/${page + 2}"
            "top" -> "https://www.tnaflix.com/toprated/${page + 1}"
            else -> "https://www.tnaflix.com/search?what=${enc(section)}&page=${page + 1}"
        }
        private val MARK = Regex("""<div data-vid="(\d+)"""")
        private val HREF = Regex("""href="(https://www\.tnaflix\.com/[^"]+/video\d+)"""")
        private val IMG = Regex("""<img[^>]*\bsrc="(https://[^"]+\.jpg[^"]*)"""")
        private val ALT = Regex("""<img[^>]*\balt="([^"]*)"""")
        private val DUR = Regex("""video-duration">\s*([\d:]+)""")
        private val SOURCE = Regex("""<source src="([^"]+)" type="video/mp4" size="(\d+)"""")

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (id, c) ->
            val href = HREF.find(c)?.groupValues?.get(1) ?: return@mapNotNull null
            TubeVideo(id, ALT.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim(), href, IMG.find(c)?.groupValues?.get(1)?.let(::unescape), duration(DUR.find(c)?.groupValues?.get(1)))
        }.distinctBy { it.id }

        override fun stream(pageHtml: String): TubeStream? =
            best(SOURCE.findAll(pageHtml).map { it.groupValues[2].toInt() to unescape(it.groupValues[1]) }.toList())
        override fun embedUrl(id: String) = "https://player.tnaflix.com/video/$id"
    }

    object Porntrex : TubeSite("porntrex.com", "Porntrex") {
        override fun listUrl(section: String, page: Int): String = when (section) {
            "new" -> "https://www.porntrex.com/latest-updates/${page + 1}/"
            "top" -> "https://www.porntrex.com/top-rated/${page + 1}/"
            else -> "https://www.porntrex.com/search/${enc(section).replace("+", "-")}/${page + 1}/"
        }
        private val MARK = Regex("""data-item-id="(\d+)"""")
        private val HREF = Regex("""href="(https://www\.porntrex\.com/video/[^"]+)"""")
        private val IMG = Regex("""data-src="(//[^"]+\.jpg[^"]*)"""")
        private val ALT = Regex("""\balt="([^"]*)"""")
        private val DUR = Regex("""class="durations">(?:<i[^>]*></i>)?\s*([\d:]+)""")
        // KVS flashvars: video_url / video_alt_url / video_alt_url2… with matching *_text "720p HD"
        private val URLS = Regex("""\b(video(?:_alt)?_url\d*):\s*'(https://[^']+)'""")
        private fun textOf(html: String, key: String) =
            Regex("""\b${key}_text:\s*'(\d+)p""").find(html)?.groupValues?.get(1)?.toIntOrNull()

        override fun parseList(body: String): List<TubeVideo> = chunks(body, MARK).mapNotNull { (id, c) ->
            val href = HREF.find(c)?.groupValues?.get(1) ?: return@mapNotNull null
            TubeVideo(id, ALT.find(c)?.groupValues?.get(1)?.let(::unescape)?.trim(), href, IMG.find(c)?.groupValues?.get(1)?.let { "https:" + unescape(it) }, duration(DUR.find(c)?.groupValues?.get(1)))
        }.distinctBy { it.id }

        override fun stream(pageHtml: String): TubeStream? =
            best(URLS.findAll(pageHtml).mapNotNull { m -> textOf(pageHtml, m.groupValues[1])?.let { it to m.groupValues[2] } }.toList())
        override fun embedUrl(id: String) = "https://www.porntrex.com/embed/$id"
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
    return videos.mapIndexed { i, v -> item(v, section, v.published ?: if (section == "new") now.minusSeconds(((page * 60L) + i) * 300L) else null) }
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
        extras = mapOf("resolve" to "tube", "vid" to v.id),
    )
