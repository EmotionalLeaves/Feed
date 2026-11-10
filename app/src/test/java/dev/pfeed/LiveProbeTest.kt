package dev.pfeed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Temporary diagnostic (round 2): prints the real markup/answers the parsers must handle. Never fails. */
class LiveProbeTest {
    private val ua = "PrivateFeed/3.16.0 (Android)"
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class R(val code: Int, val body: String, val type: String?, val location: String?)

    private fun get(url: String, accept: String = "text/html", agent: String = ua, c: OkHttpClient = client, range: Boolean = false): R {
        val b = Request.Builder().url(url).header("Accept", accept).header("User-Agent", agent)
        if (range) b.header("Range", "bytes=0-2047")
        c.newCall(b.build()).execute().use { r ->
            val body = if (range) "bytes=" + (r.body?.bytes()?.size ?: 0) else r.body?.string().orEmpty()
            return R(r.code, body, r.header("Content-Type"), r.header("Location"))
        }
    }

    private fun probe(name: String, block: () -> String) {
        val out = try { block() } catch (e: Throwable) { "EXC ${e.javaClass.simpleName}: ${e.message}" }
        println("PROBE|$name|" + out.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s{2,}"), " "))
    }

    private fun around(s: String, marker: String, before: Int = 250, after: Int = 900, nth: Int = 0): String {
        var i = -1
        repeat(nth + 1) { i = s.indexOf(marker, i + 1); if (i < 0) return "<no '$marker'>" }
        return s.substring((i - before).coerceAtLeast(0), (i + after).coerceAtMost(s.length))
    }

    @Test
    fun probe() {
        // ── Coomer files: where does /data redirect, and does that host resolve / answer? ──
        val api = runCatching { get("https://coomer.st/api/v1/posts?o=0", accept = "text/css").body }.getOrDefault("")
        val paths = Regex(""""path":"([^"]+)"""").findAll(api).map { it.groupValues[1] }.toList()
        val img = paths.firstOrNull { it.endsWith(".jpg") }
        val vid = paths.firstOrNull { it.endsWith(".mp4") }
        for ((label, p) in listOf("img" to img, "vid" to vid)) {
            if (p == null) continue
            probe("coomer $label redirect") { get("https://coomer.st/data$p", "*/*", c = noRedirect).let { "${it.code} location=${it.location} ${it.body.take(120)}" } }
            for (n in 1..6) probe("coomer $label n$n") { get("https://n$n.coomer.st/data$p", "*/*", range = true).let { "${it.code} ${it.type} ${it.body.take(40)}" } }
            probe("coomer $label via img host") { get("https://img.coomer.st/thumbnail/data$p", "*/*", range = true).let { "${it.code} ${it.type} ${it.body.take(40)}" } }
        }
        for (h in listOf("coomer.st", "n1.coomer.st", "n2.coomer.st", "n3.coomer.st", "n4.coomer.st", "img.coomer.st")) {
            probe("dns $h") { InetAddress.getAllByName(h).joinToString { it.hostAddress ?: "?" } }
        }
        probe("coomer post detail") {
            val m = Regex(""""id":"([^"]+)","user":"([^"]+)","service":"([^"]+)"""").find(api) ?: return@probe "no post"
            val (pid, user, svc) = m.destructured
            get("https://coomer.st/api/v1/$svc/user/$user/post/$pid", accept = "text/css").let { "${it.code} ${it.body.take(700)}" }
        }

        // ── Reddit: what do entries actually link to? ──
        for (sub in listOf("gonewild", "nsfw_gifs", "porninfifteenseconds")) probe("reddit $sub links") {
            val r = get("https://www.reddit.com/r/$sub/hot/.rss?limit=25", agent = "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 PrivateFeed/1.1")
            if (r.code != 200) return@probe "${r.code}"
            val entries = r.body.split("<entry>").drop(1)
            entries.take(12).joinToString(" || ") { e ->
                val html = e.substringAfter("<content", "").substringBefore("</content>")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
                val hrefs = Regex("""href="([^"]+)"""").findAll(html).map { it.groupValues[1] }.filterNot { it.contains("/user/") }.toList()
                val imgs = Regex("""<img[^>]*src="([^"]+)"""").findAll(html).map { it.groupValues[1].take(70) }.toList()
                "hrefs=${hrefs.map { it.take(80) }} imgs=$imgs"
            }
        }
        probe("reddit one raw entry") {
            val r = get("https://www.reddit.com/r/nsfw_gifs/hot/.rss?limit=5", agent = "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 PrivateFeed/1.1")
            "${r.code} " + r.body.split("<entry>").getOrNull(2)?.take(1800)
        }

        // ── Board listings + one post page each ──
        val listings = listOf(
            "rule34.xxx" to "https://rule34.xxx/index.php?page=post&s=list&pid=0&tags=sex",
            "gelbooru.com" to "https://gelbooru.com/index.php?page=post&s=list&pid=0&tags=sex",
            "tbib.org" to "https://tbib.org/index.php?page=post&s=list&pid=0&tags=sex",
            "realbooru.com" to "https://realbooru.com/index.php?page=post&s=list&pid=0&tags=sex",
            "rule34.us" to "https://rule34.us/index.php?r=posts/index&q=sex",
        )
        for ((h, url) in listings) {
            var html = ""
            probe("listing $h") {
                val r = get(url); html = r.body
                "${r.code} len=${html.length} " + around(html, "thumbnail", 300, 700, nth = if (h == "rule34.us") 0 else 3)
            }
            val postUrl = Regex("""href="([^"]*(?:s=view|posts/view)[^"]*id=\d+[^"]*)"""").find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
                ?.let { if (it.startsWith("http")) it else "https://$h/" + it.removePrefix("/") }
            probe("post page $h $postUrl") {
                if (postUrl == null) return@probe "no post link found"
                val p = get(postUrl).body
                val vid = Regex("""<(?:source|video)[^>]*>""").find(p)?.value
                val image = Regex("""<img[^>]*id="image"[^>]*>""").find(p)?.value ?: Regex("""<img[^>]*(?:class="[^"]*content[^"]*"|id="img")[^>]*>""").find(p)?.value
                val orig = Regex("""<a[^>]*href="[^"]+"[^>]*>\s*Original[^<]*</a>""").find(p)?.value
                val og = Regex("""<meta[^>]*property="og:image"[^>]*>""").find(p)?.value
                "len=${p.length} video=$vid image=$image original=$orig og=$og"
            }
        }
        probe("tbib json first") {
            val r = get("https://tbib.org/index.php?page=dapi&s=post&q=index&json=1&limit=2&pid=0&tags=sex", "application/json")
            ((json.parseToJsonElement(r.body) as JsonArray).first() as JsonObject).toString().take(900)
        }
        probe("tbib file guess") {
            val r = get("https://tbib.org/index.php?page=dapi&s=post&q=index&json=1&limit=1&pid=0&tags=sex", "application/json")
            val o = (json.parseToJsonElement(r.body) as JsonArray).first() as JsonObject
            fun s(k: String) = o[k].toString().trim('"')
            val u = "https://tbib.org/images/${s("directory")}/${s("image")}"
            val t = "https://tbib.org/thumbnails/${s("directory")}/thumbnail_${s("image").substringBeforeLast('.')}.jpg"
            "$u -> " + get(u, "*/*", range = true).let { "${it.code} ${it.type}" } + " ; $t -> " + get(t, "*/*", range = true).let { "${it.code} ${it.type}" }
        }
        for (u in listOf(
            "https://danbooru.donmai.us/posts.json?limit=5&tags=rating:explicit+sex",
            "https://chan.sankakucomplex.com/posts/keyset?limit=5&tags=sex",
            "https://capi-v2.sankakucomplex.com/posts/keyset?limit=5&tags=sex",
            "https://dollbooru.org/posts.json?limit=5",
            "https://derpibooru.org/api/v1/json/search/images?q=explicit&per_page=5&filter_id=56027",
            "https://furbooru.org/api/v1/json/search/images?q=explicit&per_page=5&filter_id=2",
        )) probe("api $u") { get(u, "application/json").let { "${it.code} ${it.type} ${it.body.take(200)}" } }

        // ── Mastodon servers in the library ──
        for (h in listOf("bdsmr.com", "cum.salon", "ero.social", "femdom.social", "kemonodon.space", "kinkies.com", "kinktroet.social", "latex.social", "lewdland.social", "nsfwdone.social", "rubber.social", "sex.social", "yiff.social", "sinblr.com", "mastodon.social", "mas.to", "mstdn.jp")) {
            probe("mastodon $h") {
                val t = get("https://$h/api/v1/timelines/tag/nsfw?limit=40&only_media=true", "application/json")
                val p = get("https://$h/api/v1/timelines/public?limit=40&only_media=true&local=true", "application/json")
                fun n(b: String) = runCatching { (json.parseToJsonElement(b) as JsonArray).size }.getOrDefault(-1)
                "tag ${t.code} n=${n(t.body)} | public ${p.code} n=${n(p.body)} ${if (n(p.body) < 0) p.body.take(80) else ""}"
            }
        }
    }
}
