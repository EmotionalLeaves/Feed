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
        val (lc, lb) = get("https://xhamster.com/newest/1")
        probe("xh list") { "$lc len=${lb.length} n=${TubeSite.Xhamster.parseList(lb).size} title=${around(lb, "<title", 0, 120)} thumbProps=${lb.indexOf("videoThumbProps")} videos=${lb.indexOf("xhamster.com/videos/")}" }
        val v = TubeSite.Xhamster.parseList(lb).firstOrNull()
        val url = v?.pageUrl ?: Regex("https://xhamster\\.com/videos/[A-Za-z0-9-]+").find(lb)?.value ?: "https://xhamster.com/videos/xhZlgVu"
        probe("xh id") { "${v?.id} url=$url" }
        val (pc, p) = get(url)
        probe("xh page") { "$pc len=${p.length} title=${around(p, "<title", 0, 120)}" }
        probe("xh xplayer") { around(p, "xplayerSettings", 0, 2500) }
        probe("xh sources") { around(p, "\"sources\"", 0, 1500) }
        probe("xh initials parse") { TubeSite.Xhamster.initials(p)?.keys?.take(40)?.toString() ?: ("null; raw=" + around(p, "window.initials", 0, 120)) }
        probe("xh stream") { TubeSite.Xhamster.stream(p).toString() }
    }
}
