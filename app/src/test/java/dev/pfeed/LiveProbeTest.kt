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
        val home = get("https://comics.8muses.com/").second
        probe("home") { "len=${home.length} " + flat(around(home, "c-tile", 100, 900)) }
        var url = Regex("href=\"(/comics/album/[^\"]+)\"").find(home)?.groupValues?.get(1)
        repeat(3) {
            if (url == null) return@repeat
            val h = get("https://comics.8muses.com$url").second
            val pics = Regex("href=\"(/comics/picture/[^\"]+)\"").findAll(h).count()
            val sub = Regex("href=\"(/comics/album/[^\"]+)\"").findAll(h).map { it.groupValues[1] }.filter { it.length > url!!.length && it.startsWith(url!!) }.firstOrNull()
            probe("album $url") { "len=${h.length} pictures=$pics sub=$sub :: " + flat(around(h, "/image/", 200, 500)) }
            if (pics > 0) return@repeat
            url = sub
        }
    }
}
