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
        val (xc, xb) = get("https://www.xnxx.com/new/0")
        probe("xnxx new") { "$xc len=${xb.length} xvParse=${TubeSite.Xvideos.parseList(xb).size} " + around(xb, "thumb-block", 100, 900) }
        probe("xnxx search") { get("https://www.xnxx.com/search/milf/0").let { "${it.first} n=${TubeSite.Xvideos.parseList(it.second).size}" } }
        probe("xnxx page") { val u = Regex("href=\"(/video-[^\"]+)\"").find(xb)?.groupValues?.get(1) ?: return@probe "no href"
            val (c, p) = get("https://www.xnxx.com$u"); "$c " + TubeSite.Xvideos.stream(p) + " | " + around(p, "setVideoHLS", 50, 160) }
        for (u in listOf(
            "https://www.youporn.com/api/webmasters/search/?query=milf&page=1",
            "https://www.youporn.com/api/webmasters/listing/?page=1",
            "https://www.youporn.com/api/webmasters/search/?page=1",
            "https://api.tube8.com/api.php?action=searchVideos&output=json&search=milf&page=1",
            "https://api.tube8.com/api.php?action=getVideosList&output=json&page=1",
            "https://www.pornhub.com/webmasters/search?search=milf&page=1",
            "https://www.pornhub.com/webmasters/video_by_id?id=6acf3013d23d9",
            "https://www.youporn.com/api/webmasters/video_by_id/?id=1",
        )) probe("api $u") { get(u, "application/json").let { "${it.first} ${it.second.take(700)}" } }
        probe("youporn html") { get("https://www.youporn.com/browse/time/").let { "${it.first} len=${it.second.length} " + around(it.second, "data-video-id", 200, 900) } }
        probe("tube8 html") { get("https://www.tube8.com/latest/").let { "${it.first} len=${it.second.length} " + around(it.second, "video-", 200, 900) } }
    }
}
