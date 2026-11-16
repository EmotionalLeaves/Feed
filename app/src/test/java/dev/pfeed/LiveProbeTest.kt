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
        val c = OkHttpClient.Builder().followRedirects(true).build()
        fun g(url: String, ref: String? = null): Pair<Int, String> = c.newCall(Request.Builder().url(url).header("User-Agent", ua).header("Accept", "text/html").apply { if (ref != null) header("Referer", ref) }.build()).execute().use { it.code to it.body?.string().orEmpty() }
        fun h(url: String, ref: String): String = c.newCall(Request.Builder().url(url).header("User-Agent", ua).header("Referer", ref).header("Range", "bytes=0-0").build()).execute().use { "${it.code} ${it.header("Content-Type")}" }
        val sites = listOf(
            Triple("https://thisvid.com/latest-updates/", Regex("""href="(https://thisvid\.com/videos/[^"]+)""""), "https://thisvid.com/"),
            Triple("https://hqporner.com/", Regex("""href="(/hdporn/[^"]+\.html)""""), "https://hqporner.com"),
            Triple("https://www.drtuber.com/", Regex("""href="(/video/\d+/[^"]+)""""), "https://www.drtuber.com"),
            Triple("https://www.pornone.com/newest/", Regex("""href="(https://pornone\.com/[^"/]+/[^"/]+/\d+/)""""), ""),
            Triple("https://www.ok.xxx/", Regex("""href="(https://ok\.xxx/video/\d+/)""""), ""),
            Triple("https://www.porndig.com/", Regex("""href="(/videos/\d+/[^"]+)""""), "https://www.porndig.com"),
            Triple("https://www.fapster.xxx/", Regex("""href="(https://www\.fapster\.xxx/videos/\d+/[^"]+)""""), ""),
        )
        for ((list, re, prefix) in sites) probe(list) {
            val (lc, lb) = g(list)
            val links = re.findAll(lb).map { it.groupValues[1] }.distinct().toList()
            val first = links.firstOrNull() ?: return@probe "list $lc no links (len ${lb.length}) " + around(lb, "href=\"", 0, 200)
            val url = if (first.startsWith("http")) first else prefix + first
            val (vc, page) = g(url, list)
            val kvs = Regex("""video(?:_alt)?_url\d*:\s*'([^']+)'""").findAll(page).map { it.groupValues[1].take(120) }.toList()
            val mp4 = Regex("""(https?:)?//[^"' ]+\.mp4[^"' ]*""").findAll(page).map { it.value.take(140) }.distinct().take(4).toList()
            val m3u8 = Regex("""https?:[^"' ]+\.m3u8[^"' ]*""").find(page)?.value?.take(140)
            val headTry = (kvs.firstOrNull { it.startsWith("http") } ?: mp4.firstOrNull()?.let { if (it.startsWith("//")) "https:$it" else it })?.let { runCatching { h(it, url) }.getOrNull() }
            "links=${links.size} page=$vc len=${page.length} kvs=$kvs mp4=$mp4 m3u8=$m3u8 head=$headTry lic=${page.contains("license_code")} :: " + around(page, "<video", 0, 300)
        }
    }
}
