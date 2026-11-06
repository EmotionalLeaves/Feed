package dev.pfeed.source.direct

import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.parse.MediaTypes
import dev.pfeed.source.sha1Hex
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Plays direct media links (mp4/webm/m3u8/gif/jpg/png/webp…) the user pastes. No network fetch to list; each URL is one item. */
@Singleton
class DirectMediaAdapter @Inject constructor(
    private val settings: SettingsRepository,
) : CachingAdapter() {
    override val id = "direct"
    override val displayName = "Direct links"
    override val requiresAuth = false

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> = settings.get().directUrls

    override suspend fun fetchEntry(entry: String): List<MediaItem> {
        val url = entry.toHttpUrlOrNull()?.toString() ?: return emptyList()
        val type = MediaTypes.classify(url) ?: MediaType.VIDEO
        val name = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.substringBeforeLast('.')
        return listOf(
            MediaItem(
                id = sha1Hex(url),
                sourceId = id,
                type = type,
                title = name?.ifBlank { null },
                creator = url.toHttpUrlOrNull()?.host,
                tags = emptyList(),
                durationSec = null,
                thumbnailUrl = if (type == MediaType.IMAGE || type == MediaType.GIF) url else null,
                pageUrl = url,
                publishedAt = null,
                extras = mapOf("media" to url),
            ),
        )
    }
}
