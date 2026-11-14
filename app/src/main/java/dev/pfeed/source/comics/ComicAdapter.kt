package dev.pfeed.source.comics

import dev.pfeed.data.settings.SourceCatalog
import dev.pfeed.domain.filter.MinorsFilter
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.source.net.CachingAdapter
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.net.attempt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comic sites that share one gallery engine (IMHentai, HentaiFox): a listing of galleries, and a
 * gallery page that names its image folder and every page's format. Each gallery is one comic you
 * swipe through. Entry = "host|tag".
 *
 * Every gallery's page is read before it's shown (for its pages and its tag names), and galleries
 * whose tags the minors filter rejects never become items; one that can't be read is dropped.
 */
@Singleton
class ComicAdapter @Inject constructor(private val http: HttpJson) : CachingAdapter() {
    override val id = SOURCE_ID
    override val displayName = "Comic sites"
    override val requiresAuth = false
    override val maxConcurrency = 2
    override val entriesPerExtend = 2
    override val entryTimeoutMs = 30_000L
    override fun groupOf(entry: String) = entry.substringBefore('|')

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> =
        ComicSite.ALL.flatMap { site -> (listOf("") + SourceCatalog.comicTags).map { "${site.host}|$it" } }

    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val site = ComicSite.byHost(entry.substringBefore('|')) ?: return EntryPage(emptyList(), null)
        val tag = entry.substringAfter('|', "")
        val page = token?.toIntOrNull() ?: 1
        val listed = ComicSite.parseList(site, http.getRaw(id, site.listUrl(tag.replace('-', ' '), page)))
        // The newest listing is newest-first; date it by position so Latest can place it.
        val now = java.time.Instant.now()
        val items = readAll(site, listed).mapIndexed { i, it ->
            if (tag.isEmpty() && it.publishedAt == null) it.copy(publishedAt = now.minusSeconds(((page * 30L) + i) * 600L)) else it
        }
        val next = if (listed.isEmpty() || page >= MAX_PAGES) null else (page + 1).toString()
        return EntryPage(items, next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        val found = coroutineScope {
            ComicSite.ALL.map { site ->
                async { attempt { readAll(site, ComicSite.parseList(site, http.getRaw(id, site.listUrl(q, 1))).take(SEARCH_LIMIT)) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }
        (found + poolMatches(query)).distinctBy { it.key }
    }

    private val gate = Semaphore(PAGE_CONCURRENCY)

    private suspend fun readAll(site: ComicSite, listed: List<ComicSite.Listed>): List<MediaItem> = coroutineScope {
        listed.map { l ->
            async {
                gate.withPermit {
                    attempt { ComicSite.gallery(site, l, http.getRaw(id, site.galleryUrl(l.id))) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull().filter(MinorsFilter::allows)
    }

    companion object {
        const val SOURCE_ID = "comics"
        private const val MAX_PAGES = 20
        private const val PAGE_CONCURRENCY = 4
        private const val SEARCH_LIMIT = 16
    }
}

/** One site on the shared gallery engine. */
class ComicSite private constructor(
    val host: String,
    val label: String,
    private val listBase: String,
    private val searchBase: String,
) {
    data class Listed(val id: String, val title: String?, val thumb: String?)

    fun listUrl(term: String, page: Int): String =
        if (term.isBlank()) "$listBase?page=$page" else "$searchBase${URLEncoder.encode(term, "UTF-8")}&page=$page"

    fun galleryUrl(id: String) = "https://$host/gallery/$id/"

    companion object {
        val IMHENTAI = ComicSite("imhentai.xxx", "IMHentai", "https://imhentai.xxx/", "https://imhentai.xxx/search/?key=")
        val HENTAIFOX = ComicSite("hentaifox.com", "HentaiFox", "https://hentaifox.com/", "https://hentaifox.com/search/?q=")
        val ALL = listOf(IMHENTAI, HENTAIFOX)
        fun byHost(host: String) = ALL.firstOrNull { host == it.host || host.endsWith("." + it.host) }

        private val GALLERY_LINK = Regex("""<a href="/gallery/(\d+)/">(?!<img)([^<]*)</a>""")
        private val THUMB = Regex("""<a href="/gallery/(\d+)/"><img[^>]*\bdata-src="(https://[^"]+/thumb\.[a-z]+)"""")
        private val HIDDEN = Regex("""id="(load_server|load_dir|load_id|load_pages)" value="([^"]*)"""")
        private val PAGE_TYPES = Regex("""g_th\s*=\s*\$\.parseJSON\('(\{.*?\})'\)""")
        private val PAGE_TYPE = Regex(""""(\d+)":"([a-z])""")
        private val TAG = Regex("""href=['"]/tag/([^/'"]+)/['"]""")
        /** Page thumbnails ".../12t.webp": the page count and each page's format where g_th is missing. */
        private val PAGE_THUMB = Regex("""/(\d+)t\.([a-z]+)["'?]""")
        private val H1 = Regex("""<h1[^>]*>([^<]+)</h1>""")
        private val EXT = mapOf("j" to "jpg", "p" to "png", "g" to "gif", "w" to "webp")

        /** Galleries on a listing page: id, title and the thumbnail (which names the image folder). */
        fun parseList(site: ComicSite, html: String): List<Listed> {
            val thumbs = THUMB.findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
            val titles = GALLERY_LINK.findAll(html).associate { it.groupValues[1] to unescape(it.groupValues[2]).trim().ifBlank { null } }
            return thumbs.keys.map { id -> Listed(id, titles[id], thumbs[id]) }
        }

        /** The comic behind [l], from its gallery page. Null if the page doesn't describe one. */
        fun gallery(site: ComicSite, l: Listed, html: String): MediaItem? {
            val fields = HIDDEN.findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
            val dir = fields["load_dir"] ?: return null
            val loadId = fields["load_id"] ?: return null
            // The thumbnail is on the gallery's own image server: https://m11.imhentai.xxx/033/abc/thumb.jpg
            val base = l.thumb?.substringBeforeLast('/')
                ?: fields["load_server"]?.let { "https://m$it.${site.host}/$dir/$loadId" } ?: return null
            val types = PAGE_TYPES.find(html)?.groupValues?.get(1)
                ?.let { json -> PAGE_TYPE.findAll(json).associate { it.groupValues[1].toInt() to it.groupValues[2] } }
                .orEmpty()
            val thumbExts = PAGE_THUMB.findAll(html).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            val count = types.keys.maxOrNull() ?: fields["load_pages"]?.toIntOrNull() ?: thumbExts.keys.maxOrNull() ?: return null
            if (count <= 0) return null
            val thumbExt = l.thumb?.substringAfterLast('.')?.lowercase() ?: "jpg"
            val pages = (1..count).map { n -> "$base/$n.${types[n]?.let { EXT[it] } ?: thumbExts[n] ?: thumbExt}" }
            // Only the gallery's own tag list (from "Tags:" to the end of its list), not sidebars.
            val tagArea = html.indexOf("Tags:").takeIf { it >= 0 }?.let { i -> html.substring(i, html.indexOf("</ul>", i).takeIf { it > i } ?: minOf(html.length, i + 4000)) } ?: ""
            val tags = TAG.findAll(tagArea).map { it.groupValues[1].replace('-', '_') }.distinct().toList()
            if (tags.isEmpty()) return null // can't be checked, so not shown
            return MediaItem(
                id = "${site.host}/${l.id}",
                sourceId = ComicAdapter.SOURCE_ID,
                type = MediaType.IMAGE,
                title = l.title ?: H1.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim(),
                creator = null,
                tags = (listOf("comic") + tags).distinct(),
                durationSec = null,
                thumbnailUrl = l.thumb,
                pageUrl = site.galleryUrl(l.id),
                publishedAt = null,
                extras = mapOf("media" to pages.first(), "rating" to "e"),
                pages = if (pages.size > 1) pages else emptyList(),
            )
        }

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
    }
}
