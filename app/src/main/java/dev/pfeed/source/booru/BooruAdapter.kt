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

    /**
     * Builds the correct API URL for the board family. Each board family has its own path:
     * - Danbooru/safebooru/e621/tbib: /posts.json
     * - rule34.xxx / gelbooru.com: /index.php?page=dapi&s=post&q=index&json=1
     * - konachan / yande.re (moebooru): /post.json
     * - Sankaku: /post/index.json
     */
    private fun postsUrl(host: String, tags: String?): String {
        val enc = tags?.trim().orEmpty().let { if (it.isEmpty()) "" else URLEncoder.encode(it, "UTF-8") }
        val h = host.removeSuffix("/")
        return when {
            h.contains("rule34.xxx") || h.contains("gelbooru.com") -> {
                val tagsParam = if (enc.isEmpty()) "" else "&tags=$enc"
                "$h/index.php?page=dapi&s=post&q=index&json=1&limit=40$tagsParam"
            }
            h.contains("konachan.com") || h.contains("yande.re") || h.contains("behoimi") -> {
                val tagsParam = if (enc.isEmpty()) "" else "&tags=$enc"
                "$h/post.json?limit=40$tagsParam"
            }
            h.contains("sankakucomplex.com") -> {
                val tagsParam = if (enc.isEmpty()) "" else "&tags=$enc"
                "$h/post/index.json?limit=40$tagsParam"
            }
            else -> {
                // Danbooru/e621/tbib/xbooru/dollbooru and most others
                val tagsParam = if (enc.isEmpty()) "" else "&tags=$enc"
                "$h/posts.json?limit=40$tagsParam"
            }
        }
    }

    private fun normalizeHost(host: String): String {
        val h = host.trim().removeSuffix("/")
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
    }
}
