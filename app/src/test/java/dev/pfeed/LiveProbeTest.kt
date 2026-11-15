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
        val ua = "PrivateFeed/4.31 (Android)"
        fun r(url: String, cookie: String?): String {
            val req = Request.Builder().url(url).header("User-Agent", ua).apply { if (cookie != null) header("Cookie", cookie) }.build()
            return OkHttpClient().newCall(req).execute().use { "${it.code} entries=${(it.body?.string().orEmpty()).split("<entry>").size - 1}" }
        }
        for (u in listOf(
            "https://www.reddit.com/search/.rss?q=milf&sort=relevance&t=all&include_over_18=on&limit=100",
            "https://www.reddit.com/search.rss?q=milf&include_over_18=on&limit=100",
            "https://www.reddit.com/r/gonewild+milf/search/.rss?q=milf&restrict_sr=on&include_over_18=on&limit=100",
        )) for (c in listOf(null, "over18=1")) { probe("reddit ${u.substringAfter(".com").take(60)} cookie=$c") { r(u, c) }; Thread.sleep(6500) }
    }
}
