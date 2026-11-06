package dev.pfeed.source.rss

import android.util.Xml
import dev.pfeed.domain.model.MediaType
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Minimal RSS 2.0 / Atom parser with Media RSS (`media:`), enclosure and iTunes-duration support. */
object FeedParser {

    data class Entry(
        val id: String?,
        val title: String?,
        val link: String?,
        val author: String?,
        val categories: List<String>,
        val published: Instant?,
        val mediaUrl: String?,
        val mediaMime: String?,
        val mediaMedium: String?,
        val thumbnail: String?,
        val playerUrl: String?,
        val durationSec: Int?,
    )

    data class Result(val title: String?, val entries: List<Entry>)

    private const val MEDIA_NS = "http://search.yahoo.com/mrss/"
    private const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    private class Builder {
        var id: String? = null
        var title: String? = null
        var link: String? = null
        var author: String? = null
        val categories = mutableListOf<String>()
        var published: Instant? = null
        var updated: Instant? = null
        val media = mutableListOf<Triple<String, String?, String?>>() // url, mime, medium
        var thumbnail: String? = null
        var playerUrl: String? = null
        var durationSec: Int? = null

        fun build(): Entry {
            val best = media.firstOrNull { classify(it.first, it.second, it.third) == MediaType.VIDEO }
                ?: media.firstOrNull { classify(it.first, it.second, it.third) != null }
            return Entry(
                id = id, title = title?.trim(), link = link, author = author?.trim(),
                categories = categories.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                published = published ?: updated,
                mediaUrl = best?.first, mediaMime = best?.second, mediaMedium = best?.third,
                thumbnail = thumbnail, playerUrl = playerUrl, durationSec = durationSec,
            )
        }
    }

    fun parse(input: InputStream): Result {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)

        var feedTitle: String? = null
        val entries = mutableListOf<Entry>()
        var current: Builder? = null
        var inAuthor = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    val ns = parser.namespace.orEmpty()
                    val b = current
                    if (name == "item" || name == "entry") {
                        current = Builder()
                        continue
                    }
                    if (b == null) {
                        if (name == "title" && feedTitle == null && ns != MEDIA_NS) feedTitle = parser.safeText()
                        continue
                    }
                    when {
                        ns == MEDIA_NS -> when (name) {
                            "content" -> {
                                val url = parser.attr("url")
                                if (url != null) b.media += Triple(url, parser.attr("type"), parser.attr("medium"))
                                parser.attr("duration")?.toIntOrNull()?.let { b.durationSec = it }
                            }
                            "thumbnail" -> if (b.thumbnail == null) b.thumbnail = parser.attr("url")
                            "player" -> b.playerUrl = parser.attr("url")
                            "keywords" -> b.categories += parser.safeText().split(',')
                            "title" -> if (b.title == null) b.title = parser.safeText()
                            "credit" -> if (b.author == null) b.author = parser.safeText()
                        }
                        ns == ITUNES_NS && name == "duration" -> b.durationSec = parseDuration(parser.safeText())
                        name == "title" -> b.title = parser.safeText()
                        name == "link" -> {
                            val href = parser.attr("href")
                            if (href == null) {
                                b.link = parser.safeText().trim()
                            } else {
                                when (parser.attr("rel")) {
                                    null, "alternate" -> if (b.link == null) b.link = href
                                    "enclosure" -> b.media += Triple(href, parser.attr("type"), null)
                                }
                            }
                        }
                        name == "guid" || name == "id" -> b.id = parser.safeText().trim()
                        name == "author" -> {
                            inAuthor = true
                            // RSS: <author>text</author>. Atom: <author><name/></author> (handled below).
                            if (ns.isEmpty()) {
                                val text = parser.safeText()
                                if (text.isNotBlank()) b.author = text
                                inAuthor = false
                            }
                        }
                        name == "name" && inAuthor -> b.author = parser.safeText()
                        name == "creator" -> b.author = parser.safeText()
                        name == "category" -> {
                            val term = parser.attr("term")
                            b.categories += term ?: parser.safeText()
                        }
                        name == "pubDate" || name == "published" || name == "date" -> b.published = parseDate(parser.safeText())
                        name == "updated" -> b.updated = parseDate(parser.safeText())
                        name == "enclosure" -> parser.attr("url")?.let { b.media += Triple(it, parser.attr("type"), null) }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "item", "entry" -> {
                            current?.let { entries += it.build() }
                            current = null
                        }
                        "author" -> inAuthor = false
                    }
                }
            }
        }
        return Result(feedTitle?.trim(), entries)
    }

    fun classify(url: String?, mime: String?, medium: String?): MediaType? {
        val m = mime?.lowercase()
        val u = url?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        fun ext(vararg e: String) = e.any { u.endsWith(it) }
        return when {
            m == "image/gif" || ext(".gif") -> MediaType.GIF
            m?.startsWith("video/") == true || m == "application/x-mpegurl" || m == "application/vnd.apple.mpegurl" ||
                medium == "video" || ext(".mp4", ".webm", ".m4v", ".mov", ".m3u8", ".mkv") -> MediaType.VIDEO
            m?.startsWith("image/") == true || medium == "image" || ext(".jpg", ".jpeg", ".png", ".webp", ".avif") -> MediaType.IMAGE
            else -> null
        }
    }

    fun parseDate(raw: String): Instant? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(s).toInstant() }
        runCatching { return Instant.parse(s) }
        runCatching { return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
        return null
    }

    /** "SS", "MM:SS" or "HH:MM:SS". */
    fun parseDuration(raw: String): Int? {
        val parts = raw.trim().split(':').map { it.trim().toIntOrNull() ?: return null }
        return parts.fold(0) { acc, p -> acc * 60 + p }.takeIf { parts.isNotEmpty() }
    }

    private fun XmlPullParser.attr(name: String): String? =
        getAttributeValue(null, name)?.takeIf { it.isNotBlank() }

    /** Reads text content; skips any nested markup instead of throwing. */
    private fun XmlPullParser.safeText(): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString()
    }
}
