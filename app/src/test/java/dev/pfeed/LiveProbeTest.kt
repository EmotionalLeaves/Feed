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

    @Test
    fun probe() {
        for (site in TubeSite.ALL) {
            for (section in listOf("new", "milf")) probe("${site.label} $section") {
                val (code, body) = get(site.listUrl(section, 0), if (site is TubeSite.Redtube || site is TubeSite.Eporner) "application/json" else "text/html")
                val list = site.parseList(body)
                if (list.isEmpty()) return@probe "$code len=${body.length} items=0 title=${Regex("<title>([^<]*)").find(body)?.groupValues?.get(1)} snippet=${body.take(300)}"
                val v = list.first()
                Thread.sleep(1500)
                val (pc, page) = get(v.pageUrl)
                val stream = site.stream(page)
                val index = site.streamIndexUrl(page)
                val fromIndex = index?.let { runCatching { site.streamFromIndex(Json.parseToJsonElement(get(it, "application/json", v.pageUrl).second)) }.getOrNull() }
                "$code items=${list.size} first=${v.id} '${v.title?.take(30)}' dur=${v.durationSec} thumb=${v.thumb?.let { head(it) }} | page $pc len=${page.length} stream=${stream?.url?.take(90)} -> ${stream?.let { head(it.url) }} | index=${index?.take(60)} -> ${fromIndex?.url?.take(80)} ${fromIndex?.let { head(it.url) }}" +
                    if (stream == null && fromIndex == null) " | pageTitle=${Regex("<title>([^<]*)").find(page)?.groupValues?.get(1)} hasMediaDefs=${page.contains("mediaDefinitions")} hasInitials=${page.contains("window.initials")} hasHtml5=${page.contains("html5player")}" else ""
            }
        }
        probe("nhentai") {
            val (code, body) = get("https://nhentai.net/api/galleries/search?query=" + java.net.URLEncoder.encode("tag:milf language:english ${NhentaiAdapter.EXCLUDE}", "UTF-8") + "&sort=popular", "application/json")
            val items = runCatching { NhentaiAdapter.parse(Json.parseToJsonElement(body)) }.getOrDefault(emptyList())
            if (items.isEmpty()) return@probe "$code len=${body.length} ${body.take(300)}"
            val g = items.first()
            "$code items=${items.size} '${g.title}' pages=${g.pages.size} thumb=${head(g.thumbnailUrl!!)} p1=${head(g.pages.first())} p2=${head(g.pages[1])}"
        }
    }
}
