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
        var url: String? = "/porn/album/MilfToon-Comics"
        repeat(4) {
            val u = url ?: return@repeat
            val h = get("https://comics.8muses.com$u").second
            val pics = Regex("href=\"(/porn/picture/[^\"]+)\"").findAll(h).count()
            val subs = Regex("href=\"(/porn/album/[^\"]+)\"").findAll(h).map { it.groupValues[1] }.filter { it.startsWith("$u/") && !it.contains("?") }.distinct().toList()
            probe("album $u") { "len=${h.length} pictures=$pics subs=${subs.size} first=${subs.firstOrNull()} :: " + flat(around(h, if (pics > 0) "/porn/picture/" else "c-tile", 100, 700)) }
            if (pics > 0) {
                val th = Regex("/image/th/([^\"]+)\"").find(h)?.groupValues?.get(1)
                probe("full image") { th?.let { head("https://comics.8muses.com/image/fl/$it") } ?: "no th" }
                return@repeat
            }
            url = subs.firstOrNull()
        }
    }
}
