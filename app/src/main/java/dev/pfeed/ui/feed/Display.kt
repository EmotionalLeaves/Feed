package dev.pfeed.ui.feed

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.parse.MediaTypes
import dev.pfeed.domain.reco.Features

/**
 * How an item's metadata is shown in the feed overlay. Sources hand back raw, technical values
 * ("booru", "/u/name", "12345678@onlyfans", "highres", "rating:explicit"); this turns them into what
 * a person wants to read, without changing the underlying data the app learns from.
 */
object Display {

    /**
     * The type to render an item as once its file is known. Listings often only know a thumbnail:
     * a post listed as an image whose file is really an MP4/WebM must play as a video.
     */
    fun effectiveType(item: MediaItem, stream: StreamInfo?): MediaType {
        val direct = stream as? StreamInfo.Direct ?: return item.type
        val actual = MediaTypes.classify(direct.url, direct.mimeType) ?: return item.type
        return when {
            // Listed as a still, file is a clip → play it.
            (item.type == MediaType.IMAGE || item.type == MediaType.GIF) && actual == MediaType.VIDEO -> MediaType.VIDEO
            // Listed as a clip (boards tag GIFs "animated" too), file is a GIF → show it as a GIF;
            // the video player can't play one and the post was being dropped as broken.
            item.type == MediaType.VIDEO && actual == MediaType.GIF -> MediaType.GIF
            item.type == MediaType.IMAGE && actual == MediaType.GIF -> MediaType.GIF
            else -> item.type
        }
    }

    /** A video post whose only file is a still image (the clip couldn't be found): nothing to play. */
    fun isStillForVideo(item: MediaItem, stream: StreamInfo?): Boolean {
        val s = stream as? StreamInfo.Direct ?: return false
        if (item.type != MediaType.VIDEO) return false
        val actual = MediaTypes.classify(s.url, s.mimeType)
        return actual == MediaType.IMAGE
    }

    /** Short, recognisable name of where an item came from: "r/BDSM", "Rule34", "Coomer"… */
    fun source(item: MediaItem, fallback: String): String {
        val host = item.id.substringBefore('/', "").removePrefix("api.").removePrefix("www.")
        return when (item.sourceId) {
            "reddit" -> item.tags.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "r/$it" } ?: "Reddit"
            "redgifs" -> "RedGIFs"
            "kemono" -> when {
                host.startsWith("coomer") -> "Coomer"
                host.startsWith("kemono") -> "Kemono"
                else -> host.ifBlank { fallback }
            }
            "booru", "philomena" -> KNOWN_HOSTS[host] ?: prettyHost(host).ifBlank { fallback }
            "mastodon" -> KNOWN_HOSTS[host] ?: host.ifBlank { fallback }
            else -> fallback
        }
    }

    /** The creator as a readable handle, or null when there's nothing meaningful to show. */
    fun creator(item: MediaItem): String? {
        var c = item.creator?.trim().orEmpty()
        c = c.removePrefix("/u/").removePrefix("u/").removePrefix("@")
        if (item.sourceId == "kemono") c = c.substringBefore('@')
        if (c.isBlank()) return null
        // Long numeric account ids (Patreon/Fanbox) mean nothing to a reader.
        if (c.length > 5 && c.all(Char::isDigit)) return null
        return c
    }

    /**
     * Up to [max] tags worth tapping: drops ratings, file formats, booru bookkeeping tags and
     * internal markers, and the subreddit name already shown as the source. Returns
     * (label, tag-to-open) pairs.
     */
    fun tags(item: MediaItem, max: Int = 5): List<Pair<String, String>> {
        val skipFirst = item.sourceId == "reddit"
        return item.tags.asSequence()
            .drop(if (skipFirst) 1 else 0)
            .map { it.trim() }
            .filter { it.length in 2..28 }
            .filterNot { it.startsWith("creator:") }
            .filterNot { it.contains(':') && it.substringBefore(':').lowercase() in META_PREFIXES }
            .filterNot { Features.isGeneric(it) }
            .filterNot { Features.normalizeTag(it) in BOOKKEEPING }
            .filterNot { it.all(Char::isDigit) }
            .distinctBy { Features.normalizeTag(it) }
            .take(max)
            .map { raw -> raw.replace('_', ' ') to raw }
            .toList()
    }

    private fun prettyHost(host: String): String {
        val name = host.substringBefore('.')
        return name.replaceFirstChar { it.uppercase() }
    }

    private val KNOWN_HOSTS = mapOf(
        "rule34.xxx" to "Rule34",
        "gelbooru.com" to "Gelbooru",
        "danbooru.donmai.us" to "Danbooru",
        "safebooru.donmai.us" to "Danbooru",
        "e621.net" to "e621",
        "e6ai.net" to "e6AI",
        "yande.re" to "yande.re",
        "konachan.com" to "Konachan",
        "chan.sankakucomplex.com" to "Sankaku",
        "capi-v2.sankakucomplex.com" to "Sankaku",
        "idol.sankakucomplex.com" to "Sankaku Idol",
        "tbib.org" to "TBIB",
        "xbooru.com" to "Xbooru",
        "realbooru.com" to "Realbooru",
        "aibooru.online" to "AIBooru",
        "hypnohub.net" to "HypnoHub",
        "dollbooru.org" to "Dollbooru",
        "derpibooru.org" to "Derpibooru",
        "furbooru.org" to "Furbooru",
        "mastodon.social" to "Mastodon",
        "mstdn.jp" to "mstdn.jp",
    )

    private val META_PREFIXES = setOf("rating", "score", "order", "sort", "source", "md5", "id", "width", "height", "status")

    /** Booru housekeeping tags: about the upload, not the content. */
    private val BOOKKEEPING = setOf(
        "1girl", "1boy", "2girls", "solo", "solo_focus", "commentary", "commentary_request", "english_commentary",
        "translated", "translation_request", "bad_id", "bad_pixiv_id", "bad_twitter_id", "md5_mismatch",
        "duplicate", "artist_name", "signature", "watermark", "web_address", "patreon_username", "twitter_username",
        "simple_background", "white_background", "transparent_background", "looking_at_viewer", "absurd_res", "hi_res",
        "high_res", "conditional_dnp", "third-party_edit", "edit", "censored", "uncensored", "mosaic_censoring",
        "artist_request", "character_request", "copyright_request", "tagme_(artist)", "ai_generated", "ai-generated",
        "has_audio", "sound_warning", "long_playtime", "short_playtime", "2d", "digital_media_(artwork)",
    )
}
