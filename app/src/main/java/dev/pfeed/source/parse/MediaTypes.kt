package dev.pfeed.source.parse

import dev.pfeed.domain.model.MediaType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure helpers shared by every source adapter: deciding a [MediaType] from a URL/MIME, parsing dates
 * and durations, and small HTML/entity cleanup. No Android dependencies, so it is unit-tested directly.
 */
object MediaTypes {
    const val HLS_MIME = "application/x-mpegURL"


    fun classify(url: String?, mime: String? = null, medium: String? = null): MediaType? {
        val m = mime?.lowercase()
        val u = url?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        fun ext(vararg e: String) = e.any { u.endsWith(it) }
        return when {
            m == "image/gif" || ext(".gif") -> MediaType.GIF
            m?.startsWith("video/") == true || m == "application/x-mpegurl" || m == "application/vnd.apple.mpegurl" ||
                medium == "video" || ext(".mp4", ".webm", ".m4v", ".mov", ".m3u8", ".mpd", ".mkv") -> MediaType.VIDEO
            m?.startsWith("image/") == true || medium == "image" ||
                ext(".jpg", ".jpeg", ".jpe", ".jfif", ".png", ".webp", ".avif", ".heic", ".heif", ".bmp") -> MediaType.IMAGE
            else -> null
        }
    }

    fun parseDate(raw: String?): Instant? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(s).toInstant() }
        runCatching { return Instant.parse(s) }
        runCatching { return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
        return null
    }

    fun epochSeconds(seconds: Double?): Instant? =
        seconds?.takeIf { it > 0 }?.let { Instant.ofEpochMilli((it * 1000).toLong()) }

    /** "SS", "MM:SS" or "HH:MM:SS". */
    fun parseDuration(raw: String): Int? {
        val parts = raw.trim().split(':').map { it.trim().toIntOrNull() ?: return null }
        return parts.fold(0) { acc, p -> acc * 60 + p }.takeIf { parts.isNotEmpty() }
    }

    private val tagRegex = Regex("<[^>]+>")

    /** Turns a snippet of HTML (Mastodon status content, etc.) into a one-line plain-text title. */
    fun stripHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val text = unescape(tagRegex.replace(html, " ")).replace(Regex("\\s+"), " ").trim()
        return text.ifBlank { null }
    }

    fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
}
