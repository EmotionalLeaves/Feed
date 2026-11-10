package dev.pfeed

import dev.pfeed.source.parse.BooruParser
import dev.pfeed.source.parse.MastodonParser
import dev.pfeed.source.reddit.RedditRss
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Temporary diagnostic: asks each source's real endpoint exactly the way the app does and prints
 * what came back, so fixes are based on what the sites actually answer. Never fails the build.
 */
class LiveProbeTest {
    private val ua = "PrivateFeed/3.16.0 (Android)"
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class R(val code: Int, val body: String, val type: String?, val finalUrl: String)

    private fun get(url: String, accept: String = "application/json", agent: String = ua, range: Boolean = false): R {
        val b = Request.Builder().url(url).header("Accept", accept).header("User-Agent", agent)
        if (range) b.header("Range", "bytes=0-2047")
        client.newCall(b.build()).execute().use { r ->
            val body = if (range) (r.body?.bytes()?.size ?: 0).toString() else r.body?.string().orEmpty()
            return R(r.code, body, r.header("Content-Type"), r.request.url.toString())
        }
    }

    private fun probe(name: String, block: () -> String) {
        val out = try { block() } catch (e: Throwable) { "EXC ${e.javaClass.simpleName}: ${e.message}" }
        println("PROBE|$name|$out")
    }

    private fun head(s: String) = s.take(160).replace('\n', ' ')

