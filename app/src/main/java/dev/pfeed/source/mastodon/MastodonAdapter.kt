package dev.pfeed.source.mastodon

import dev.pfeed.data.settings.MastodonExclusions
import dev.pfeed.data.settings.MastodonFeed
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.parse.MastodonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
        settings.get().mastodonFeeds
            .filterNot { isExcluded(it.host) }
            .map { json.encodeToString(MastodonFeed.serializer(), it) }

    /** Pages each timeline backwards with `max_id` = the oldest status id on the previous page. */
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val feed = json.decodeFromString(MastodonFeed.serializer(), entry)
        val root = http.get(id, timelineUrl(feed.host, feed.tag, maxId = token))
        val statuses = root as? JsonArray
        val oldest = (statuses?.lastOrNull() as? JsonObject)?.get("id") as? JsonPrimitive
        return EntryPage(MastodonParser.parse(root, feed.host), next = oldest?.contentOrNull?.takeIf { statuses?.isNotEmpty() == true })
    }

    /**
     * Hashtag search on every configured instance at once, each with its own deadline, plus anything
     * already loaded. Hashtags can't contain spaces, so "rough sex" is searched as #roughsex.
     */
    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val tag = query.trim().removePrefix("#").replace(Regex("[\\s_-]+"), "")
        if (tag.isEmpty()) return@cachedSearch emptyList()
        val hosts = settings.get().mastodonFeeds.filterNot { isExcluded(it.host) }.map { normalizeHost(it.host) }.distinct()
        val remote = coroutineScope {
            hosts.map { host ->
                async {
                    withTimeoutOrNull(SEARCH_HOST_TIMEOUT_MS) {
                        runCatching { MastodonParser.parse(http.get(id, timelineUrl(host, tag)), host) }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
        }
        val merged = (remote.flatten().sortedByDescending { it.publishedAt } + poolMatches(query)).distinctBy { it.key }
        merged
    }

    private fun timelineUrl(host: String, tag: String?, maxId: String? = null): String {
        val base = normalizeHost(host)
        val page = maxId?.let { "&max_id=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return if (tag.isNullOrBlank()) {
            "$base/api/v1/timelines/public?limit=40&only_media=true$page"
        } else {
            "$base/api/v1/timelines/tag/${URLEncoder.encode(tag.removePrefix("#"), "UTF-8")}?limit=40&only_media=true$page"
        }
    }

    private fun isExcluded(host: String): Boolean = MastodonExclusions.isExcluded(host)

    companion object {
        private const val SEARCH_HOST_TIMEOUT_MS = 6_000L
    }

    private fun normalizeHost(host: String): String {
        val h = host.trim().removeSuffix("/")
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
    }
}
