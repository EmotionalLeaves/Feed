package dev.pfeed

import dev.pfeed.di.ArchiveHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Temporary: which candidate sites answer from CI, and how their pages look. Never fails. */
class LiveProbeTest {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", ArchiveHeaders.BROWSER_UA).build()) }.build()

    private fun get(url: String, accept: String = "text/html"): Pair<Int, String> =
        client.newCall(Request.Builder().url(url).header("Accept", accept).build()).execute().use { it.code to it.body?.string().orEmpty() }
    private fun probe(name: String, block: () -> String) = println("PROBE|$name|" + try { block() } catch (e: Throwable) { "EXC ${e.javaClass.simpleName}: ${e.message?.take(200)}" }.replace('\n', ' '))

    private fun around(s: String, marker: String, before: Int, after: Int): String {
        val i = s.indexOf(marker); if (i < 0) return "<no '$marker'>"
        return s.substring((i - before).coerceAtLeast(0), (i + after).coerceAtMost(s.length))
    }

    private fun links(b: String, kind: String) = Regex("href=\"([^\"]*/$kind/[^\"]*)\"[^>]*>([^<]{0,40})").findAll(b).map { it.groupValues[1] + "=" + it.groupValues[2].trim() }.distinct().take(15).joinToString(" ; ")

    @Test
    fun probe() {
        val site = dev.pfeed.source.tube.TubeSite.PornHat
        for ((sec, page) in listOf("new" to 0, "new" to 1, "top" to 0, "top" to 1, "milf" to 0, "milf" to 1, "big tits" to 0)) probe("list $sec $page") {
            val u = site.listUrl(sec, page)
            val (code, body) = get(u)
            val l = site.parseList(body)
            "$u code=$code n=${l.size} durs=${l.count { it.durationSec != null }} creators=${l.count { it.creator != null }} thumbs=${l.count { it.thumb != null }} first=${l.firstOrNull()}"
        }
        probe("stream") {
            val v = site.parseList(get(site.listUrl("new", 0)).second).first()
            val (c, page) = get(v.pageUrl)
            val st = site.stream(page)
            val h = st?.let { s -> client.newCall(Request.Builder().url(s.url).header("Range", "bytes=0-0").header("Referer", v.pageUrl).build()).execute().use { "${it.code} ${it.header("Content-Type")} ${it.request.url.host}" } }
            "page=$c stream=$st head=$h tags=${site.pageTags(page)}"
        }
    }
}
