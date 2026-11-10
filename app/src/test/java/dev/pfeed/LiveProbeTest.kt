package dev.pfeed

import dev.pfeed.di.HotlinkReferer
import dev.pfeed.domain.model.MediaType
import dev.pfeed.source.parse.BooruHtml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Temporary diagnostic (final): listing → post page → file, with the app's request rules. Never fails. */
class LiveProbeTest {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val site = HotlinkReferer.siteFor(request.url.host)
            if (site == null) chain.proceed(request) else chain.proceed(request.newBuilder().header("Referer", "https://$site/").build())
        }.build()

    private fun text(url: String): String = client.newCall(Request.Builder().url(url).header("Accept", "text/html").header("User-Agent", "PrivateFeed/3.17.0 (Android)").build()).execute().use { it.body?.string().orEmpty() }
    private fun file(url: String): String = client.newCall(Request.Builder().url(url).header("Range", "bytes=0-1023").header("User-Agent", "PrivateFeed/3.17.0 (Android)").build()).execute().use { "${it.code} ${it.header("Content-Type")}" }

    @Test
    fun probe() {
        for (h in listOf("rule34.xxx", "gelbooru.com", "realbooru.com")) {
            val out = try {
                val items = BooruHtml.parseListing(text("https://$h/index.php?page=post&s=list&pid=0&tags=sex"), h)
                val picks = listOfNotNull(items.firstOrNull { it.type == MediaType.IMAGE }, items.firstOrNull { it.type == MediaType.VIDEO })
                "items=${items.size} " + picks.joinToString(" | ") { p ->
                    Thread.sleep(1200)
                    val m = BooruHtml.mediaFromPostPage(text(p.pageUrl), p.pageUrl)
                    "${p.type}: thumb ${file(p.thumbnailUrl!!)}; file ${m?.url?.substringBefore('?')?.takeLast(40)} ${m?.let { file(it.url) }}"
                }
            } catch (e: Throwable) { "EXC $e" }
            println("PROBE|chain $h|$out")
        }
    }
}
