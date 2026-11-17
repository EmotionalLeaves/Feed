package dev.pfeed

import dev.pfeed.di.ArchiveHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Temporary: how candidate sites serve their streams. Never fails. */
class LiveProbeTest {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", ArchiveHeaders.BROWSER_UA).build()) }.build()

    private fun get(url: String, accept: String = "text/html", referer: String? = null): Pair<Int, String> =
        client.newCall(Request.Builder().url(url).header("Accept", accept).apply { if (referer != null) header("Referer", referer) }.build()).execute().use { it.code to it.body?.string().orEmpty() }
    private fun probe(name: String, block: () -> String) = println("PROBE|$name|" + try { block() } catch (e: Throwable) { "EXC ${e.javaClass.simpleName}: ${e.message?.take(200)}" }.replace('\n', ' '))
    private fun around(s: String, marker: String, before: Int, after: Int): String {
        val i = s.indexOf(marker); if (i < 0) return "<no '$marker'>"
        return s.substring((i - before).coerceAtLeast(0), (i + after).coerceAtMost(s.length))
    }
    private fun firstLink(home: String, re: String) = Regex(re).find(home)?.groupValues?.get(1)

    @Test
    fun probe() {
        for (site in listOf("https://www.drtuber.com", "https://www.nuvid.com")) probe("config $site") {
            val (_, home) = get("$site/")
            val id = Regex("/video/(\\d+)/").find(home)?.groupValues?.get(1) ?: return@probe "no id"
            val (c, b) = get("$site/player_config_json/?vid=$id&aid=0&domain_id=0&embed=0&ref=null&check_speed=0", "application/json", "$site/")
            "id=$id code=$c " + b.take(1200)
        }
        probe("drtuber item") { val (_, h) = get("https://www.drtuber.com/"); around(h, "<li class=\"m0\">", 0, 1200) }
        probe("drtuber search") { val (c, b) = get("https://www.drtuber.com/search/videos/milf"); "code=$c len=${b.length} ids=${Regex("/video/(\\d+)/").findAll(b).count()}" }
        probe("sunporno flash") {
            val (_, home) = get("https://www.sunporno.com/")
            val link = firstLink(home, "href=\"(https://www\\.sunporno\\.com/v/[^\"]+)\"") ?: return@probe "no link"
            val (_, p) = get(link)
            "license=" + around(p, "license_code", 0, 60) + " urls=" + Regex("video(?:_alt)?_url\\d*:\\s*'[^']{0,200}'").findAll(p).map { it.value }.joinToString(" | ")
        }
        probe("porndig page") {
            val (_, home) = get("https://www.porndig.com/")
            val link = firstLink(home, "href=\"(/videos/\\d+/[^\"]+)\"") ?: return@probe "no link"
            val (c, p) = get("https://www.porndig.com$link")
            "code=$c m3u8=" + around(p, ".m3u8", 300, 100) + " mp4=" + around(p, ".mp4", 300, 80) + " iframe=" + around(p, "<iframe", 0, 300)
        }
        probe("porn300 page") {
            val (_, home) = get("https://www.porn300.com/")
            val link = firstLink(home, "href=\"((?:https://www\\.porn300\\.com)?/(?:video|videos)/[^\"]+)\"") ?: return@probe "no link"
            val url = if (link.startsWith("/")) "https://www.porn300.com$link" else link
            val (c, p) = get(url)
            "url=$url code=$c m3u8=" + around(p, ".m3u8", 300, 100) + " mp4=" + around(p, ".mp4", 300, 80) + " source=" + Regex("<source[^>]+>").findAll(p).map { it.value.take(200) }.joinToString(" | ")
        }
    }
}
