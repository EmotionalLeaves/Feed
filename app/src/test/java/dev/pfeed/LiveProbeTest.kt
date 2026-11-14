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

    private val pony = Regex("my_little_pony|my little pony|\\bmlp\\b|friendship_is_magic|\\bpony\\b|equestria|twilight_sparkle|rainbow_dash|fluttershy|pinkie_pie|applejack|rarity|princess_(celestia|luna)|\\bsafe\\b|derpy|\\bequine\\b|\\bbrony")
    private fun share(name: String, url: String, tagsOf: (kotlinx.serialization.json.JsonObject) -> List<String>, arrayKey: String) = probe(name) {
        val root = Json.parseToJsonElement(get(url, "application/json").second)
        val arr = ((root as? kotlinx.serialization.json.JsonObject)?.get(arrayKey) ?: root) as kotlinx.serialization.json.JsonArray
        val posts = arr.map { tagsOf(it as kotlinx.serialization.json.JsonObject) }
        val p = posts.count { t -> t.any { pony.containsMatchIn(it.lowercase()) } }
        "$p of ${posts.size} posts are My Little Pony"
    }

    @Test
    fun probe() {
        val philo: (kotlinx.serialization.json.JsonObject) -> List<String> = { o -> (o["tags"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') } }
        for (q in listOf("explicit", "explicit, bondage", "explicit, milf", "explicit, cosplay"))
            share("derpibooru '$q'", "https://derpibooru.org/api/v1/json/search/images?q=${java.net.URLEncoder.encode(q, "UTF-8")}&filter_id=56027&per_page=50&sf=wilson_score&sd=desc", philo, "images")
        for (q in listOf("explicit", "explicit, bondage"))
            share("furbooru '$q'", "https://furbooru.org/api/v1/json/search/images?q=${java.net.URLEncoder.encode(q, "UTF-8")}&filter_id=2&per_page=50&sf=wilson_score&sd=desc", philo, "images")
        val e6: (kotlinx.serialization.json.JsonObject) -> List<String> = { o -> (o["tags"] as kotlinx.serialization.json.JsonObject).values.flatMap { (it as kotlinx.serialization.json.JsonArray).map { t -> t.toString().trim('"') } } }
        for (q in listOf("explicit order:random", "explicit animation order:random", "bondage explicit order:random"))
            share("e621 '$q'", "https://e621.net/posts.json?tags=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=100", e6, "posts")
    }
}