    @Test
    fun probe() {
        // ── Coomer ──
        var firstPaths: List<String> = emptyList()
        probe("coomer posts") {
            val r = get("https://coomer.st/api/v1/posts?o=0", accept = "text/css")
            if (r.code != 200) return@probe "${r.code} ${r.type} ${head(r.body)}"
            val root = json.parseToJsonElement(r.body)
            val posts = when (root) {
                is JsonArray -> root.mapNotNull { it as? JsonObject }
                is JsonObject -> (root["posts"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                else -> emptyList()
            }
            firstPaths = posts.flatMap { p ->
                listOfNotNull(((p["file"] as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull) +
                    ((p["attachments"] as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull }.orEmpty())
            }
            val keys = (root as? JsonObject)?.keys ?: setOf("<array>")
            "200 ${r.type} posts=${posts.size} paths=${firstPaths.size} rootKeys=$keys firstPostKeys=${posts.firstOrNull()?.keys} sample=${firstPaths.take(3)}"
        }
        probe("coomer posts (json accept)") { get("https://coomer.st/api/v1/posts?o=0").let { "${it.code} ${it.type} ${head(it.body)}" } }
        probe("coomer popular") { get("https://coomer.st/api/v1/posts/popular?period=week", accept = "text/css").let { "${it.code} ${it.type} ${head(it.body)}" } }
        probe("coomer search") { get("https://coomer.st/api/v1/posts?q=beach&o=0", accept = "text/css").let { "${it.code} ${it.type} ${head(it.body)}" } }
        val img = firstPaths.firstOrNull { it.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
        val vid = firstPaths.firstOrNull { it.substringAfterLast('.').lowercase() in setOf("mp4", "m4v", "mov", "webm") }
        if (img != null) {
            probe("coomer image file") { get("https://coomer.st/data$img", accept = "*/*", range = true).let { "${it.code} ${it.type} bytes=${it.body} final=${it.finalUrl.take(60)}" } }
            probe("coomer thumbnail") { get("https://img.coomer.st/thumbnail/data$img", accept = "*/*", range = true).let { "${it.code} ${it.type} bytes=${it.body} final=${it.finalUrl.take(60)}" } }
        }
        if (vid != null) {
            probe("coomer video file") { get("https://coomer.st/data$vid", accept = "*/*", range = true).let { "${it.code} ${it.type} bytes=${it.body} final=${it.finalUrl.take(60)}" } }
        }

        // ── Reddit ──
        for (u in listOf(
            "https://www.reddit.com/r/gonewild/hot/.rss?limit=50",
            "https://www.reddit.com/r/nsfw_gifs/top/.rss?limit=50&t=week",
            "https://old.reddit.com/r/gonewild/hot/.rss?limit=50",
        )) probe("reddit ${u.substringAfter("reddit.com")}") {
            val r = get(u, accept = "text/html", agent = "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 PrivateFeed/1.1")
            if (r.code != 200) return@probe "${r.code} ${head(r.body)}"
            val items = RedditRss.parse(r.body, "x")
            "200 entries=${r.body.split("<entry>").size - 1} items=${items.size} types=${items.groupingBy { it.type }.eachCount()} last=${RedditRss.lastId(r.body)} firstMedia=${items.firstOrNull()?.extras}"
        }

        // ── RedGIFs ──
        probe("redgifs") {
            val a = get("https://api.redgifs.com/v2/auth/temporary", agent = "Mozilla/5.0 (Android; Mobile) PrivateFeed/2")
            if (a.code != 200) return@probe "auth ${a.code} ${head(a.body)}"
            val token = ((json.parseToJsonElement(a.body) as JsonObject)["token"] as JsonPrimitive).content
            client.newCall(
                Request.Builder().url("https://api.redgifs.com/v2/gifs/search?search_text=milf%20anal&order=trending&count=40&page=1")
                    .header("Authorization", "Bearer $token").header("User-Agent", "Mozilla/5.0 (Android; Mobile) PrivateFeed/2").build(),
            ).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val gifs = runCatching { ((json.parseToJsonElement(body) as JsonObject)["gifs"] as JsonArray).size }.getOrDefault(-1)
                "search ${r.code} gifs=$gifs ${if (gifs <= 0) head(body) else ""}"
            }
        }

        // ── Boards (API, the way the app asks) ──
        val gel = listOf("api.rule34.xxx", "gelbooru.com", "tbib.org", "xbooru.com", "rule34.us", "realbooru.com", "hypnohub.net")
        for (h in gel) {
            probe("board api $h") {
                val r = get("https://$h/index.php?page=dapi&s=post&q=index&json=1&limit=20&pid=0&tags=sex")
                val n = runCatching { BooruParser.parse(json.parseToJsonElement(r.body), h.removePrefix("api.")).size }.getOrDefault(-1)
                "${r.code} ${r.type} parsed=$n ${if (n <= 0) head(r.body) else ""}"
            }
            probe("board html $h") {
                val r = get("https://${h.removePrefix("api.")}/index.php?page=post&s=list&pid=0&tags=sex", accept = "text/html")
                "${r.code} len=${r.body.length} thumbs=${Regex("thumbnail").findAll(r.body).count()} ids=${Regex("[?&]id=(\\d+)").findAll(r.body).count()} ${if (r.body.length < 3000) head(r.body) else ""}"
            }
        }
        for ((h, q) in listOf(
            "danbooru.donmai.us" to "posts.json?limit=20&tags=rating:explicit+sex",
            "danbooru.donmai.us" to "posts.json?limit=20&tags=animated+sex",
            "aibooru.online" to "posts.json?limit=20&tags=rating:explicit+sex",
            "e621.net" to "posts.json?limit=20&tags=sex+rating:e",
            "e6ai.net" to "posts.json?limit=20&tags=sex+rating:e",
            "yande.re" to "post.json?limit=20&tags=sex+rating:e",
            "konachan.com" to "post.json?limit=20&tags=sex+rating:e",
        )) probe("board $h $q") {
            val r = get("https://$h/$q")
            val n = runCatching { BooruParser.parse(json.parseToJsonElement(r.body), h).size }.getOrDefault(-1)
            "${r.code} parsed=$n ${if (n <= 0) head(r.body) else ""}"
        }

        // ── Mastodon ──
        for (h in listOf("mastodon.social", "mstdn.social", "mas.to", "lewd.town", "kinky.business", "sinblr.com", "mstdn.jp")) probe("mastodon $h") {
            val r = get("https://$h/api/v1/timelines/tag/nsfw?limit=40&only_media=true")
            val n = runCatching { MastodonParser.parse(json.parseToJsonElement(r.body), h).size }.getOrDefault(-1)
            "${r.code} parsed=$n ${if (n <= 0) head(r.body) else ""}"
        }
    }
}
