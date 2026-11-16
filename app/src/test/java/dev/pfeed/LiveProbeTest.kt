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
        val ua = dev.pfeed.di.ArchiveHeaders.BROWSER_UA
        fun g(url: String): Pair<Int, String> = OkHttpClient.Builder().followRedirects(true).build()
            .newCall(Request.Builder().url(url).header("User-Agent", ua).header("Accept", "text/html").build()).execute().use { it.code to it.body?.string().orEmpty() }
        val sites = listOf(
            "https://spankbang.com/new_videos/" to "/video/",
            "https://motherless.com/videos/recent" to "motherless.com/",
            "https://hqporner.com/" to "/hdporn/",
            "https://thisvid.com/latest-updates/" to "/videos/",
            "https://www.txxx.com/latest-updates/" to "/videos/",
            "https://beeg.com/" to "beeg",
            "https://www.porn.com/videos" to "/videos/",
            "https://www.pornhat.com/" to "/video/",
            "https://www.fapster.xxx/" to "/videos/",
            "https://www.porndig.com/" to "/videos/",
            "https://www.pornone.com/newest/" to "/",
            "https://hanime.tv/" to "/videos/hentai/",
            "https://www.ixxx.com/" to "/",
            "https://www.4tube.com/videos" to "/videos/",
            "https://www.fux.com/videos" to "/videos/",
            "https://www.porntube.com/videos" to "/videos/",
            "https://www.pornerbros.com/videos" to "/videos/",
            "https://www.sunporno.com/" to "/videos/",
            "https://www.drtuber.com/" to "/video/",
            "https://www.vjav.com/latest-updates/" to "/videos/",
            "https://www.ok.xxx/" to "/video/",
        )
        for ((u, marker) in sites) probe(u) {
            val (code, body) = g(u)
            val title = Regex("<title[^>]*>([^<]{0,70})").find(body)?.groupValues?.get(1)
            "$code len=${body.length} markers=${Regex(Regex.escape(marker)).findAll(body).count()} title=$title mp4=${body.indexOf(".mp4")} m3u8=${body.indexOf(".m3u8")} cf=${body.contains("cf-chl") || body.contains("Just a moment")}"
        }
    }
}
