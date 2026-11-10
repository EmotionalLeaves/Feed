package dev.pfeed.source.parse

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType

/**
 * Reads gelbooru-engine web pages for boards whose API needs a key or is offline (rule34.xxx,
 * gelbooru.com, realbooru.com). Pure, and written against the markup the sites serve today:
 *
 *  - rule34.xxx / tbib / xbooru: `<span class="thumb"><a id="p123" href=…><img src=… alt="tag tag">`
 *  - gelbooru.com: `<article class="thumbnail-preview"><a id="p123" href=…><img src=… title="tag tag">`
 *  - realbooru.com: `<div class="col thumb"><a id="p123" href=…><img src=… title="tag one, tag two">`
 *
 * What they share is `<a id="p{id}">` directly wrapping the thumbnail `<img>`, so that's what is matched.
 */
object BooruHtml {
    private val ANCHOR_IMG = Regex("""<a[^>]*\bid="p(\d+)"[^>]*>\s*(<img[^>]*>)""", RegexOption.IGNORE_CASE)
    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name="([^"]*)"""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    fun parseListing(html: String, host: String): List<MediaItem> {
        val board = BooruFamily.bareHost(host).removePrefix("api.")
        val out = ArrayList<MediaItem>()
        val seen = HashSet<String>()
        for (m in ANCHOR_IMG.findAll(html)) {
            val postId = m.groupValues[1]
            if (!seen.add(postId)) continue
            val img = m.groupValues[2]
            val thumb = (attr(img, "src") ?: attr(img, "data-src"))?.let(::absolute)?.takeIf { it.startsWith("http") } ?: continue
            val tags = tags(attr(img, "title")?.takeIf { it.isNotBlank() } ?: attr(img, "alt").orEmpty())
            val lower = tags.map { it.lowercase() }
            // The listing only has a still thumbnail; the tags say what the post is.
            val type = when {
                lower.any { it in VIDEO_TAGS } -> MediaType.VIDEO
                lower.any { it == "gif" || it == "animated_gif" } -> MediaType.GIF
                else -> MediaType.IMAGE
            }
            out += MediaItem(
                id = "$board/$postId",
                sourceId = BooruParser.SOURCE_ID,
                type = type,
                title = null,
                creator = null,
                tags = tags,
                durationSec = null,
                thumbnailUrl = thumb,
                pageUrl = BooruFamily.postPage(board, postId),
                publishedAt = null,
                // The real file is read from the post page when the post is about to be shown.
                extras = buildMap {
                    put("media", thumb)
                    put("resolve", "page")
                    put("host", board)
                    tags.firstOrNull { it.startsWith("rating:") }?.let { r ->
                        dev.pfeed.domain.filter.ContentRating.normalize(r.removePrefix("rating:"))?.let { put("rating", it) }
                    }
                },
            )
        }
        return out
    }

    /** "tag_a tag_b score:3 rating:explicit" or "tag one, tag two" → board-style tags. */
    internal fun tags(raw: String): List<String> {
        val decoded = raw.replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").trim()
        val parts = if (decoded.contains(", ")) decoded.split(",").map { it.trim().replace(' ', '_') } else decoded.split(Regex("\\s+"))
        return parts.filter { it.isNotBlank() && !it.startsWith("score:") }.take(150)
    }

    data class PostMedia(val url: String, val full: String?)

    /** The file behind a post page: the video if there is one, else the sample image (original kept for zoom). */
    fun mediaFromPostPage(html: String, pageUrl: String): PostMedia? {
        val video = VIDEO_SRC.find(html)?.groupValues?.get(1)
        if (video != null) return PostMedia(absolute(video, pageUrl), null)
        val original = ORIGINAL_LINK.find(html)?.groupValues?.get(1)?.let { absolute(it, pageUrl) }
        if (original != null && MediaTypes.classify(original) == MediaType.VIDEO) return PostMedia(original, null)
        val shown = IMG_TAG.findAll(html).map { it.value }.firstOrNull { attr(it, "id") == "image" }
            ?.let { attr(it, "src") }?.let { absolute(it, pageUrl) }
        // A GIF's on-page image can be a still sample: the original is the animation.
        if (original != null && MediaTypes.classify(original) == MediaType.GIF) return PostMedia(original, null)
        val url = shown ?: original ?: return null
        return PostMedia(url, original?.takeIf { it != url })
    }

    internal fun absolute(url: String, page: String = ""): String {
        val u = url.replace("&amp;", "&").trim()
        val abs = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http") -> u
            u.startsWith("/") && page.isNotEmpty() -> "https://" + BooruFamily.bareHost(page).removePrefix("api.") + u
            else -> u
        }
        // "https://host//samples/…" (as the sites print it) → single slash.
        val scheme = abs.substringBefore("://", "")
        return if (scheme.isEmpty()) abs else scheme + "://" + abs.substringAfter("://").replace(Regex("/{2,}"), "/")
    }

    private val VIDEO_TAGS = setOf("video", "webm", "mp4", "animated", "sound", "has_audio", "animated_webm")
    private val VIDEO_SRC = Regex("""<(?:source|video)[^>]*\bsrc="([^"]+\.(?:mp4|webm|m4v|mov)[^"]*)"""", RegexOption.IGNORE_CASE)
    private val ORIGINAL_LINK = Regex("""<a[^>]*\bhref="([^"]+)"[^>]*>\s*Original(?: image)?\s*</a>""", RegexOption.IGNORE_CASE)
    private val IMG_TAG = Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE)
}
