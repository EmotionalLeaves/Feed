package dev.pfeed.source.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Your own proxy (Settings → Blocked where you are), for sites that block your state or country:
 * an HTTP or SOCKS5 proxy, or Orbot's Tor proxy on the phone. Used only for blocked sites unless
 * you ask for everything. With none set, every connection goes direct.
 */
object AppProxy : ProxySelector() {
    data class Config(
        val type: Proxy.Type = Proxy.Type.DIRECT,
        val host: String = "",
        val port: Int = 0,
        val user: String = "",
        val password: String = "",
        /** true: every site goes through it; false: only sites blocked where you are. */
        val everything: Boolean = false,
    ) {
        val active: Boolean get() = type != Proxy.Type.DIRECT && host.isNotBlank() && port in 1..65535
    }

    @Volatile var config: Config = Config()
        private set

    /** Called when the proxy changes (drops pooled connections made the old way). */
    @Volatile var onChange: (() -> Unit)? = null

    fun configure(c: Config) {
        if (c == config) return
        config = c
        onChange?.invoke()
        // SOCKS5 sign-in goes through Java's Authenticator; HTTP proxies use OkHttp's (see AppModule).
        if (c.active && c.type == Proxy.Type.SOCKS && c.user.isNotBlank()) {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication? =
                    if (requestingHost == c.host || requestingPort == c.port) java.net.PasswordAuthentication(c.user, c.password.toCharArray()) else null
            })
        }
    }

    /** Whether a request to [host] goes through the proxy right now. */
    fun routes(host: String): Boolean {
        val c = config
        return c.active && (c.everything || GeoBlock.isBlocked(host))
    }

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
        val c = config
        if (!routes(host)) return listOf(Proxy.NO_PROXY)
        // Unresolved: the proxy looks the name up, so a blocked name doesn't need local DNS.
        return listOf(Proxy(c.type, InetSocketAddress.createUnresolved(c.host, c.port)))
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
}
