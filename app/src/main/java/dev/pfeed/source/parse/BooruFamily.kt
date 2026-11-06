package dev.pfeed.source.parse

/**
 * Image boards run on a handful of engines, each with its own API path, paging and post-page URL.
 * Everything that depends on the engine goes through here so the feed, search and links agree.
 */
enum class BooruFamily {
    /** Gelbooru engine: rule34.xxx, gelbooru.com, tbib, xbooru, realbooru, safebooru.org, hypnohub. */
    GELBOORU,
    /** moebooru: konachan, yande.re. */
    MOEBOORU,
    /** Sankaku (chan/idol). */
    SANKAKU,
    /** Danbooru, e621/e926 and compatible. */
    DANBOORU;

    companion object {
        private val GELBOORU_HOSTS = listOf(
            "rule34.xxx", "gelbooru.com", "tbib.org", "xbooru.com", "realbooru.com", "safebooru.org", "hypnohub.net",
        )

        fun of(host: String): BooruFamily {
            val h = bareHost(host)
            return when {
                GELBOORU_HOSTS.any { h == it || h.endsWith(".$it") } -> GELBOORU
                h.startsWith("konachan.") || h.endsWith("yande.re") || h.contains("behoimi") -> MOEBOORU
                h.endsWith("sankakucomplex.com") -> SANKAKU
                else -> DANBOORU
            }
        }

        /** "https://www.Rule34.xxx/" → "rule34.xxx". */
        fun bareHost(host: String): String =
            host.trim().lowercase()
                .removePrefix("https://").removePrefix("http://")
                .substringBefore('/')
                .removePrefix("www.")

        /** Where the API lives. rule34.xxx serves its API from a separate host. */
        fun apiBase(host: String): String {
            val h = bareHost(host)
            return when {
                h == "rule34.xxx" -> "https://api.rule34.xxx"
                h.endsWith("sankakucomplex.com") -> "https://capi-v2.sankakucomplex.com"
                else -> "https://$h"
            }
        }

        /** The human-facing page for a post: what "Open source page" opens. */
        fun postPage(host: String, postId: String): String {
            val h = bareHost(host).removePrefix("api.")
            return when (of(h)) {
                GELBOORU -> "https://$h/index.php?page=post&s=view&id=$postId"
                MOEBOORU -> "https://$h/post/show/$postId"
                SANKAKU -> "https://${h.removePrefix("capi-v2.")}/posts/$postId"
                DANBOORU -> "https://$h/posts/$postId"
            }
        }
    }
}
