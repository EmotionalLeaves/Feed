package dev.pfeed.source.booru

import dev.pfeed.data.settings.BooruFeed
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.pageAt
import dev.pfeed.source.parse.BooruParser
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads Danbooru-style `posts.json` image-board APIs (Danbooru/e621/moebooru-compatible). The user adds
 * a host and optional tag filter; entries are encoded as JSON so the caching base can key on them.
 */
@Singleton
class BooruAdapter @Inject constructor(
    private val settings: SettingsRepository,
    private val http: HttpJson,
    private val json: Json,
) : CachingAdapter() {
    override val id = BooruParser.SOURCE_ID
    override val displayName = "Booru (Danbooru-style)"
    override val requiresAuth = false

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> =
        settings.get().booruFeeds.map { json.encodeToString(BooruFeed.serializer(), it) }

    override suspend fun fetchEntry(entry: String): List<MediaItem> {
        val feed = json.decodeFromString(BooruFeed.serializer(), entry)
        val host = normalizeHost(feed.host)
        return BooruParser.parse(http.get(id, postsUrl(host, feed.tags)), host)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val hosts = settings.get().booruFeeds.map { normalizeHost(it.host) }.distinct()
        val merged = hosts.flatMap { host ->
            runCatching { BooruParser.parse(http.get(id, postsUrl(host, query)), host) }.getOrDefault(emptyList())
        }.distinctBy { it.key }
        return merged.pageAt(cursor, pageSize)
    }

    private fun postsUrl(host: String, tags: String?): String {
        val query = tags?.trim().orEmpty().let { if (it.isEmpty()) "" else "&tags=${URLEncoder.encode(it, "UTF-8")}" }
        return "$host/posts.json?limit=40$query"
    }

    private fun normalizeHost(host: String): String {
        val h = host.trim().removeSuffix("/")
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
    }
}
