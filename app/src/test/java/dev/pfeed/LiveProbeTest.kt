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

    @Test
    fun probe() {
        probe("beeg main") { val (c, b) = get("https://store.externulls.com/facts/tag?id=27173&limit=3&offset=0", "application/json"); "code=$c " + b.take(3500) }
        probe("beeg slug milf") { val (c, b) = get("https://store.externulls.com/facts/tag?slug=milf&limit=2&offset=0", "application/json"); "code=$c " + b.take(600) }
        probe("beeg tag info") { val (c, b) = get("https://store.externulls.com/tag/facts/tags?get_original=true&slug=index", "application/json"); "code=$c " + b.take(600) }
        probe("beeg js") {
            val (_, home) = get("https://beeg.com/")
            Regex("src=\"([^\"]+\\.js)\"").findAll(home).map { it.groupValues[1] }.joinToString(" ")
        }
        for (u in listOf("https://www.drtuber.com/", "https://www.nuvid.com/", "https://www.sunporno.com/", "https://www.pornhat.com/", "https://www.porndig.com/")) probe("list $u") {
            val (_, b) = get(u)
            val link = Regex("href=\"([^\"]*/(?:video|videos|watch|v)/[^\"]+)\"").find(b)?.groupValues?.get(1)
            "link=$link " + around(b, link ?: "zzz", 600, 900)
        }
        for (u in listOf("https://www.drtuber.com/", "https://www.nuvid.com/", "https://www.pornhat.com/")) probe("page $u") {
            val (_, b) = get(u)
            var link = Regex("href=\"([^\"]*/(?:video|videos|watch|v)/[^\"]+)\"").find(b)?.groupValues?.get(1) ?: return@probe "no link"
            if (link.startsWith("/")) link = u.trimEnd('/') + link
            val (c, page) = get(link)
            "url=$link code=$c len=${page.length} m3u8=${around(page, ".m3u8", 300, 100)} || mp4=${around(page, ".mp4", 300, 100)} || tags=${around(page, "tags", 100, 600)}"
        }
    }
}
