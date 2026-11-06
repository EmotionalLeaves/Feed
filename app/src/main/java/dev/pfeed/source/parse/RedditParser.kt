package dev.pfeed.source.parse

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses Reddit's public listing JSON (`/r/<sub>/.json`, `/search.json`) into [MediaItem]s.
 * Handles reddit-hosted video (HLS), direct images/GIFs, and the preview image fallback.
 * Read-only use of a public, documented JSON endpoint; see SOURCES.md.
 */
object RedditParser {
    const val SOURCE_ID = "reddit"

    fun parse(root: JsonElement): List<MediaItem> {
        val children = ((root as? JsonObject)?.get("data") as? JsonObject)?.get("children") as? JsonArray
            ?: return emptyList()
        return children.mapNotNull { child ->
            val d = (child as? JsonObject)?.get("data") as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (d[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            fun bool(key: String) = d[key]?.jsonPrimitive?.booleanOrNull ?: false

            val id = str("name") ?: str("id") ?: return@mapNotNull null
            val permalink = str("permalink")
            val pageUrl = permalink?.let { "https://www.reddit.com$it" } ?: str("url") ?: return@mapNotNull null
            val over18 = bool("over_18")

            var type: MediaType? = null
            var media: String? = null
            var mime: String? = null

            val redditVideo = ((d["media"] as? JsonObject)?.get("reddit_video")) as? JsonObject
                ?: ((d["secure_media"] as? JsonObject)?.get("reddit_video")) as? JsonObject
            if (bool("is_video") && redditVideo != null) {
                val hls = (redditVideo["hls_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val fallback = (redditVideo["fallback_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                if (hls != null) {
                    media = MediaTypes.unescape(hls); type = MediaType.VIDEO; mime = "application/x-mpegURL"
                } else if (fallback != null) {
                    media = MediaTypes.unescape(fallback); type = MediaType.VIDEO
                }
            }
            if (media == null) {
                val url = str("url_overridden_by_dest") ?: str("url")
                val t = MediaTypes.classify(url)
                if (t != null && url != null) { media = url; type = t }
            }

            val previewSource = (((d["preview"] as? JsonObject)?.get("images") as? JsonArray)
                ?.firstOrNull() as? JsonObject)?.get("source") as? JsonObject
            val previewUrl = (previewSource?.get("url") as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.let(MediaTypes::unescape)

            if (media == null && previewUrl != null) { media = previewUrl; type = MediaType.IMAGE }
            if (media == null || type == null) return@mapNotNull null

            val thumbRaw = str("thumbnail")?.takeIf { it.startsWith("http") }
            val tags = buildList {
                str("subreddit")?.lowercase()?.let(::add)
                str("link_flair_text")?.lowercase()?.let(::add)
                if (over18) add("nsfw")
            }.distinct()

            MediaItem(
                id = id,
                sourceId = SOURCE_ID,
                type = type,
                title = str("title"),
                creator = str("author"),
                tags = tags,
                durationSec = redditVideo?.get("duration")?.jsonPrimitive?.doubleOrNull?.toInt(),
                thumbnailUrl = previewUrl ?: thumbRaw,
                pageUrl = pageUrl,
                publishedAt = MediaTypes.epochSeconds(d["created_utc"]?.jsonPrimitive?.doubleOrNull),
                extras = buildMap {
                    put("media", media)
                    mime?.let { put("mime", it) }
                },
            )
        }
    }
}
