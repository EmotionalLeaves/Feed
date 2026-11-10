package dev.pfeed

import dev.pfeed.source.parse.BooruHtml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Temporary diagnostic (round 5): which rule34.xxx queries its web page accepts. Never fails. */
class LiveProbeTest {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    @Test
    fun probe() {
        for (q in listOf(
            "sex", "sex -rating:safe", "sex -young", "sex -loli", "sex -child", "animated sex", "huge_breasts paizuri",
            "sex -loli -shota -lolicon -shotacon -toddlercon -child -young -aged_down",
            "sex -loli -shota -lolicon -shotacon -toddlercon -child -young -aged_down -rating:safe",
        )) {
            val out = try {
                Thread.sleep(1500)
                client.newCall(
                    Request.Builder().url("https://rule34.xxx/index.php?page=post&s=list&pid=0&tags=" + URLEncoder.encode(q, "UTF-8"))
                        .header("Accept", "text/html").header("User-Agent", "PrivateFeed/3.17.0 (Android)").build(),
                ).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    "${r.code} items=${BooruHtml.parseListing(body, "rule34.xxx").size} " + if (r.code != 200) Regex("<title>([^<]*)").find(body)?.groupValues?.get(1) else ""
                }
            } catch (e: Throwable) { "EXC $e" }
            println("PROBE|rule34 '$q'|$out")
        }
    }
}
