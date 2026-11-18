package dev.pfeed

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Temporary: what Porntrex answers from CI, with a few header sets. */
class LiveProbeTest {
    @Test
    fun probe() {
        val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()
        val uas = listOf(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )
        for (url in listOf("https://www.porntrex.com/latest-updates/1/", "https://porntrex.com/latest-updates/1/", "https://www.porntrex.com/")) for (ua in uas) {
            runCatching {
                http.newCall(Request.Builder().url(url).header("User-Agent", ua).header("Accept", "text/html,application/xhtml+xml").header("Accept-Language", "en-US,en;q=0.9").build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    println("PROBE $url ua=${ua.take(30)} -> ${r.code} server=${r.header("server")} cf=${r.header("cf-ray") != null} len=${body.length} videos=${Regex("/video/").findAll(body).count()} head=${body.take(160).replace("\n", " ")}")
                }
            }.onFailure { println("PROBE $url failed: ${it.message}") }
        }
    }
}
