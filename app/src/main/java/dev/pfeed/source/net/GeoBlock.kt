package dev.pfeed.source.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Sites that refuse this connection because of where it is: US state age-verification laws
 * (RedGIFs and the Pornhub family block whole states), or a country block. Seen from the site's
 * own answer (HTTP 451, or a short "blocked in your state" page instead of the real one).
 *
 * Blocked sites are routed through the proxy (Settings → Blocked where you are) when one is set;
 * without one, they're named in Settings so it's clear why they're quiet.
 */
object GeoBlock {
    private const val PREFS = "geo_block"
    /** A block is re-checked after this long (laws and the sites' choices change; you may travel). */
    private const val TTL_MS = 3L * 24 * 3600 * 1000
    /** Block pages are short; real pages and API answers that run past this are never block pages. */
    private const val PEEK = 24_000L

    private val blocked = ConcurrentHashMap<String, Long>()
    private val _sites = MutableStateFlow<Set<String>>(emptySet())
    /** Sites (registrable domains, "redgifs.com") currently blocked where you are. */
    val sites: StateFlow<Set<String>> = _sites
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val now = System.currentTimeMillis()
        p.all.forEach { (site, at) -> (at as? Long)?.takeIf { now - it < TTL_MS }?.let { blocked[site] = it } }
        publish()
    }

    /** "media.redgifs.com" → "redgifs.com". */
    fun siteOf(host: String): String {
        val parts = host.lowercase().trimEnd('.').split('.')
        return if (parts.size <= 2) parts.joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    fun isBlocked(host: String): Boolean {
        val at = blocked[siteOf(host)] ?: return false
        return System.currentTimeMillis() - at < TTL_MS
    }

    /** Called when a site is newly found blocked (drops pooled direct connections so the proxy is used). */
    @Volatile var onNewBlock: (() -> Unit)? = null

    internal fun mark(site: String) {
        val had = blocked.containsKey(site)
        blocked[site] = System.currentTimeMillis()
        prefs?.edit()?.putLong(site, blocked[site]!!)?.apply()
        if (!had) { publish(); onNewBlock?.invoke() }
    }

    internal fun clear(site: String) {
        if (blocked.remove(site) != null) {
            prefs?.edit()?.remove(site)?.apply()
            publish()
        }
    }

    private fun publish() { _sites.value = blocked.keys.toSet() }

    /**
     * Looks at an answer: a block marks the site; a full, normal answer reached directly clears it
     * (you moved, a VPN is on, or the site changed its mind). Media and big pages aren't read.
     */
    fun inspect(host: String, response: Response, viaProxy: Boolean) {
        val site = siteOf(host)
        if (response.code == 451) { mark(site); return }
        val type = response.header("Content-Type")?.lowercase().orEmpty()
        if (!type.contains("html") && !type.contains("json")) return
        if (response.code !in 200..403) return
        val peeked = runCatching { response.peekBody(PEEK).string() }.getOrNull() ?: return
        val short = peeked.length < PEEK
        if (short && (looksBlocked(peeked) || (response.code == 403 && refusedForPlace(peeked)))) mark(site)
        else if (!short && response.isSuccessful && !viaProxy && blocked.containsKey(site)) clear(site)
    }

    private val MARKERS = listOf(
        "blocked due to legislation", "due to legislation in", "not available in your state", "unavailable in your state",
        "not available in your region", "unavailable in your region", "not available in your location", "restricted in your location",
        "your elected officials", "age verification laws", "access from your state", "blocked in your state", "blocked in your region",
        "unavailableforlegalreasons", "unavailable for legal reasons",
    )

    /** A short 403 that gives the place as the reason (RedGIFs' API answers blocked states this way). */
    internal fun refusedForPlace(body: String): Boolean {
        val t = body.lowercase()
        if (t.contains("just a moment") || t.contains("cf-chl")) return false // a bot check, not a place block
        return listOf("your region", "your state", "your location", "your country", "geo", "legal reasons", "age verification").any { t.contains(it) }
    }

    internal fun looksBlocked(body: String): Boolean {
        val t = body.lowercase()
        return MARKERS.any { t.contains(it) }
    }
}
