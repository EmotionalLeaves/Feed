package dev.pfeed.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Serialized MediaItem, kept only while something in the library references it. */
@Entity(tableName = "items")
data class ItemEntity(@PrimaryKey val itemKey: String, val json: String, val storedAt: Long)

/** kind = SAVED | LIKED | HISTORY */
@Entity(tableName = "library", primaryKeys = ["itemKey", "kind"], indices = [Index("kind", "at")])
data class LibraryEntity(val itemKey: String, val kind: String, val at: Long)

@Entity(tableName = "signals", indices = [Index("at")])
data class SignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemKey: String,
    val type: String,
    val value: Double,
    val at: Long,
)

@Entity(tableName = "tag_affinity")
data class TagAffinityEntity(@PrimaryKey val tag: String, val score: Double, val updatedAt: Long)

@Entity(tableName = "creator_affinity")
data class CreatorAffinityEntity(@PrimaryKey val creator: String, val score: Double, val updatedAt: Long)

/** kind = CREATOR | TAG */
@Entity(tableName = "follows", primaryKeys = ["kind", "value"])
data class FollowEntity(val kind: String, val value: String, val at: Long)

/** kind = TAG | CREATOR | ITEM */
@Entity(tableName = "blocks", primaryKeys = ["kind", "value"])
data class BlockEntity(val kind: String, val value: String, val at: Long)

/** OAuth tokens etc. Lives only in the encrypted DB; never exported. */
@Entity(tableName = "credentials")
data class CredentialEntity(@PrimaryKey val sourceId: String, val json: String)
