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
import kotlinx.coroutines.sync.withPermit
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
class TubeAdapter @Inject constructor(
    private val http: HttpJson,
    private val blocks: dev.pfeed.data.repo.BlockRepository,
) : CachingAdapter() {
    /** Sites switched off (in Sources, or hidden from a post's menu): not asked at all. */
    private suspend fun activeSites(): List<TubeSite> {
        val hidden = runCatching { blocks.current().origins }.getOrDefault(emptySet())
        return TubeSite.ALL.filter { "tube:${it.host}" !in hidden }
    }

    override val id = SOURCE_ID
    override val displayName = "Tube sites"
    override val requiresAuth = false
    override val maxConcurrency = 5
    override val entriesPerExtend = 6
    override val maxPerGroupPerExtend = 2
    override fun groupOf(entry: String) = entry.substringBefore('|')

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> =
        activeSites().flatMap { site -> SourceCatalog.tubeSections.map { "${site.host}|$it" } }

    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val site = TubeSite.byHost(entry.substringBefore('|')) ?: return EntryPage(emptyList(), null)
        val section = entry.substringAfter('|', "new")
        val page = token?.toIntOrNull() ?: 0
        val listed = site.items(listWithMirrors(site, site.listUrl(section, page)), section, page)
        val items = if (site.checkTagsFirst) checked(site, listed.take(TAG_CHECK_LIMIT)) else listed
        val next = if (listed.isEmpty() || page + 1 >= MAX_PAGES) null else (page + 1).toString()
        return EntryPage(items, next)
    }

    private val tagGate = kotlinx.coroutines.sync.Semaphore(4)

    /**
     * For [TubeSite.checkTagsFirst] sites: each video page is read, its tags added, and the post kept
     * only if it has tags and the minors filter allows them. The stream on that page is kept for
     * playback, so this costs no extra request when the video is opened.
     */
    private suspend fun checked(site: TubeSite, items: List<MediaItem>): List<MediaItem> = coroutineScope {
        items.map { item ->
            async {
                tagGate.withPermit {
                    attempt {
                        val html = http.getRaw(id, item.pageUrl)
                        val tags = site.pageTags(html)
                        if (tags.isEmpty()) return@attempt null
                        site.stream(html)?.let { resolved[item.key] = StreamInfo.Direct(it.url, it.mime) to System.currentTimeMillis() }
                        item.copy(tags = (tags + item.tags).distinct())
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull().filter(dev.pfeed.domain.filter.MinorsFilter::allows)
    }

    private val resolved = ConcurrentHashMap<String, Pair<StreamInfo, Long>>()

    /** Mirror that last answered for a site; tried first next time. */
    private val workingMirror = ConcurrentHashMap<String, String>()

    /** The listing at [url], from the site or, if it answers with nothing usable, one of its mirrors. */
    private suspend fun listWithMirrors(site: TubeSite, url: String): List<TubeVideo> {
        // A site that answered every domain with an empty shell (a regional page with no list at
        // all, see TubeSite.isShell) rests for a while instead of being asked four times per load.
        if ((emptyUntil[site.host] ?: 0L) > System.currentTimeMillis()) return emptyList()
        val order = listOfNotNull(workingMirror[site.host]) + (listOf(site.host) + site.mirrors).filter { it != workingMirror[site.host] }
        var lastError: Exception? = null
        var shells = 0
        for (domain in order) {
            val u = if (domain == site.host) url else site.onMirror(url, domain)
            try {
                val body = http.getRaw(id, u)
                if (site.isShell(body)) shells++
                val videos = site.parseList(body)
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
        if (shells == order.size) emptyUntil[site.host] = System.currentTimeMillis() + EMPTY_REST_MS
        return emptyList()
    }

    private val emptyUntil = ConcurrentHashMap<String, Long>()

    /**
     * One line per site: does its newest page list videos, and does the first one play directly
     * (or only through the site's embed)? For diagnostics (the on-device health test).
     */
    suspend fun siteReport(): List<Pair<String, String>> = coroutineScope {
        TubeSite.ALL.map { site ->
            async {
                site.label to (withTimeoutOrNull(30_000) {
                    attempt {
                        emptyUntil.remove(site.host) // the report always asks
                        val items = site.items(listWithMirrors(site, site.listUrl("new", 0)), "new")
                        if (items.isEmpty()) {
                            val raw = attempt { http.getRaw(id, site.listUrl("new", 0)) }.getOrElse { return@attempt "lists nothing (${it.message?.take(60)})" }
                            val title = Regex("<title[^>]*>([^<]{0,60})", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.trim()
                            val text = raw.replace(Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ")
                                .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(160)
                            // xHamster answers some regions with an empty shell (no list at all): say so plainly.
                            if (site.isShell(raw)) return@attempt "lists nothing here: the site sends this network an empty page (regional block); works elsewhere"
                            return@attempt "lists nothing (page ${raw.length / 1024} KB, \"$title\": $text)"
                        }
                        val first = items.first()
                        resolved.remove(first.key) // judge the site now, not an earlier answer
                        val how = when (val st = resolveStream(first)) {
                            is StreamInfo.Direct -> if (http.reachable(st.url)) "plays directly" else "stream doesn't load"
                            is StreamInfo.Embed -> {
                                val page = attempt { http.getRaw(id, first.pageUrl) }.getOrNull()
                                val hint = page?.let { p -> listOf("setVideoHLS", "mediaDefinitions", "<source", "flashvars").firstOrNull { p.contains(it) }?.let { k -> " has $k: " + p.substring(p.indexOf(k), minOf(p.length, p.indexOf(k) + 110)).replace(Regex("\\s+"), " ") } }
                                "plays in embed (video page ${page?.length?.div(1024) ?: "?"} KB, \"${page?.let { Regex("<title[^>]*>([^<]{0,50})", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim() }}\"${hint.orEmpty()})"
                            }
                        }
                        "${items.size} videos, $how"
                    }.getOrElse { "error: ${it.message?.take(80)}" }
                } ?: "no answer in 30 s")
            }
        }.awaitAll()
    }

    override suspend fun resolveStream(item: MediaItem): StreamInfo {
        if (item.extras["resolve"] != "tube") return super.resolveStream(item)
        resolved[item.key]?.let { (s, at) -> if (System.currentTimeMillis() - at < RESOLVED_TTL_MS) return s }
        val site = TubeSite.byHost(item.id.substringBefore('/')) ?: throw SourceException(id, "Unknown site")
        val vid = item.extras["vid"] ?: item.id.substringAfter('/')
        val stream: StreamInfo = try {
            val page = site.streamPageUrl(item.pageUrl, vid)
            val html = http.getRaw(id, page)
            val direct = site.stream(html) ?: site.streamIndexUrl(html)?.let { index ->
                attempt { site.streamFromIndex(http.get(id, index, mapOf("Referer" to page))) }.getOrNull()
            } ?: site.embedUrl(vid).takeIf { it != page }?.let { embed ->
                // Some pages (a region notice, a "processing" placeholder) lack the player setup that
                // the embed page still carries; read the stream from there before giving up.
                attempt { site.stream(http.getRaw(id, embed)) }.getOrNull()
            }
            direct?.let { StreamInfo.Direct(it.url, it.mime) } ?: StreamInfo.Embed(site.embedUrl(vid))
        } catch (e: SourceException) {
            // Page refused (often a moment's rate limit): the embed works now, and next time the
            // page is asked again rather than remembering the fallback.
            return StreamInfo.Embed(site.embedUrl(vid))
        }
        // Remembered a while: stream links carry expiring tokens, so not for the whole session.
        resolved[item.key] = stream to System.currentTimeMillis()
        return stream
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        val sites = activeSites()
        val remote = coroutineScope {
            sites.map { site ->
                async {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        attempt {
                            val found = site.items(listWithMirrors(site, site.listUrl(q, 0)), q)
                            if (site.checkTagsFirst) checked(site, found.take(TAG_CHECK_SEARCH)) else found
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
        }
        (CachingAdapter.interleave(remote) + poolMatches(query)).distinctBy { it.key }
    }

    companion object {
        /** How long a site that lists nothing on any of its domains is left alone. */
        private const val EMPTY_REST_MS = 20 * 60_000L

        private const val RESOLVED_TTL_MS = 20 * 60_000L
        private const val TAG_CHECK_LIMIT = 16
        private const val TAG_CHECK_SEARCH = 8
        const val SOURCE_ID = "tube"
        private const val MAX_PAGES = 30
        private const val SEARCH_TIMEOUT_MS = 8_000L
    }
}
