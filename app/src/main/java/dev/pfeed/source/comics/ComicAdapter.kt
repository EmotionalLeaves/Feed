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
class ComicAdapter @Inject constructor(
    internal val http: HttpJson,
    private val blocks: dev.pfeed.data.repo.BlockRepository,
) : CachingAdapter() {
    private suspend fun hiddenHosts(): Set<String> =
        runCatching { blocks.current().origins }.getOrDefault(emptySet()).filter { it.startsWith("comics:") }.mapTo(HashSet()) { it.removePrefix("comics:") }

    override val id = SOURCE_ID
    override val displayName = "Comic sites"
    override val requiresAuth = false
    override val maxConcurrency = 2
    override val entriesPerExtend = 2
    override val entryTimeoutMs = 30_000L
    override fun groupOf(entry: String) = entry.substringBefore('|')

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> {
        val hidden = hiddenHosts()
        return ComicSite.ALL.filter { it.host !in hidden }.flatMap { site -> (listOf("") + SourceCatalog.comicTags).map { "${site.host}|$it" } } +
            (if (EightMuses.HOST in hidden) emptyList() else EightMuses.artists(http, id).map { "${EightMuses.HOST}|$it" })
    }

    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        if (entry.startsWith(EightMuses.HOST + "|")) {
            val offset = token?.toIntOrNull() ?: 0
            val (items, next) = EightMuses.page(http, id, entry.substringAfter('|'), offset)
            return EntryPage(items.filter(MinorsFilter::allows), next?.toString())
        }
        val site = ComicSite.byHost(entry.substringBefore('|')) ?: return EntryPage(emptyList(), null)
        val tag = entry.substringAfter('|', "")
        val page = token?.toIntOrNull() ?: 1
        val listed = ComicSite.parseList(site, http.getRaw(id, site.listUrl(tag.replace('-', ' '), page)))
        // The newest listing is newest-first; date it by position so Latest can place it.
        val now = java.time.Instant.now()
        val items = readAll(site, listed).mapIndexed { i, it ->
            if (tag.isEmpty() && it.publishedAt == null) it.copy(publishedAt = now.minusSeconds(((page * 30L) + i) * 600L), extras = it.extras + (dev.pfeed.domain.model.MediaItem.APPROX_DATE to "1")) else it
        }
        val next = if (listed.isEmpty() || page >= MAX_PAGES) null else (page + 1).toString()
        return EntryPage(items, next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        val hidden = hiddenHosts()
        val found = coroutineScope {
            ComicSite.ALL.filter { it.host !in hidden }.map { site ->
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
                    attempt { ComicSite.gallery(site, l, http.getRaw(id, site.galleryUrl(l.id)))?.let { g -> verified(site, g) } }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull().filter(MinorsFilter::allows)
    }

    companion object {
        const val SOURCE_ID = "comics"
        private const val MAX_PAGES = 20
        private const val PAGE_CONCURRENCY = 4
        private const val SEARCH_LIMIT = 8
    }
}

/**
 * For a site whose page format is assumed (see ComicSite.pageExt): check the first page exists, and
 * fall back to JPEG pages if it doesn't. A gallery whose first page loads either way isn't shown.
 */
private suspend fun ComicAdapter.verified(site: ComicSite, g: MediaItem): MediaItem? {
    if (!site.assumesPageExt) return g
    if (http.reachable(g.pages.firstOrNull() ?: return g)) return g
    val jpg = g.pages.map { it.substringBeforeLast('.') + ".jpg" }
    if (!http.reachable(jpg.first())) return null
    return g.copy(pages = jpg, extras = g.extras + ("media" to jpg.first()))
}

/** One site on the shared gallery engine. */
class ComicSite private constructor(
    val host: String,
    val label: String,
    private val listBase: String,
    private val searchBase: String,
    /** The format every full page comes in, when the site serves them all the same (HentaiRox: webp). */
    private val pageExt: String? = null,
) {
    val assumesPageExt: Boolean get() = pageExt != null
    data class Listed(val id: String, val title: String?, val thumb: String?)

    fun listUrl(term: String, page: Int): String =
        if (term.isBlank()) "$listBase?page=$page" else "$searchBase${URLEncoder.encode(term, "UTF-8")}&page=$page"

    fun galleryUrl(id: String) = "https://$host/gallery/$id/"

    companion object {
        val IMHENTAI = ComicSite("imhentai.xxx", "IMHentai", "https://imhentai.xxx/", "https://imhentai.xxx/search/?key=")
        val HENTAIFOX = ComicSite("hentaifox.com", "HentaiFox", "https://hentaifox.com/", "https://hentaifox.com/search/?q=")
        val HENTAIERA = ComicSite("hentaiera.com", "HentaiEra", "https://hentaiera.com/", "https://hentaiera.com/search/?key=")
        val HENTAIROX = ComicSite("hentairox.com", "HentaiRox", "https://hentairox.com/", "https://hentairox.com/search/?q=", pageExt = "webp")
        val ALL = listOf(IMHENTAI, HENTAIFOX, HENTAIERA, HENTAIROX)
        private val EIGHT_MUSES_LABEL = ComicSite(EightMuses.HOST, "8muses", "", "")
        fun byHost(host: String) = (ALL + EIGHT_MUSES_LABEL).firstOrNull { host == it.host || host.endsWith("." + it.host) }

        private val GALLERY_LINK = Regex("""<a href="/gallery/(\d+)/">(?!<img)([^<]*)</a>""")
        private val THUMB = Regex("""<a href="/gallery/(\d+)/"><img[^>]*\bdata-src="(https://[^"]+/thumb\.[a-z]+)"""")
        /** Card layouts: <a class="…cover" href="/gallery/1/" aria-label="…"> <img src="…/thumb.jpg"> */
        private val THUMB_CARD = Regex("""href="/gallery/(\d+)/"[^>]*>\s*<img[^>]*\bsrc="(https://[^"]+/thumb\.[a-z]+)"""")
        private val HIDDEN = Regex("""id="(load_server|load_dir|load_id|load_pages)" value="([^"]*)"""")
        private val PAGE_TYPES = Regex("""g_th\s*=\s*\$\.parseJSON\('(\{.*?\})'\)""")
        private val PAGE_TYPE = Regex(""""(\d+)":"([a-z])""")
        private val TAG = Regex("""href=['"]/tag/([^/'"]+)/['"]""")
        /** Page thumbnails ".../12t.webp": the page count and each page's format where g_th is missing. */
        private val PAGE_THUMB = Regex("""/(\d+)t\.([a-z]+)["'?]""")
        private val H1 = Regex("""<h1[^>]*>([^<]+)</h1>""")
        /** "167 pages" in the gallery's details (where only the first page thumbnails are in the page). */
        private val PAGES_TEXT = Regex("""\b(\d{1,4})\s+pages\b""", RegexOption.IGNORE_CASE)
        private val EXT = mapOf("j" to "jpg", "p" to "png", "g" to "gif", "w" to "webp")

        /** Galleries on a listing page: id, title and the thumbnail (which names the image folder). */
        fun parseList(site: ComicSite, html: String): List<Listed> {
            val thumbs = (THUMB.findAll(html) + THUMB_CARD.findAll(html)).associate { it.groupValues[1] to it.groupValues[2] }
            val titles = GALLERY_LINK.findAll(html).associate { it.groupValues[1] to unescape(it.groupValues[2]).trim().ifBlank { null } }
            return thumbs.keys.map { id -> Listed(id, titles[id], thumbs[id]) }
        }

        /** The comic behind [l], from its gallery page. Null if the page doesn't describe one. */
        fun gallery(site: ComicSite, l: Listed, html: String): MediaItem? {
            val fields = HIDDEN.findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
            // The thumbnail is on the gallery's own image server: https://m11.imhentai.xxx/033/abc/thumb.jpg
            val base = l.thumb?.substringBeforeLast('/')
                ?: fields["load_server"]?.let { srv -> fields["load_dir"]?.let { d -> fields["load_id"]?.let { "https://m$srv.${site.host}/$d/$it" } } }
                ?: return null
            val types = PAGE_TYPES.find(html)?.groupValues?.get(1)
                ?.let { json -> PAGE_TYPE.findAll(json).associate { it.groupValues[1].toInt() to it.groupValues[2] } }
                .orEmpty()
            val thumbExts = PAGE_THUMB.findAll(html).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            val count = types.keys.maxOrNull() ?: fields["load_pages"]?.toIntOrNull()
                ?: (if (site.pageExt != null) PAGES_TEXT.find(html)?.groupValues?.get(1)?.toIntOrNull() else null)
                ?: thumbExts.keys.maxOrNull() ?: return null
            if (count <= 0) return null
            val thumbExt = l.thumb?.substringAfterLast('.')?.lowercase() ?: "jpg"
            val pages = (1..count).map { n -> "$base/$n.${types[n]?.let { EXT[it] } ?: site.pageExt ?: thumbExts[n] ?: thumbExt}" }
            // Only the gallery's own tag list (from "Tags:" to the end of its list), not sidebars.
            val tagArea = html.indexOf("Tags:").takeIf { it >= 0 }?.let { i -> html.substring(i, html.indexOf("</ul>", i).takeIf { it > i } ?: minOf(html.length, i + 4000)) } ?: ""
            // Otherwise: the gallery's own details, from its title down to its page thumbnails.
            val detailArea = H1.find(html)?.range?.first?.let { i -> html.substring(i, html.indexOf("/1t.", i).takeIf { it > i } ?: minOf(html.length, i + 8000)) } ?: ""
            val tags = (TAG.findAll(tagArea).toList().ifEmpty { TAG.findAll(detailArea).toList() })
                .map { it.groupValues[1].replace('-', '_') }.distinct().toList()
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


/**
 * 8muses: western porn comics, organised artist → series → issue. Each issue (an album of pictures)
 * becomes one comic; its pages are the full-size versions of the picture thumbnails. Entries are
 * the artists on the front page; each page of an entry walks a few of that artist's albums.
 * An issue without tags isn't shown (it couldn't be checked).
 */
internal object EightMuses {
    const val HOST = "comics.8muses.com"
    private const val BASE = "https://$HOST"
    private const val ALBUMS_PER_PAGE = 4
    private const val ISSUES_PER_ALBUM = 3

    private val TILE = Regex("""<a class="c-tile[^"]*" href="(/porn/album/[^"?]+)"""")
    private val PICTURE_THUMB = Regex("""href="/porn/picture/[^"]+"[^>]*>\s*<div class="image"><img[^>]*\bsrc="/image/th/([^"]+)"""")
    private val TAG = Regex("""href="/porn/tag/([^"/]+)"""")
    private val H1 = Regex("""<h1 class="page-title">([^<]+)</h1>""")

    @Volatile private var artistsCache: Pair<Long, List<String>>? = null

    /** Artist album paths from the front page (refreshed every 6 h). */
    suspend fun artists(http: HttpJson, sourceId: String): List<String> {
        artistsCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 6 * 3_600_000L && list.isNotEmpty()) return list }
        val list = attempt { TILE.findAll(http.getRaw(sourceId, "$BASE/")).map { it.groupValues[1] }.distinct().toList() }.getOrDefault(emptyList())
        if (list.isNotEmpty()) artistsCache = System.currentTimeMillis() to list
        return list
    }

    /** One page of an artist: issues from up to [ALBUMS_PER_PAGE] of its albums, and the next offset. */
    suspend fun page(http: HttpJson, sourceId: String, path: String, offset: Int): Pair<List<MediaItem>, Int?> {
        val html = http.getRaw(sourceId, BASE + path)
        issue(path, html)?.let { return listOf(it) to null }
        val subs = children(path, html)
        val slice = subs.drop(offset).take(ALBUMS_PER_PAGE)
        val items = slice.flatMap { sub -> attempt { issuesUnder(http, sourceId, sub, depth = 2) }.getOrDefault(emptyList()) }
        val next = (offset + ALBUMS_PER_PAGE).takeIf { it < subs.size }
        return items to next
    }

    private suspend fun issuesUnder(http: HttpJson, sourceId: String, path: String, depth: Int): List<MediaItem> {
        val html = http.getRaw(sourceId, BASE + path)
        issue(path, html)?.let { return listOf(it) }
        if (depth <= 0) return emptyList()
        return children(path, html).take(ISSUES_PER_ALBUM).flatMap { attempt { issuesUnder(http, sourceId, it, depth - 1) }.getOrDefault(emptyList()) }
    }

    private fun children(path: String, html: String) =
        TILE.findAll(html).map { it.groupValues[1] }.filter { it.startsWith("$path/") }.distinct().toList()

    /** The comic on an issue page, or null if the page is a list of albums (or has no tags). */
    fun issue(path: String, html: String): MediaItem? {
        val thumbs = PICTURE_THUMB.findAll(html).map { it.groupValues[1] }.toList()
        if (thumbs.isEmpty()) return null
        val tags = TAG.findAll(html).map { it.groupValues[1].replace('-', '_').lowercase() }.distinct().toList()
        if (tags.isEmpty()) return null
        val pages = thumbs.map { "$BASE/image/fl/$it" }
        val series = path.removePrefix("/porn/album/").split('/')
        return MediaItem(
            id = "$HOST/${series.joinToString("/")}",
            sourceId = ComicAdapter.SOURCE_ID,
            type = MediaType.IMAGE,
            title = (H1.find(html)?.groupValues?.get(1)?.trim()?.let { t -> if (series.size >= 2) series[series.size - 2].replace('-', ' ') + " – " + t else t }),
            creator = series.firstOrNull()?.replace('-', ' '),
            tags = (listOf("comic") + tags).distinct(),
            durationSec = null,
            thumbnailUrl = "$BASE/image/th/${thumbs.first()}",
            pageUrl = BASE + path,
            publishedAt = null,
            extras = mapOf("media" to pages.first(), "rating" to "e"),
            pages = if (pages.size > 1) pages else emptyList(),
        )
    }
}
