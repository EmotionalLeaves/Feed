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

    @Test
    fun probe() {
        for (host in listOf("hentaiera.com", "hentairox.com")) probe("$host gallery") {
            val id = Regex("""href="/gallery/(\d+)/"""").find(get("https://$host/").second)!!.groupValues[1]
            val (code, html) = get("https://$host/gallery/$id/")
            val hidden = Regex("""<input[^>]*type="hidden"[^>]*>""").findAll(html).map { it.value }.toList()
            val imgs = Regex("""https://m\d+\.$host/[^"' ]+""").findAll(html).map { it.value }.distinct().take(6).toList()
            val tagLinks = Regex("""href="(/tag/[^"]+)"""").findAll(html).map { it.groupValues[1] }.distinct().take(10).toList()
            val tagIdx = html.indexOf("Tags")
            "$code len=${html.length} hidden=$hidden imgs=$imgs tagLinks=$tagLinks :: " + (if (tagIdx >= 0) html.substring(tagIdx, minOf(html.length, tagIdx + 500)) else "<no Tags>") + " || " + around(html, "parseJSON", 0, 200) + " || " + around(html, "/1t.", 300, 100)
        }
    }
}
