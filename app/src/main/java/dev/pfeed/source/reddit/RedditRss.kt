package dev.pfeed.source.reddit

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.source.parse.MediaTypes
import dev.pfeed.source.parse.RedditParser

/**
 * Parses Reddit's Atom feeds (`/r/{sub}/{listing}/.rss`) into feed items. Pure, so it's unit-tested.
 *
 * What a post actually links to is in its `<content>` block — entity-escaped HTML ending in
 * `<a href="…">[link]</a>`. The `<media:thumbnail>` is only a ~140 px preview, so using it as the
 * media made every Reddit post a blurry still and every video post a single frame. Instead:
 *
 *  - `i.redd.it/x.jpg|png|webp` → full-size image; `.gif` → animated GIF
 *  - `v.redd.it/{id}` → Reddit's HLS stream (video + audio)
 *  - `redgifs.com/watch/{id}` → a clip resolved through the RedGIFs API when it's played
 *  - `i.imgur.com/x.gifv|.gif|.mp4` → the MP4; imgur stills → full-size image
 *  - any other direct `.mp4/.webm` or image link → as-is
 *  - otherwise a `preview.redd.it` / `external-preview.redd.it` image (≥ 320 px) if the post has one
 *
 * Posts with nothing better than the tiny thumbnail (galleries, link posts) are skipped.
 */
internal object RedditRss {

    data class Media(val type: MediaType, val url: String?, val mime: String? = null, val redgifsId: String? = null)

