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
        val pages = listOf(
            "https://beeg.com/",
            "https://store.externulls.com/facts/tag?id=27173&limit=10&offset=0",
            "https://txxx.com/latest-updates/",
            "https://hqporner.com/hdporn/1",
            "https://www.pornhat.com/",
            "https://fapster.xxx/",
            "https://www.porndig.com/",
            "https://www.anyporn.com/",
            "https://sxyprn.com/",
            "https://www.4tube.com/videos",
            "https://www.fux.com/videos",
            "https://www.pornoxo.com/",
            "https://www.vjav.com/",
            "https://www.hclips.com/",
            "https://www.porn300.com/",
            "https://www.xxxbunker.com/",
            "https://www.pornheed.com/",
            "https://www.drtuber.com/",
            "https://www.sunporno.com/",
            "https://www.nuvid.com/",
            "https://www.vporn.com/",
            "https://www.hellporno.com/",
            "https://www.alphaporno.com/",
            "https://xcafe.com/",
        )
        for (u in pages) probe(u) {
            val (code, body) = get(u, if (u.contains("externulls")) "application/json" else "text/html")
            val title = Regex("<title>([^<]{0,80})").find(body)?.groupValues?.get(1)
            "code=$code len=${body.length} title=$title cf=${body.contains("challenge-platform") || body.contains("cf-chl")} m3u8=${body.split(".m3u8").size - 1} mp4=${body.split(".mp4").size - 1} " +
                "videoLinks=${Regex("href=\"[^\"]*/(?:video|videos|watch|v)/[^\"]+").findAll(body).count()} json=${body.take(160)}"
        }
    }
}
