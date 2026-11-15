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

    private fun flat(h: String) = h.replace(Regex("\\s+"), " ")
    @Test
    fun probe() {
        val hosts = HashMap<String, Int>()
        var skipped = 0
        for (sub in listOf("gonewild", "RealGirls", "nsfw", "holdthemoan", "cumsluts", "PetiteGoneWild")) {
            val xml = get("https://www.reddit.com/r/$sub/new/.rss?limit=100", "application/atom+xml").second
            val links = Regex("<a href=\"([^\"]+)\">\\[link\\]</a>").findAll(xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")).map { it.groupValues[1] }.toList()
            links.forEach { l -> val h = l.substringAfter("://").substringBefore('/').removePrefix("www."); hosts[h] = (hosts[h] ?: 0) + 1; if (dev.pfeed.source.reddit.RedditRss.mediaFor(l) == null && !l.contains("/gallery/")) skipped++ }
            Thread.sleep(2500)
        }
        probe("link hosts") { hosts.entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key}=${it.value}" } + " unplayable=$skipped" }
        val album = get("https://www.erome.com/explore/new").second.let { Regex("href=\"(https://www.erome.com/a/[A-Za-z0-9]+)\"").find(it)?.groupValues?.get(1) }
        probe("erome album") { album ?: "none" }
        if (album != null) {
            val h = get(album).second
            probe("erome page") { "len=${h.length} videos=${Regex("<source src=\"(https://[^\"]+\\.mp4)\"").findAll(h).map { it.groupValues[1] }.take(3).toList()} imgs=${Regex("data-src=\"(https://s\\d+\\.erome\\.com/[^\"]+)\"").findAll(h).map { it.groupValues[1] }.take(3).toList()} title=${Regex("<title>([^<]{0,60})").find(h)?.groupValues?.get(1)}" }
            val v = Regex("<source src=\"(https://[^\"]+\\.mp4)\"").find(h)?.groupValues?.get(1)
            if (v != null) probe("erome video") { head(v) + " / with referer: " + client.newCall(Request.Builder().url(v).header("Range", "bytes=0-0").header("Referer", "https://www.erome.com/").build()).execute().use { "${it.code} ${it.header("Content-Type")}" } }
        }
    }
}
