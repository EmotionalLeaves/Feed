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
        val tl = get("https://www.tnaflix.com/new/1").second
        probe("tna block") { flat(around(tl, "data-vid=", 0, 1800)) }
        val th = Regex("href=\"(https://www.tnaflix.com/[^\"]+/video\\d+)\"").find(tl)!!.groupValues[1]
        val tp = get(th).second
        probe("tna mp4s") { Regex("https?:[^\"' ]+\\.mp4[^\"' ]*").findAll(tp).map { it.value.take(130) }.distinct().take(8).toList().toString() }
        probe("tna source") { flat(around(tp, "<source", 0, 700)) }
        val pl = get("https://www.porntrex.com/latest-updates/").second
        probe("ptx block") { flat(around(pl, "data-item-id=", 0, 2200)) }
        val ph = Regex("href=\"(https://www.porntrex.com/video/[^\"]+)\"").find(pl)!!.groupValues[1]
        val pp = get(ph).second
        probe("ptx flashvars") { flat(around(pp, "flashvars", 0, 1600)) }
        val yl = get("https://www.youjizz.com/newest-clips/1.html").second
        probe("yj block") { flat(around(yl, "class=\"video-thumb\"", 0, 1800)) }
        val yh = "https://www.youjizz.com" + Regex("href=\"(/videos/[^\"]+\\.html)\"").find(yl)!!.groupValues[1]
        probe("yj enc") { flat(around(get(yh).second, "dataEncodings", 0, 900)) }
    }
}
