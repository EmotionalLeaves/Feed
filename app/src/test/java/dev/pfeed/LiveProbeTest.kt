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
        for (site in listOf("https://www.pornhat.com", "https://www.sunporno.com")) {
            val (_, home) = get("$site/")
            probe("$site item") { around(home, "class=\"item", 0, 2200) }
            for (path in listOf("/search/milf/", "/tags/milf/", "/latest-updates/2/", "/top-rated/", "/categories/milf/", "/search/?q=milf")) probe("$site$path") {
                val (c, b) = get(site + path)
                "code=$c len=${b.length} items=${b.split("class=\"item").size - 1} title=${Regex("<title>([^<]{0,80})").find(b)?.groupValues?.get(1)}"
            }
            probe("$site page") {
                val link = Regex("href=\"((?:https://www\\.[a-z]+\\.com)?/(?:video|v)/[^\"]+)\"").find(home)?.groupValues?.get(1) ?: return@probe "no link"
                val url = if (link.startsWith("/")) site + link else link
                val (c, page) = get(url)
                "url=$url code=$c sources=" + Regex("<source[^>]+>").findAll(page).map { it.value.take(260) }.joinToString(" | ") +
                    " flash=" + Regex("video(?:_alt)?_url\\d*(?:_text)?:\\s*'[^']{0,140}'").findAll(page).map { it.value }.joinToString(" | ") +
                    " tags=" + links(page, "tags") + " cats=" + links(page, "categories") + " models=" + links(page, "models") + " pornstars=" + links(page, "pornstars") +
                    " date=" + around(page, "uploadDate", 0, 80) + " dur=" + around(page, "duration", 0, 80)
            }
        }
    }
}
