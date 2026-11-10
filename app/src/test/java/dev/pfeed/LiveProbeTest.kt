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
        probe("xvideos new/1") { get("https://www.xvideos.com/new/1").let { "${it.first} items=${TubeSite.Xvideos.parseList(it.second).size}" } }
        probe("xvideos search block") { around(get("https://www.xvideos.com/?k=milf&p=0").second, "thumb-block", 100, 1600) }
        probe("pornhub item") { around(get("https://www.pornhub.com/video?o=cm&page=1").second, "data-video-vkey", 150, 1400) }
        val xh = get("https://xhamster.com/newest/1").second
        probe("xhamster markers") { listOf("videoThumbProps", "__NUXT__", "thumbURL", "x-api", "initials", "/videos/", "application/ld+json", "window.__").joinToString(" ") { "$it=${xh.indexOf(it)}" } + " len=${xh.length}" }
        probe("xhamster href") { around(xh, "xhamster.com/videos/", 400, 900) }
        probe("xhamster ld") { around(xh, "application/ld+json", 0, 1500) }
        probe("redtube defs") { around(get("https://www.redtube.com/308514911").second, "mediaDefinitions", 50, 1500) }
        probe("nhentai docs") { get("https://nhentai.net/api/v2/docs", "text/html").let { "${it.first} " + it.second.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").take(3500) } }
        for (u in listOf(
            "https://nhentai.net/api/v2/galleries/search?query=milf&sort=popular",
            "https://nhentai.net/api/v2/galleries?query=milf",
            "https://nhentai.net/api/v2/search?query=milf",
        )) probe("nhentai $u") { get(u, "application/json").let { "${it.first} ${it.second.take(400)}" } }
    }
}
