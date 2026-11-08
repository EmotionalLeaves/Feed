package dev.pfeed.source.parse

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses the Danbooru-style `posts.json` family (Danbooru, e621, moebooru-compatible hosts). These are
 * public, documented image-board APIs; the user supplies the host. Handles the flat Danbooru shape and
 * the `{"posts":[...]}` / `file.url` shape (e621).
 */
object BooruParser {
    const val SOURCE_ID = "booru"

    /** Boards tag posts thoroughly; keep them all (the recommender learns from every one). */
    private const val MAX_TAGS = 150

    fun parse(root: JsonElement, host: String): List<MediaItem> {
        val posts: List<JsonElement> = when {
            root is JsonArray -> root
            // e621: {"posts":[...]}
            root is JsonObject && root["posts"] is JsonArray -> (root["posts"] as JsonArray)
            // Gelbooru/rule34: {"@attributes":{...}, "post":[...]}
            root is JsonObject && root["post"] is JsonArray -> (root["post"] as JsonArray)
            // Gelbooru single result: {"post":{...}}
            root is JsonObject && root["post"] is JsonObject -> listOf(root["post"]!!)
            // Sankaku capi-v2: {"data":[...]}
            root is JsonObject && root["data"] is JsonArray -> (root["data"] as JsonArray)
            else -> emptyList()
        }
        // Post numbers are only unique within one board: prefix the board so rule34 #123 and
        // gelbooru #123 are different items (likes, history, playback and links stay separate).
        val board = BooruFamily.bareHost(host).removePrefix("api.")
        return posts.mapNotNull { element ->
            val p = element as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (p[key] as? JsonPrimitive)?.contentOrNull
            val id = (p["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null

            // Danbooru: file_url / large_file_url
            // e621: file.url
            // moebooru (konachan/yande.re): file_url or sample_url
            // Gelbooru/rule34: file_url or sample_url
            val fileObj = p["file"] as? JsonObject
            val media = str("file_url") ?: str("large_file_url") ?: str("sample_url")
                ?: (fileObj?.get("url") as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            val type = MediaTypes.classify(media) ?: return@mapNotNull null

            val preview = str("preview_file_url") ?: str("preview_url")
                ?: ((p["preview"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull

            // e621 groups tags by category; use every category (general, species, character, …).
            val tagGroups = p["tags"] as? JsonObject
            val tagString = str("tag_string")
                ?: str("tags")  // gelbooru/rule34/moebooru: space-separated string under "tags"
                ?: tagGroups?.values?.flatMap { g -> (g as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty() }
                    ?.joinToString(" ")
            val tags = tagString?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.take(MAX_TAGS).orEmpty()
            val artist = str("tag_string_artist")?.split(' ')?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: ((tagGroups?.get("artist") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it != "conditional_dnp" }
            // Community vote score: a quality prior for ranking. e621 nests it as {"total": n}.
            val score = (p["score"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: ((p["score"] as? JsonObject)?.get("total") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: str("total_score")?.toDoubleOrNull()
                ?: str("fav_count")?.toDoubleOrNull()

            MediaItem(
                id = "$board/$id",
                sourceId = SOURCE_ID,
                type = type,
                // Boards have no real titles. A made-up one (e.g. the first tags) made thousands of
                // different posts look identical to the duplicate filter, so leave it empty; the
                // tags are shown instead.
                title = null,
                creator = artist,
                tags = tags,
                durationSec = null,
                thumbnailUrl = preview ?: media.takeIf { type != MediaType.VIDEO },
                pageUrl = BooruFamily.postPage(board, id),
                publishedAt = MediaTypes.parseDate(str("created_at")),
                extras = buildMap {
                    put("media", media)
                    score?.let { put("score", it.toLong().toString()) }
                    dev.pfeed.domain.filter.ContentRating.normalize(str("rating"))?.let { put("rating", it) }
                },
            )
        }
    }
}
