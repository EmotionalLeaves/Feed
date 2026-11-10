package dev.pfeed

import dev.pfeed.source.parse.BooruHtml
import dev.pfeed.source.parse.BooruParser
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Temporary diagnostic (round 3): runs the app's real board parsers against the live sites. Never fails. */
class LiveProbeTest {
    private val ua = "PrivateFeed/3.17.0 (Android)"
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun get(url: String, accept: String = "text/html", range: Boolean = false): Pair<Int, String> {
        val b = Request.Builder().url(url).header("Accept", accept).header("User-Agent", ua)
        if (range) b.header("Range", "bytes=0-2047")
        client.newCall(b.build()).execute().use { r ->
            return r.code to (if (range) "${r.header("Content-Type")}" else r.body?.string().orEmpty())
        }
    }

    private fun probe(name: String, block: () -> String) {
        val out = try { block() } catch (e: Throwable) { "EXC ${e.javaClass.simpleName}: ${e.message}" }
        println("PROBE|$name|" + out.replace('\n', ' '))
    }

    @Test
    fun probe() {
        val q = URLEncoder.encode("sex -loli -shota -lolicon -shotacon -toddlercon -child -young -aged_down -rating:safe", "UTF-8")
        val gq = URLEncoder.encode("animated sex -loli -shota -lolicon -shotacon -toddlercon -child -young -aged_down -rating:general -rating:sensitive", "UTF-8")
        for ((h, query) in listOf("rule34.xxx" to q, "gelbooru.com" to gq, "realbooru.com" to q, "tbib.org" to q, "xbooru.com" to q, "hypnohub.net" to q)) {
            probe("html $h") {
                val (code, html) = get("https://$h/index.php?page=post&s=list&pid=0&tags=$query")
                val items = BooruHtml.parseListing(html, h)
                val first = items.firstOrNull() ?: return@probe "$code len=${html.length} items=0"
                val (pc, page) = get(first.pageUrl)
                val media = BooruHtml.mediaFromPostPage(page, first.pageUrl)
                val vid = items.firstOrNull { it.type == dev.pfeed.domain.model.MediaType.VIDEO }
                val vmedia = vid?.let { BooruHtml.mediaFromPostPage(get(it.pageUrl).second, it.pageUrl) }
                "$code items=${items.size} types=${items.groupingBy { it.type }.eachCount()} rating=${first.extras["rating"]} tags=${first.tags.take(4)} thumb=${get(first.thumbnailUrl!!, "*/*", true)} " +
                    "page=$pc media=${media?.url} -> ${media?.let { get(it.url, "*/*", true) }} full=${media?.full} | video=${vmedia?.url} -> ${vmedia?.let { get(it.url, "*/*", true) }}"
            }
        }
        probe("tbib api") {
            val (code, body) = get("https://tbib.org/index.php?page=dapi&s=post&q=index&json=1&limit=40&pid=0&tags=$q", "application/json")
            val items = BooruParser.parse(json.parseToJsonElement(body), "tbib.org")
            val f = items.firstOrNull() ?: return@probe "$code items=0"
            "$code items=${items.size} media=${f.extras["media"]} -> ${get(f.extras["media"]!!, "*/*", true)} full=${f.extras["full"]} thumb=${f.thumbnailUrl} -> ${get(f.thumbnailUrl!!, "*/*", true)}"
        }
        probe("danbooru") { get("https://danbooru.donmai.us/posts.json?limit=3&tags=rating:explicit+sex", "application/json").let { "${it.first} ${it.second.take(100)}" } }
    }
}
