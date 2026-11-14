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
        val pages = mapOf(
            "motherless" to "https://motherless.com/videos/recent?page=1",
            "spankbang" to "https://spankbang.com/new_videos/",
            "youjizz" to "https://www.youjizz.com/newest-clips/1.html",
            "porntrex" to "https://www.porntrex.com/latest-updates/",
            "hqporner" to "https://hqporner.com/hdporn/1",
            "tnaflix" to "https://www.tnaflix.com/new/1",
            "pornone" to "https://pornone.com/newest/",
            "4tube" to "https://www.4tube.com/videos",
        )
        for ((name, url) in pages) {
            val got = runCatching { get(url) }
            if (got.isFailure) { probe(name) { "EXC ${got.exceptionOrNull()?.message}" }; continue }
            val (c, h) = got.getOrThrow()
            probe("$name list") { "$c len=${h.length} title=${Regex("<title>([^<]{0,60})").find(h)?.groupValues?.get(1)}" }
            val href = when (name) {
                "motherless" -> Regex("href=\"(https://motherless.com/[A-Z0-9]{7})\"").find(h)?.groupValues?.get(1)
                "spankbang" -> Regex("href=\"(/[a-z0-9]+/video/[^\"]+)\"").find(h)?.groupValues?.get(1)?.let { "https://spankbang.com$it" }
                "youjizz" -> Regex("href=\"(/videos/[^\"]+\\.html)\"").find(h)?.groupValues?.get(1)?.let { "https://www.youjizz.com$it" }
                "porntrex" -> Regex("href=\"(https://www.porntrex.com/video/[^\"]+)\"").find(h)?.groupValues?.get(1)
                "hqporner" -> Regex("href=\"(/hdporn/\\d+-[^\"]+\\.html)\"").find(h)?.groupValues?.get(1)?.let { "https://hqporner.com$it" }
                "tnaflix" -> Regex("href=\"(https://www.tnaflix.com/[^\"]+/video\\d+)\"").find(h)?.groupValues?.get(1)
                "pornone" -> Regex("href=\"(https://pornone.com/[^\"]+/\\d+/)\"").find(h)?.groupValues?.get(1)
                else -> Regex("href=\"(https://www.4tube.com/videos/\\d+/[^\"]+)\"").find(h)?.groupValues?.get(1)
            }
            probe("$name item") { "$href :: " + flat(around(h, href?.substringAfter(".com")?.take(30) ?: "zzzz", 400, 700)) }
            if (href != null) {
                val pg = runCatching { get(href) }
                if (pg.isFailure) { probe("$name page") { "EXC ${pg.exceptionOrNull()?.message}" }; continue }
                val (c2, p) = pg.getOrThrow()
                probe("$name page") { "$c2 len=${p.length} m3u8=${Regex("https?:[^\"' ]+\\.m3u8[^\"' ]*").find(p.replace("\\/", "/"))?.value?.take(150)} mp4=${Regex("https?:[^\"' ]+\\.mp4[^\"' ]*").find(p.replace("\\/", "/"))?.value?.take(150)}" }
            }
        }
    }
}
