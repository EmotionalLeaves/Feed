package dev.pfeed.source.tube

import dev.pfeed.source.net.attempt
import dev.pfeed.data.settings.SourceCatalog
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.SourceException
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tube sites (XVideos, Pornhub, xHamster, RedTube, Eporner): each lists videos from its "new",
 * "top" and category sections, and plays them from the stream its video page exposes (HLS or MP4),
 * falling back to the site's own embed player when it doesn't expose one (Eporner always).
 *
 * Entry = "host|section". Each site is one origin for spacing, and one group for rate limiting.
 */
@Singleton
class TubeAdapter @Inject constructor(private val http: HttpJson) : CachingAdapter() {
    override val id = SOURCE_ID
    override val displayName = "Tube sites"
    override val requiresAuth = false
    override val maxConcurrency = 5
    override val entriesPerExtend = 6
    override val maxPerGroupPerExtend = 2
    override fun groupOf(entry: String) = entry.substringBefore('|')

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> =
        TubeSite.ALL.flatMap { site -> SourceCatalog.tubeSections.map { "${site.host}|$it" } }

    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val site = TubeSite.byHost(entry.substringBefore('|')) ?: return EntryPage(emptyList(), null)
        val section = entry.substringAfter('|', "new")
        val page = token?.toIntOrNull() ?: 0
        val items = site.items(listWithMirrors(site, site.listUrl(section, page)), section)
        val next = if (items.isEmpty() || page + 1 >= MAX_PAGES) null else (page + 1).toString()
        return EntryPage(items, next)
    }

    private val resolved = ConcurrentHashMap<String, StreamInfo>()

    /** Mirror that last answered for a site; tried first next time. */
    private val workingMirror = ConcurrentHashMap<String, String>()

    /** The listing at [url], from the site or, if it answers with nothing usable, one of its mirrors. */
    private suspend fun listWithMirrors(site: TubeSite, url: String): List<TubeVideo> {
        val order = listOfNotNull(workingMirror[site.host]) + (listOf(site.host) + site.mirrors).filter { it != workingMirror[site.host] }
        var lastError: Exception? = null
        for (domain in order) {
            val u = if (domain == site.host) url else site.onMirror(url, domain)
            try {
                val videos = site.parseList(http.getRaw(id, u))
                if (videos.isNotEmpty()) {
                    if (domain != site.host) workingMirror[site.host] = domain else workingMirror.remove(site.host)
                    return videos
                }
            } catch (e: SourceException) {
                lastError = e
            }
            if (site.mirrors.isEmpty()) break
        }
        lastError?.let { throw it }
        return emptyList()
    }

    /**
     * One line per site: does its newest page list videos, and does the first one play directly
     * (or only through the site's embed)? For diagnostics (the on-device health test).
     */
    suspend fun siteReport(): List<Pair<String, String>> = coroutineScope {
        TubeSite.ALL.map { site ->
            async {
                site.label to (withTimeoutOrNull(30_000) {
                    attempt {
                        val items = site.items(listWithMirrors(site, site.listUrl("new", 0)), "new")
                        if (items.isEmpty()) {
                            val raw = attempt { http.getRaw(id, site.listUrl("new", 0)) }.getOrElse { return@attempt "lists nothing (${it.message?.take(60)})" }
                            val title = Regex("<title[^>]*>([^<]{0,60})", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.trim()
                            return@attempt "lists nothing (page ${raw.length / 1024} KB, \"$title\")"
                        }
                        val first = items.first()
                        val how = when (val st = resolveStream(first)) {
                            is StreamInfo.Direct -> if (http.reachable(st.url)) "plays directly" else "stream doesn't load"
                            is StreamInfo.Embed -> "plays in embed"
                            else -> st.javaClass.simpleName
                        }
                        "${items.size} videos, $how"
                    }.getOrElse { "error: ${it.message?.take(80)}" }
                } ?: "no answer in 30 s")
            }
        }.awaitAll()
    }

    override suspend fun resolveStream(item: MediaItem): StreamInfo {
        if (item.extras["resolve"] != "tube") return super.resolveStream(item)
        resolved[item.key]?.let { return it }
        val site = TubeSite.byHost(item.id.substringBefore('/')) ?: throw SourceException(id, "Unknown site")
        val vid = item.extras["vid"] ?: item.id.substringAfter('/')
        val stream: StreamInfo = try {
            val page = site.streamPageUrl(item.pageUrl, vid)
            val html = http.getRaw(id, page)
            val direct = site.stream(html) ?: site.streamIndexUrl(html)?.let { index ->
                attempt { site.streamFromIndex(http.get(id, index, mapOf("Referer" to page))) }.getOrNull()
            }
            direct?.let { StreamInfo.Direct(it.url, it.mime) } ?: StreamInfo.Embed(site.embedUrl(vid))
        } catch (e: SourceException) {
            // Page refused or changed: the embed still works.
            StreamInfo.Embed(site.embedUrl(vid))
        }
        resolved[item.key] = stream
        return stream
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        val remote = coroutineScope {
            TubeSite.ALL.map { site ->
                async {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        attempt { site.items(listWithMirrors(site, site.listUrl(q, 0)), q) }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
        }
        (CachingAdapter.interleave(remote) + poolMatches(query)).distinctBy { it.key }
    }

    companion object {
        const val SOURCE_ID = "tube"
        private const val MAX_PAGES = 30
        private const val SEARCH_TIMEOUT_MS = 8_000L
    }
}
