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

    private fun b36(n: Long): String = java.lang.Long.toString(n, 36)

    @Test
    fun probe() {
        val list = TubeSite.Eporner.parseList(get(TubeSite.Eporner.listUrl("new", 0), "application/json").second)
        val v = list.first()
        probe("video") { "${v.id} ${v.pageUrl}" }
        for (u in listOf(v.pageUrl, "https://www.eporner.com/embed/${v.id}/", "https://www.eporner.com/hd-porn/${v.id}/")) {
            val (c, html) = get(u)
            val hash = Regex("""hash\s*[:=]\s*["']([\da-f]{32})""").find(html)?.groupValues?.get(1)
            probe("page $u") { "$c len=${html.length} hash=$hash title=${Regex("<title>([^<]{0,50})").find(html)?.groupValues?.get(1)} ${around(html, "hash", 80, 80).replace(Regex("\\s+"), " ")}" }
            if (hash != null) {
                val enc = (0 until 32 step 8).joinToString("") { b36(hash.substring(it, it + 8).toLong(16)) }
                val x = "https://www.eporner.com/xhr/video/${v.id}?hash=$enc&domain=www.eporner.com&fallback=false&embed=true&supportedFormats=dash,mp4"
                probe("xhr") { get(x, "application/json", u).let { (c2, b) -> "$c2 ${b.take(600)}" } }
                break
            }
        }
    }
}
