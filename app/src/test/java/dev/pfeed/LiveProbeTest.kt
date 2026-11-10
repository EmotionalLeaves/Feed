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
        for (site in TubeSite.ALL) for (sec in listOf("new", "milf")) probe("list ${site.host} $sec") {
            val u = site.listUrl(sec, 1)
            val (code, body) = get(u, if (u.contains("api")) "application/json" else "text/html")
            val l = site.parseList(body)
            "$code n=${l.size} thumbs=${l.count { it.thumb != null }} durs=${l.count { it.durationSec != null }} first=${l.firstOrNull()?.let { "${it.id} | ${it.title?.take(40)} | ${it.pageUrl}" }}"
        }
        for (site in listOf(TubeSite.Xnxx, TubeSite.Youporn, TubeSite.Tube8)) probe("stream ${site.host}") {
            val v = site.parseList(get(site.listUrl("milf", 1)).second).firstOrNull() ?: return@probe "no videos"
            val (code, page) = get(v.pageUrl)
            val direct = site.stream(page); val index = site.streamIndexUrl(page)
            val pick = direct ?: index?.let { ix -> runCatching { site.streamFromIndex(Json.parseToJsonElement(get(ix, "application/json", v.pageUrl).second)) }.getOrNull() }
            "page=$code len=${page.length} direct=$direct index=$index head=${pick?.let { head(it.url) }} defs=${page.indexOf("mediaDefinitions")} " + around(page, "mediaDefinition", 0, 300)
        }
    }
}
