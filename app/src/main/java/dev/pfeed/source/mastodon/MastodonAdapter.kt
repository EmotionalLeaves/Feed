package dev.pfeed.source.mastodon

import dev.pfeed.data.settings.MastodonFeed
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.pageAt
import dev.pfeed.source.parse.MastodonParser
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads Mastodon/ActivityPub public and hashtag timelines (documented, auth-free). Entries are encoded
 * as "host|tag" so the caching base can key on them; the user adds an instance host and optional tag.
 */
@Singleton
class MastodonAdapter @Inject constructor(
    private val settings: SettingsRepository,
    private val http: HttpJson,
    private val json: Json,
) : CachingAdapter() {
    override val id = MastodonParser.SOURCE_ID
    override val displayName = "Mastodon / Fediverse"
    override val requiresAuth = false

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> =
        settings.get().mastodonFeeds.map { json.encodeToString(MastodonFeed.serializer(), it) }

    override suspend fun fetchEntry(entry: String): List<MediaItem> {
        val feed = json.decodeFromString(MastodonFeed.serializer(), entry)
        return MastodonParser.parse(http.get(id, timelineUrl(feed.host, feed.tag)))
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val hosts = settings.get().mastodonFeeds.map { normalizeHost(it.host) }.distinct()
        val tag = query.trim().removePrefix("#")
        val merged = hosts.flatMap { host ->
            runCatching { MastodonParser.parse(http.get(id, timelineUrl(host, tag))) }.getOrDefault(emptyList())
        }.distinctBy { it.key }
        return merged.pageAt(cursor, pageSize)
    }

    private fun timelineUrl(host: String, tag: String?): String {
        val base = normalizeHost(host)
        return if (tag.isNullOrBlank()) {
            "$base/api/v1/timelines/public?limit=40&only_media=true"
        } else {
            "$base/api/v1/timelines/tag/${URLEncoder.encode(tag.removePrefix("#"), "UTF-8")}?limit=40&only_media=true"
        }
    }

    private fun normalizeHost(host: String): String {
        val h = host.trim().removeSuffix("/")
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
    }
}
