package dev.pfeed

import dev.pfeed.di.ArchiveHeaders
import dev.pfeed.source.nhentai.NhentaiAdapter
import dev.pfeed.source.tube.TubeSite
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Temporary: validates the new site parsers against the live sites with the app's own request rules. Never fails. */
class LiveProbeTest {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val site = ArchiveHeaders.siteFor(request.url.host)
            if (site == null) chain.proceed(request.newBuilder().header("User-Agent", "PrivateFeed/3.30 (Android)").build())
            else chain.proceed(request.newBuilder().header("User-Agent", ArchiveHeaders.BROWSER_UA)
                .apply { if (request.header("Referer") == null) header("Referer", "https://$site/"); ArchiveHeaders.cookiesFor(site)?.let { header("Cookie", it) } }.build())
        }.build()

    private fun get(url: String, accept: String = "text/html", referer: String? = null): Pair<Int, String> =
        client.newCall(Request.Builder().url(url).header("Accept", accept).apply { if (referer != null) header("Referer", referer) }.build()).execute().use { it.code to it.body?.string().orEmpty() }
    private fun head(url: String): String = client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { "${it.code} ${it.header("Content-Type")}" }
    private fun probe(name: String, block: () -> String) = println("PROBE|$name|" + try { block() } catch (e: Throwable) { "EXC ${e.javaClass.simpleName}: ${e.message?.take(200)}" }.replace('\n', ' '))

    private fun around(s: String, marker: String, before: Int, after: Int): String {
        val i = s.indexOf(marker); if (i < 0) return "<no '$marker'>"
        return s.substring((i - before).coerceAtLeast(0), (i + after).coerceAtMost(s.length))
    }

    @Test
    fun probe() {
        for (site in listOf(dev.pfeed.source.comics.ComicSite.HENTAIERA, dev.pfeed.source.comics.ComicSite.HENTAIROX)) {
            for (term in listOf("", "milf")) probe("${site.host} '$term'") {
                val (code, b) = get(site.listUrl(term, 1))
                val l = dev.pfeed.source.comics.ComicSite.parseList(site, b)
                "$code n=${l.size} titles=${l.count { it.title != null }} first=${l.firstOrNull()}" + if (l.isEmpty()) " :: " + around(b, "/gallery/", 200, 500) else ""
            }
            probe("${site.host} gallery") {
                val l = dev.pfeed.source.comics.ComicSite.parseList(site, get(site.listUrl("", 1)).second).first()
                val (code, html) = get(site.galleryUrl(l.id))
                val g = dev.pfeed.source.comics.ComicSite.gallery(site, l, html)
                "$code pages=${g?.pages?.size} tags=${g?.tags?.take(8)} first=${g?.pages?.firstOrNull()} head=${g?.pages?.firstOrNull()?.let { head(it) }}" + if (g == null) " :: " + around(html, "load_", 100, 400) + " || " + around(html, "Tags", 0, 400) else ""
            }
        }
    }
}