    fun parse(xml: String, sub: String): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        for (raw in xml.split("<entry>").drop(1)) {
            val entry = raw.substringBefore("</entry>")
            val postId = tag(entry, "id")?.trim() ?: continue
            val permalink = attr(entry, "link", "href")?.let(::decode) ?: ""
            val title = tag(entry, "title")?.let(::decode)?.trim()?.takeIf { it.length > 1 }
            val author = tag(entry, "name")?.let(::decode)?.trim()?.removePrefix("/u/")?.removePrefix("u/")?.takeIf { it.isNotBlank() }
            val published = MediaTypes.parseDate(tag(entry, "published") ?: tag(entry, "updated"))
            val thumbAttr = MEDIA_THUMB_RE.find(entry)?.groupValues?.get(1)?.let(::decode)
            // Search and multireddit feeds mix subreddits; each entry names its own in <category>.
            val entrySub = CATEGORY_RE.find(entry)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: sub

            // The content block is HTML escaped once inside the XML; decode it to read the links.
            val html = tag(entry, "content")?.let(::decode).orEmpty()
            val link = LINK_RE.find(html)?.groupValues?.get(1)?.let(::decode)
            val previewImg = IMG_RE.findAll(html).map { decode(it.groupValues[1]) }.firstOrNull { isPreview(it) }

            val media = link?.let(::mediaFor) ?: previewImg?.let { Media(MediaType.IMAGE, it) } ?: continue
            val thumb = previewImg ?: thumbAttr ?: media.url?.takeIf { media.type == MediaType.IMAGE }

            out += MediaItem(
                id = "reddit/" + postId.substringAfterLast('/').substringBefore('?'),
                sourceId = RedditParser.SOURCE_ID,
                type = media.type,
                title = title,
                creator = author,
                tags = listOf(entrySub),
                durationSec = null,
                thumbnailUrl = thumb,
                pageUrl = permalink,
                publishedAt = published,
                extras = buildMap {
                    media.url?.let { put("media", it) }
                    media.mime?.let { put("mime", it) }
                    media.redgifsId?.let { put("resolve", "redgifs"); put("gid", it) }
                },
            )
        }
        return out
    }

    /** Fullname (`t3_…`) of the last post in the feed: the `after` token for the next page. */
    fun lastId(xml: String): String? =
        xml.split("<entry>").drop(1).lastOrNull()?.substringBefore("</entry>")
            ?.let { tag(it, "id")?.trim() }
            ?.takeIf { it.startsWith("t3_") }

    /** The playable/viewable media behind a post's link, or null if it isn't one we can show. */
    fun mediaFor(rawUrl: String): Media? {
        val url = rawUrl.trim().replace("&amp;", "&")
        if (!url.startsWith("http")) return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase().removePrefix("www.")
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return when {
            host == "v.redd.it" -> path.substringBefore('/').takeIf { it.isNotBlank() }
                ?.let { Media(MediaType.VIDEO, "https://v.redd.it/$it/HLSPlaylist.m3u8", mime = HLS_MIME) }
            host.endsWith("redgifs.com") -> redgifsId(host, path)?.let { Media(MediaType.VIDEO, null, redgifsId = it) }
            host == "i.imgur.com" || host == "imgur.com" || host == "m.imgur.com" -> imgur(host, path, ext)
            host == "i.redd.it" || host.endsWith("redditmedia.com") || isPreview(url) -> byExtension(url, ext)
            else -> byExtension(url, ext)
        }
    }

    private fun byExtension(url: String, ext: String): Media? = when (ext) {
        "jpg", "jpeg", "png", "webp" -> Media(MediaType.IMAGE, url)
        "gif" -> Media(MediaType.GIF, url)
        "mp4", "webm", "m4v", "mov" -> Media(MediaType.VIDEO, url)
        "m3u8" -> Media(MediaType.VIDEO, url, mime = HLS_MIME)
        else -> null
    }

    private fun imgur(host: String, path: String, ext: String): Media? {
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        if (name.isBlank() || path.startsWith("a/") || path.startsWith("gallery/") || path.startsWith("t/")) return null
        return when {
            // .gifv is an MP4 page; imgur also serves every animated .gif as MP4, which plays far better.
            ext == "gifv" || ext == "gif" || ext == "mp4" || ext == "webm" -> Media(MediaType.VIDEO, "https://i.imgur.com/$name.mp4")
            ext in setOf("jpg", "jpeg", "png", "webp") -> Media(MediaType.IMAGE, "https://i.imgur.com/$name.$ext")
            ext.isEmpty() && host != "i.imgur.com" && !path.contains('/') -> Media(MediaType.IMAGE, "https://i.imgur.com/$name.jpg")
            else -> null
        }
    }

    /** redgifs.com/watch/{id}, /ifr/{id}, v3.redgifs.com/watch/{id}, i.redgifs.com/i/{id}.jpg */
    private fun redgifsId(host: String, path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        val id = when {
            host.startsWith("i.") && parts.size >= 2 && parts[0] == "i" -> parts[1].substringBefore('.')
            parts.size >= 2 && (parts[0] == "watch" || parts[0] == "ifr") -> parts[1]
            else -> null
        }
        return id?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]+")) }
    }

    private fun isPreview(url: String): Boolean {
        val h = url.substringAfter("://").substringBefore('/').lowercase()
        if (h != "preview.redd.it" && h != "external-preview.redd.it") return false
        // Reddit sizes previews with ?width=. Under 640 px they're blurry full-screen, and a blurry
        // post is worse than no post (checked live: gallery/link posts only offer 320 px here).
        val w = Regex("[?&]width=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
        return w == null || w >= MIN_PREVIEW_WIDTH
    }

    private fun tag(xml: String, name: String): String? {
        var from = 0
        while (true) {
            val start = xml.indexOf("<$name", from)
            if (start < 0) return null
            val next = xml.getOrNull(start + name.length + 1)
            // Exact element only: "<name>" or "<name attr…>", not "<named…>".
            if (next != '>' && next != ' ' && next != '\n' && next != '\t') { from = start + 1; continue }
            val open = xml.indexOf('>', start)
            if (open < 0) return null
            if (xml[open - 1] == '/') return "" // self-closing
            val close = xml.indexOf("</$name>", open)
            if (close < 0) return null
            return xml.substring(open + 1, close).let { s ->
                if (s.startsWith("<![CDATA[")) s.removePrefix("<![CDATA[").removeSuffix("]]>") else s
            }
        }
    }

    private fun attr(xml: String, name: String, attr: String): String? {
        val start = xml.indexOf("<$name ")
        if (start < 0) return null
        val end = xml.indexOf('>', start)
        if (end < 0) return null
        return Regex("""\b$attr="([^"]*)"""").find(xml.substring(start, end))?.groupValues?.get(1)
    }

    fun decode(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&#32;", " ")
        .replace("&amp;", "&")

    const val HLS_MIME = "application/x-mpegURL"
    private const val MIN_PREVIEW_WIDTH = 640

    private val MEDIA_THUMB_RE = Regex("""<media:thumbnail[^>]*\burl="([^"]+)"""")
    private val CATEGORY_RE = Regex("""<category[^>]*\bterm="([^"]+)"""")
    private val LINK_RE = Regex("""<a href="([^"]+)">\s*\[link]\s*</a>""")
    private val IMG_RE = Regex("""<img[^>]*\bsrc="([^"]+)"""", RegexOption.IGNORE_CASE)
}
