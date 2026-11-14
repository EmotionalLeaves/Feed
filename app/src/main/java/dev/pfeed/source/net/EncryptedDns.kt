package dev.pfeed.source.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS for the app's HTTP client. Many networks (ISPs in several countries, schools, offices,
 * family-safety DNS) block adult sites by answering their names with nothing, or with a
 * local/sinkhole address. When that happens the name is looked up again over HTTPS with Cloudflare
 * (1.1.1.1, reached by address, so it can't itself be blocked by name), which answers truthfully.
 * With [always] on, every name goes that way first, which also keeps lookups private from the network.
 *
 * Answers are cached for their TTL (at least a minute), so it costs one request per site, not per call.
 */
object EncryptedDns : Dns {
    /** Settings → Privacy → "Encrypted DNS". */
    @Volatile var always: Boolean = false

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    private val doh: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM) // only ever asked for 1.1.1.1 / 1.0.0.1 literals
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (isLiteral(hostname)) return Dns.SYSTEM.lookup(hostname)
        if (always) {
            secure(hostname)?.let { return it }
            return Dns.SYSTEM.lookup(hostname)
        }
        val system = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            return secure(hostname) ?: throw e
        }
        if (system.isEmpty() || system.all(::looksBlocked)) return secure(hostname) ?: system
        return system
    }

    /** Sinkhole answers block filters give instead of the real address. */
    internal fun looksBlocked(a: InetAddress): Boolean =
        a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            (a is Inet4Address && (a.address[0].toInt() == 0 || a.isSiteLocalAddress)) ||
            (a is Inet6Address && a.isSiteLocalAddress)

    private fun isLiteral(host: String) = host.all { it.isDigit() || it == '.' } || host.contains(':')

    private fun secure(host: String): List<InetAddress>? {
        val now = System.currentTimeMillis()
        cache[host]?.let { (until, list) -> if (now < until) return list }
        val v4 = query(host, "A")
        val v6 = if (v4 == null || v4.first.isEmpty()) query(host, "AAAA") else null
        val answers = (v4?.first.orEmpty() + v6?.first.orEmpty())
        if (answers.isEmpty()) return null
        val ttl = (v4?.second ?: v6?.second ?: 300).coerceIn(60, 3600)
        cache[host] = (now + ttl * 1000L) to answers
        return answers
    }

    /** Addresses and TTL for one record type, or null if neither resolver answered. */
    private fun query(host: String, type: String): Pair<List<InetAddress>, Int>? {
        for (server in SERVERS) {
            try {
                val req = Request.Builder().url("https://$server/dns-query?name=$host&type=$type")
                    .header("Accept", "application/dns-json").build()
                val body = doh.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: continue
                return parse(host, body, if (type == "A") 1 else 28)
            } catch (_: Exception) {
                // try the next resolver
            }
        }
        return null
    }

    internal fun parse(host: String, body: String, recordType: Int): Pair<List<InetAddress>, Int> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList<InetAddress>() to 0
        val answers = (root["Answer"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.intOrNull == recordType }
        val ips = answers.mapNotNull { a ->
            (a["data"] as? JsonPrimitive)?.contentOrNull?.let { ip -> runCatching { InetAddress.getByAddress(host, InetAddress.getByName(ip).address) }.getOrNull() }
        }
        val ttl = answers.mapNotNull { (it["TTL"] as? JsonPrimitive)?.intOrNull }.minOrNull() ?: 300
        return ips to ttl
    }

    private val SERVERS = listOf("1.1.1.1", "1.0.0.1")
}
