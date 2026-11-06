package dev.pfeed.data.settings

import dev.pfeed.domain.reco.RankWeights
import kotlinx.serialization.Serializable

@Serializable
data class SourceConfig(val id: String, val enabled: Boolean)

@Serializable
enum class Disguise { NONE, NOTES, CALCULATOR }

/** Everything user-configurable except secrets (PIN, DB key, OAuth tokens), which never leave the device. */
@Serializable
data class AppSettings(
    /** Order = priority (first wins when deduplicating). Missing adapters are appended with their defaults. */
    val sources: List<SourceConfig> = emptyList(),
    val rssFeeds: List<String> = emptyList(),
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
