package dev.pfeed.source.photos

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photo sets: the studios' own picture sets of their scenes (BabeSource), each a gallery you swipe
 * through like a comic, credited to its performers and studio. Entry = "newest" / "top-rated" /
 * "most-viewed". Each set's page is read before it's shown, for its pictures and its tags.
 */
@Singleton
class PhotoSetsAdapter @Inject constructor(
    internal val http: HttpJson,
) : CachingAdapter() {
    override val id = SOURCE_ID
    override val displayName = "Photo sets"
    override val requiresAuth = false
    override val maxConcurrency = 2
    override val entriesPerExtend = 1
    override val entryTimeoutMs = 30_000L

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun entries(): List<String> = BabeSource.SECTIONS

    /** Token = "page:offset": a listing page holds ~76 sets, read [CHUNK] at a time. */
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        val page = token?.substringBefore(':')?.toIntOrNull() ?: 1
        val offset = token?.substringAfter(':', "0")?.toIntOrNull() ?: 0
        val listed = BabeSource.parseList(http.getRaw(id, BabeSource.listUrl(entry, page)))
        val items = readAll(listed.drop(offset).take(CHUNK))
        val next = when {
            listed.isEmpty() -> null
            offset + CHUNK < listed.size -> "$page:${offset + CHUNK}"
            page >= MAX_PAGES -> null
            else -> "${page + 1}:0"
        }
        return EntryPage(items, next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = cachedSearch(query, cursor) {
        val q = query.trim()
        if (q.isEmpty()) return@cachedSearch emptyList()
        val listed = attempt { BabeSource.parseList(http.getRaw(id, BabeSource.searchUrl(q, 1))) }.getOrDefault(emptyList())
        (readAll(listed.take(SEARCH_LIMIT)) + poolMatches(query)).distinctBy { it.key }
    }

    /** The newest sets (first [n] of the first page), for Discover. */
    suspend fun newest(n: Int = CHUNK): List<MediaItem> =
        attempt { readAll(BabeSource.parseList(http.getRaw(id, BabeSource.listUrl("newest", 1))).take(n)) }.getOrDefault(emptyList())

    private val gate = Semaphore(PAGE_CONCURRENCY)

    private suspend fun readAll(listed: List<BabeSource.Listed>): List<MediaItem> = coroutineScope {
        listed.map { l ->
            async { gate.withPermit { attempt { BabeSource.gallery(l, http.getRaw(id, l.url)) }.getOrNull() } }
        }.awaitAll().filterNotNull().filter(MinorsFilter::allows)
    }

    companion object {
        const val SOURCE_ID = "photos"
        private const val CHUNK = 20
        private const val MAX_PAGES = 30
        private const val PAGE_CONCURRENCY = 4
        private const val SEARCH_LIMIT = 16
    }
}

/** BabeSource's markup, as served in November 2026. */
object BabeSource {
    const val HOST = "babesource.com"
    val SECTIONS = listOf("newest", "top-rated", "most-viewed")

    data class Listed(val id: String, val url: String, val title: String?, val thumb: String?, val count: Int?)

    fun listUrl(section: String, page: Int) = "https://$HOST/$section/" + if (page > 1) "page$page.html" else ""
    fun searchUrl(query: String, page: Int) = "https://$HOST/search/${java.net.URLEncoder.encode(query.lowercase().trim(), "UTF-8").replace("+", "-")}/$page/"

    private val CARD = Regex("""<a class="main-content__card-link" href="(https://babesource\.com/galleries/[a-z0-9-]+-(\d+)\.html)">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val IMG = Regex("""<img alt="([^"]*)"[^>]*\bsrc="(https://[^"]+\.jpg)"""")
    private val COUNT = Regex("""bs-photo-count">(?:<i[^>]*></i>)?\s*(\d+)""")
    private val PHOTO = Regex("""<a [^>]*class="[^"]*slideshowGalleryImage[^"]*" href="(https://[^"]+\.jpg)"(?: data-msrc="(https://[^"]+)")?""")
    private val KEYWORDS = Regex("""<meta name="keywords" content="([^"]*)"""")
    private val H1 = Regex("""<h1[^>]*>([^<]+)</h1>""")

    fun parseList(html: String): List<Listed> {
        val cards = CARD.findAll(html).toList()
        return cards.mapIndexed { i, m ->
            val img = IMG.find(m.groupValues[3])
            val after = html.substring(m.range.last, if (i + 1 < cards.size) cards[i + 1].range.first else minOf(html.length, m.range.last + 600))
            Listed(m.groupValues[2], m.groupValues[1], img?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifEmpty { null }, img?.groupValues?.get(2), COUNT.find(after)?.groupValues?.get(1)?.toIntOrNull())
        }.distinctBy { it.id }
    }

    /** The set behind [l], from its page: every picture (the 1080 px copy where there is one) and its tags. */
    fun gallery(l: Listed, html: String): MediaItem? {
        val pages = PHOTO.findAll(html).map { it.groupValues[2].ifEmpty { it.groupValues[1] } }.distinct().toList()
        if (pages.isEmpty()) return null
        val title = l.title ?: H1.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()
        // "Liz Jordan - Deeper": the performers, then the studio.
        val studio = title?.substringAfterLast(" - ", "")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 }
        val keywords = KEYWORDS.find(html)?.groupValues?.get(1)?.split(',')?.map { unescape(it).trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return MediaItem(
            id = "$HOST/${l.id}",
            sourceId = PhotoSetsAdapter.SOURCE_ID,
            type = MediaType.IMAGE,
            title = title,
            creator = studio,
            tags = (listOf("photo set") + keywords.map { it.lowercase() }).distinct().take(20),
            durationSec = null,
            thumbnailUrl = l.thumb,
            pageUrl = l.url,
            publishedAt = null,
            extras = mapOf("media" to pages.first(), "rating" to "e"),
            pages = if (pages.size > 1) pages else emptyList(),
        )
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
}
