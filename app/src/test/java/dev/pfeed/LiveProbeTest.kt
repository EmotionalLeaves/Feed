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

    private fun chain(url: String, accept: String = "*/*"): String = client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").header("Accept", accept).build()).execute().use { r ->
        val hops = generateSequence(r) { it.priorResponse }.toList().reversed().joinToString(" -> ") { "${it.code} ${it.request.url.host}" }
        "$hops type=${r.header("Content-Type")} len=${r.header("Content-Length")} final=${r.request.url.toString().take(120)}"
    }

    private fun noFollow(url: String): String = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        .newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { "${it.code} loc=${it.header("Location")}" }

    @Test
    fun probe() {
        val path = "/56/0b/560b7d65dc462caebf9a1530d95bd47374a0fdf2e40a585c83f3046b4ab1ba1e.jpg"
        probe("redirect") { noFollow("https://coomer.st/data$path") }
        for (h in listOf("coomer.st", "n1.coomer.st", "n2.coomer.st", "n3.coomer.st", "n4.coomer.st", "img.coomer.st", "kemono.cr", "n1.kemono.cr", "n4.kemono.cr"))
            probe("dns $h") { java.net.InetAddress.getAllByName(h).joinToString { it.hostAddress ?: "?" } }
        probe("v4 only n1") {
            val v4 = client.newBuilder().dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> = okhttp3.Dns.SYSTEM.lookup(hostname).filterIsInstance<java.net.Inet4Address>()
            }).build()
            v4.newCall(Request.Builder().url("https://n1.coomer.st/data$path").header("Range", "bytes=0-0").build()).execute().use { "${it.code} ${it.header("Content-Type")}" }
        }
        probe("with server param") { noFollow("https://coomer.st/data$path?f=x.jpg") }
    }
}
