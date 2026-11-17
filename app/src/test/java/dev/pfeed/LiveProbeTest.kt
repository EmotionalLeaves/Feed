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
        val (_, home) = get("https://www.pornhat.com/")
        val i = home.indexOf("class=\"item thumb-bl thumb-bl-video")
        val c = home.substring(i, minOf(home.length, i + 6000))
        probe("times") { Regex("\\d{1,2}:\\d{2}").findAll(c).take(3).joinToString(" || ") { m -> c.substring((m.range.first - 200).coerceAtLeast(0), m.range.last + 20) } }
        probe("chunk tail") { c.substring(2300, minOf(c.length, 4300)) }
        probe("hls") {
            val site = dev.pfeed.source.tube.TubeSite.PornHat
            val v = site.parseList(home).first()
            val st = site.stream(get(v.pageUrl).second) ?: return@probe "no stream"
            client.newCall(Request.Builder().url(st.url).header("Referer", v.pageUrl).build()).execute().use { "${it.code} ${it.request.url} ${it.body?.string()?.take(400)}" }
        }
    }
}
