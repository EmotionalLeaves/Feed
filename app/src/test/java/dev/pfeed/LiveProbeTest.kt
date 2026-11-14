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
        for (site in listOf(TubeSite.Tnaflix, TubeSite.Porntrex, TubeSite.Youjizz)) for (sec in listOf("new", "top", "milf", "big tits")) probe("${site.label} $sec") {
            val (c, b) = get(site.listUrl(sec, 1))
            val l = site.parseList(b)
            "$c n=${l.size} durs=${l.count { it.durationSec != null }} thumbs=${l.count { it.thumb != null }} first=${l.firstOrNull()?.let { "${it.title?.take(30)} ${it.pageUrl}" }}"
        }
        for (site in listOf(TubeSite.Tnaflix, TubeSite.Porntrex, TubeSite.Youjizz)) probe("${site.label} stream") {
            val v = site.parseList(get(site.listUrl("new", 0)).second).first()
            val st = site.stream(get(v.pageUrl).second)
            "$st ${st?.let { head(it.url) }}"
        }
    }
}
