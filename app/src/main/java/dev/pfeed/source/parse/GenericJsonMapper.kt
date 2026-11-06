package dev.pfeed.source.parse

import dev.pfeed.data.settings.JsonFeed
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

/**
 * Maps an arbitrary JSON API response into [MediaItem]s using the field paths in a [JsonFeed]. This is
 * the universal adapter: point it at any endpoint that returns JSON and describe where the items and
 * their fields live. Pure, so it is unit-tested against sample payloads.
 */
object GenericJsonMapper {
    const val SOURCE_ID = "json"

    fun map(root: JsonElement, feed: JsonFeed): List<MediaItem> {
        return JsonPath.array(root, feed.itemsPath).mapNotNull { raw ->
            val base = if (feed.wrapPath.isBlank()) raw else JsonPath.select(raw, feed.wrapPath)
            val rawId = JsonPath.string(base, feed.idPath)
            val media = JsonPath.string(base, feed.mediaPath)?.let(MediaTypes::unescape) ?: return@mapNotNull null
            val type = when (feed.typeHint.lowercase()) {
                "video" -> MediaType.VIDEO
                "image" -> MediaType.IMAGE
                "gif" -> MediaType.GIF
                "embed" -> MediaType.EMBED
                else -> MediaTypes.classify(media) ?: MediaType.IMAGE
            }
            val id = rawId ?: shortHash(media)
            val thumb = feed.thumbnailPath.takeIf { it.isNotBlank() }?.let { JsonPath.string(base, it) }
            val creator = feed.creatorPath.takeIf { it.isNotBlank() }?.let { JsonPath.string(base, it) }
            val page = feed.pagePath.takeIf { it.isNotBlank() }?.let { JsonPath.string(base, it) }
            MediaItem(
                // Unique across feeds that share the "json" adapter.
                id = "${shortHash(feed.name)}:$id",
                sourceId = SOURCE_ID,
                type = if (type == MediaType.EMBED) MediaType.EMBED else type,
                title = JsonPath.string(base, feed.titlePath) ?: feed.name,
                creator = creator,
                tags = buildList {
                    add(feed.name.lowercase())
                    feed.tagsPath.takeIf { it.isNotBlank() }?.let { tp ->
                        JsonPath.array(base, tp).forEach { el ->
                            (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { add(it.lowercase()) }
                        }
                        JsonPath.string(base, tp)?.split(Regex("[,\\s]+"))?.forEach { if (it.isNotBlank()) add(it.lowercase()) }
                    }
                }.distinct().take(30),
                durationSec = null,
                thumbnailUrl = thumb ?: media.takeIf { type == MediaType.IMAGE || type == MediaType.GIF },
                pageUrl = page ?: media,
                publishedAt = null,
                extras = if (type == MediaType.EMBED) mapOf("player" to media) else mapOf("media" to media),
            )
        }
    }

    private fun shortHash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)
}
