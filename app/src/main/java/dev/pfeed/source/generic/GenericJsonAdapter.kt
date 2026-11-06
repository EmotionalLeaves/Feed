package dev.pfeed.source.generic

import dev.pfeed.data.settings.JsonFeed
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.pageAt
import dev.pfeed.source.parse.GenericJsonMapper
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Universal adapter: fetches any JSON endpoint the user describes with a [JsonFeed] mapping. The
 * url/search templates may contain {cursor} and {query}. This is how an arbitrary source with a JSON
 * API becomes a feed without any site-specific code.
 */
@Singleton
class GenericJsonAdapter @Inject constructor(
    private val settings: SettingsRepository,
    private val http: HttpJson,
    private val json: Json,
) : CachingAdapter() {
    override val id = GenericJsonMapper.SOURCE_ID
    override val displayName = "Generic JSON API"
    override val requiresAuth = false

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> =
        settings.get().jsonFeeds.map { json.encodeToString(JsonFeed.serializer(), it) }

    override suspend fun fetchEntry(entry: String): List<MediaItem> {
        val feed = json.decodeFromString(JsonFeed.serializer(), entry)
        val url = feed.urlTemplate.replace("{cursor}", "").replace("{query}", "")
        return GenericJsonMapper.map(http.get(id, url), feed)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val feeds = settings.get().jsonFeeds.filter { !it.searchTemplate.isNullOrBlank() }
        val merged = feeds.flatMap { feed ->
            val url = feed.searchTemplate!!.replace("{query}", q).replace("{cursor}", "")
            runCatching { GenericJsonMapper.map(http.get(id, url), feed) }.getOrDefault(emptyList())
        }.distinctBy { it.key }
        return if (feeds.isEmpty()) super.search(query, cursor) else merged.pageAt(cursor, pageSize)
    }
}
