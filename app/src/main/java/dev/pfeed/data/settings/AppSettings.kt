package dev.pfeed.data.settings

import dev.pfeed.domain.reco.RankWeights
import kotlinx.serialization.Serializable

@Serializable
data class SourceConfig(val id: String, val enabled: Boolean)

@Serializable
enum class Disguise { NONE, NOTES, CALCULATOR }

@Serializable
data class MastodonFeed(val host: String, val tag: String? = null) {
    val label: String get() = if (tag.isNullOrBlank()) host else "$host #$tag"
}

@Serializable
data class BooruFeed(val host: String, val tags: String? = null) {
    val label: String get() = if (tags.isNullOrBlank()) host else "$host · $tags"
}

/**
 * User-defined mapping for the Generic JSON adapter. [urlTemplate] may contain {cursor} and {query}.
 * Paths are dotted (see JsonPath), relative to each item after [wrapPath].
 */
@Serializable
data class JsonFeed(
    val name: String,
    val urlTemplate: String,
    val itemsPath: String,
    val mediaPath: String = "url",
    val searchTemplate: String? = null,
    val wrapPath: String = "",
    val idPath: String = "id",
    val titlePath: String = "title",
    val creatorPath: String = "",
    val thumbnailPath: String = "",
    val tagsPath: String = "",
    val pagePath: String = "",
    val typeHint: String = "auto",
)

/** Everything user-configurable except secrets (PIN, DB key, OAuth tokens), which never leave the device. */
@Serializable
data class AppSettings(
    /** Order = priority (first wins when deduplicating). Missing adapters are appended with their defaults. */
    val sources: List<SourceConfig> = emptyList(),
    val rssFeeds: List<String> = emptyList(),
    /** Direct media/page URLs the user pasted. */
    val directUrls: List<String> = emptyList(),
    /** Subreddit names (no "r/" prefix). */
    val redditSubs: List<String> = emptyList(),
    val mastodonFeeds: List<MastodonFeed> = emptyList(),
    val booruFeeds: List<BooruFeed> = emptyList(),
    val jsonFeeds: List<JsonFeed> = emptyList(),
    /** Storage Access Framework tree URIs. Device-specific, so not imported from backups. */
    val localFolders: List<String> = emptyList(),
    val cacheSizeMb: Int = 1024,
    val autoplayOnMobileData: Boolean = false,
    val defaultMute: Boolean = true,
    val historyRetentionDays: Int = 30,
    val lockTimeoutSec: Int = 30,
    val halfLifeDays: Double = 14.0,
    val weights: RankWeights = RankWeights(),
    val disguise: Disguise = Disguise.NONE,
)
