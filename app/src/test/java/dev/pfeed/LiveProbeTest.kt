package dev.pfeed

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Temporary diagnostic (round 4): what the image hosts answer, with and without the referring page. Never fails. */
class LiveProbeTest {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    private fun get(url: String, referer: String? = null, accept: String = "*/*"): String {
        val b = Request.Builder().url(url).header("Accept", accept).header("User-Agent", "PrivateFeed/3.17.0 (Android)")
        if (referer != null) b.header("Referer", referer)
        client.newCall(b.build()).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            val body = if (type.startsWith("text/")) r.body?.string().orEmpty().take(500) else "<${r.body?.bytes()?.size} bytes>"
            return "${r.code} $type server=${r.header("Server")} $body".replace('\n', ' ')
        }
    }

    private fun probe(name: String, block: () -> String) =
        println("PROBE|$name|" + try { block() } catch (e: Throwable) { "EXC ${e.javaClass.simpleName}: ${e.message}" })

    @Test
    fun probe() {
        val g = "https://img4.gelbooru.com/images/42/71/4271d6e85b00347e4398365c0c1e2e0d.mp4"
        val gt = "https://img4.gelbooru.com/thumbnails/d5/e8/thumbnail_d5e8403be562e5046fc97a73b3c390d6.jpg"
        val r = "https://realbooru.com/images/cc/b8/ccb820a45dfc2f90c0a8ddecad962810.mp4"
        val rt = "https://realbooru.com/thumbnails/6d/b3/thumbnail_6db3f1a397db5b0288e992666b917878.jpg"
        probe("gelbooru file") { get(g) }
        probe("gelbooru file +referer") { get(g, "https://gelbooru.com/") }
        probe("gelbooru thumb") { get(gt) }
        probe("gelbooru thumb +referer") { get(gt, "https://gelbooru.com/") }
        probe("realbooru file") { get(r) }
        probe("realbooru file +referer") { get(r, "https://realbooru.com/") }
        probe("realbooru thumb") { get(rt) }
        probe("realbooru thumb +referer") { get(rt, "https://realbooru.com/") }
        probe("rule34 page") { get("https://rule34.xxx/index.php?page=post&s=list&tags=sex", accept = "text/html") }
        probe("rule34 api") { get("https://api.rule34.xxx/index.php?page=dapi&s=post&q=index&json=1&limit=2&tags=sex", accept = "application/json") }
    }
}
