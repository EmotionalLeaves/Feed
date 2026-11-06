package dev.pfeed.source.parse

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses a Mastodon/ActivityPub public or tag timeline (array of statuses) into [MediaItem]s, one per
 * media attachment. Uses the documented, auth-free public timeline API; the user supplies the instance
 * host. See SOURCES.md.
 */
object MastodonParser {
    const val SOURCE_ID = "mastodon"

    fun parse(root: JsonElement): List<MediaItem> {
        val statuses = root as? JsonArray ?: return emptyList()
        val out = ArrayList<MediaItem>()
        for (element in statuses) {
            val s = element as? JsonObject ?: continue
            // A boosted post carries its media under "reblog".
            val status = (s["reblog"] as? JsonObject) ?: s
            fun str(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull

            val statusId = str(status, "id") ?: str(s, "id") ?: continue
            val account = status["account"] as? JsonObject
            val creator = account?.let { str(it, "acct") ?: str(it, "username") }
            val pageUrl = str(status, "url") ?: str(status, "uri") ?: continue
            val title = MediaTypes.stripHtml(str(status, "content"))
            val published = MediaTypes.parseDate(str(status, "created_at"))
            val tags = (status["tags"] as? JsonArray)
                ?.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.lowercase() }
                ?.distinct().orEmpty()

            val attachments = status["media_attachments"] as? JsonArray ?: continue
            for (att in attachments) {
                val a = att as? JsonObject ?: continue
                val url = str(a, "url") ?: continue
                val type = when (str(a, "type")) {
                    "image" -> MediaType.IMAGE
                    "gifv", "video" -> MediaType.VIDEO
                    else -> MediaTypes.classify(url) ?: continue
                }
                val attId = str(a, "id") ?: url.hashCode().toString()
                out += MediaItem(
                    id = "${statusId}_$attId",
                    sourceId = SOURCE_ID,
                    type = type,
                    title = title ?: str(a, "description"),
                    creator = creator,
                    tags = tags,
                    durationSec = null,
                    thumbnailUrl = str(a, "preview_url") ?: url.takeIf { type == MediaType.IMAGE },
                    pageUrl = pageUrl,
                    publishedAt = published,
                    extras = mapOf("media" to url),
                )
            }
        }
        return out
    }
}
