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
        // ── listings end to end
        for (site in TubeSite.ALL) probe("list ${site.host}") {
            val (code, body) = get(site.listUrl("new", 1), if (site.listUrl("new", 1).contains("api")) "application/json" else "text/html")
            val l = site.parseList(body)
            "$code n=${l.size} first=${l.firstOrNull()?.let { "${it.id} | ${it.title} | ${it.thumb} | ${it.durationSec}s | ${it.pageUrl}" }}"
        }
        for (site in TubeSite.ALL) probe("search ${site.host}") {
            val u = site.listUrl("milf", 1); val l = site.parseList(get(u, if (u.contains("api")) "application/json" else "text/html").second)
            "n=${l.size} thumbs=${l.count { it.thumb != null }} durs=${l.count { it.durationSec != null }} ids=${l.take(3).map { it.id }}"
        }
        // ── streams
        for (site in listOf(TubeSite.Xvideos, TubeSite.Pornhub, TubeSite.Xhamster, TubeSite.Redtube)) probe("stream ${site.host}") {
            val v = site.parseList(get(site.listUrl("new", 1), if (site.listUrl("new", 1).contains("api")) "application/json" else "text/html").second).firstOrNull() ?: return@probe "no videos"
            val (code, page) = get(v.pageUrl)
            val direct = site.stream(page)
            val index = site.streamIndexUrl(page)
            val viaIndex = index?.let { ix ->
                val (c2, j) = get(ix, "application/json", v.pageUrl)
                "idx=$c2 ${j.take(200).replace('\n', ' ')} -> " + runCatching { site.streamFromIndex(Json.parseToJsonElement(j)) }.getOrElse { "EXC $it" }
            }
            val pick = direct ?: index?.let { ix -> runCatching { site.streamFromIndex(Json.parseToJsonElement(get(ix, "application/json", v.pageUrl).second)) }.getOrNull() }
            "page=$code len=${page.length} direct=$direct | $viaIndex | head=${pick?.let { head(it.url) }}"
        }
        probe("xhamster page markers") { val p = get(TubeSite.Xhamster.parseList(get("https://xhamster.com/newest/1").second).first().pageUrl).second
            listOf("xplayerSettings", "window.initials", ".m3u8", "sources", "hls").joinToString(" ") { "$it=${p.indexOf(it)}" } + " | " + around(p, ".m3u8", 300, 200) }
        probe("xvideos page markers") { val p = get(TubeSite.Xvideos.parseList(get("https://www.xvideos.com/new/1").second).first().pageUrl).second
            listOf("setVideoHLS", "setVideoUrlHigh", "html5player").joinToString(" ") { "$it=${p.indexOf(it)}" } + " | " + around(p, "setVideoHLS", 50, 200) }
        // ── nhentai v2
        probe("nhentai openapi") { get("https://nhentai.net/api/v2/openapi.json", "application/json").let { "${it.first} " + Regex("\"(/api/v2[^\"]*)\"").findAll(it.second).map { m -> m.groupValues[1] }.distinct().joinToString(",") } }
        probe("nhentai openapi schemas") { get("https://nhentai.net/api/v2/openapi.json", "application/json").second.let { around(it, "\"schemas\"", 0, 3000) } }
        probe("nhentai list excl") { get(NhentaiAdapter.searchUrl("language:english " + NhentaiAdapter.EXCLUDE, "popular", 1), "application/json").let { "${it.first} ${it.second.take(300)}" } }
        probe("nhentai list sort week") { get(NhentaiAdapter.searchUrl("tag:milf", "popular-week", 1), "application/json").let { "${it.first} ${it.second.take(200)}" } }
        val gid = runCatching { Regex("\"id\":(\\d+)").find(get(NhentaiAdapter.searchUrl("tag:milf", "popular", 1), "application/json").second)!!.groupValues[1] }.getOrDefault("4314731")
        for (u in listOf("https://nhentai.net/api/v2/galleries/$gid", "https://nhentai.net/api/v2/gallery/$gid", "https://nhentai.net/api/gallery/$gid", "https://nhentai.net/api/v2/tags?ids=1,2", "https://nhentai.net/api/v2/tags"))
            probe("nhentai $u") { get(u, "application/json").let { "${it.first} ${it.second.take(700)}" } }
        probe("nhentai images") { val d = get("https://nhentai.net/api/v2/galleries/$gid", "application/json").second
            val items = NhentaiAdapter.parse(Json.parseToJsonElement("{\"result\":[$d]}"))
            val g = items.firstOrNull() ?: return@probe "parse failed"
            "pages=${g.pages.size} tags=${g.tags.take(8)} thumb=${head(g.thumbnailUrl!!)} p1=${head(g.pages.first())} p1url=${g.pages.first()}" }
    }
}
