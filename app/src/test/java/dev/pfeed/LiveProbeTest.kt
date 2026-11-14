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

    private val plain = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private fun code(url: String, ua: String): String = plain.newCall(Request.Builder().url(url).header("User-Agent", ua).header("Accept", "application/atom+xml,text/xml;q=0.9,*/*;q=0.8").build()).execute().use { r ->
        "${r.code}${r.header("x-ratelimit-remaining")?.let { "/rem=$it" } ?: ""}"
    }

    @Test
    fun probe() {
        val ua = "PrivateFeed/4.1.0 (Android)"
        probe("sub search rss") { plain.newCall(Request.Builder().url("https://old.reddit.com/subreddits/search.rss?q=milf&include_over_18=on&limit=25").header("User-Agent", ua).build()).execute().use { r ->
            val b = r.body!!.string(); "${r.code} len=${b.length} " + Regex("<title>([^<]+)</title>").findAll(b).take(12).joinToString(" | ") { it.groupValues[1] } + " :: " + around(b, "<entry>", 0, 700) } }
        probe("sub search json") { plain.newCall(Request.Builder().url("https://old.reddit.com/subreddits/search.json?q=milf&include_over_18=on&limit=5").header("User-Agent", ua).build()).execute().use { r -> "${r.code} ${r.body!!.string().take(300)}" } }
        for (h in listOf("xhamster.com", "xhamster2.com", "xhamster3.com", "xhamster.desi", "xhamster19.com", "xhamster.one", "ru.xhamster.com"))
            probe("xh $h") { get("https://$h/newest/1").let { (c, b) -> "$c len=${b.length} thumbProps=${b.indexOf("videoThumbProps")} n=${TubeSite.Xhamster.parseList(b).size}" } }
    }
}
