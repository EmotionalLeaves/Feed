package dev.pfeed.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Upsert
    suspend fun upsert(item: ItemEntity)

    @Query("DELETE FROM items WHERE itemKey NOT IN (SELECT itemKey FROM library)")
    suspend fun deleteOrphans()
}

@Dao
interface LibraryDao {
    @Upsert
    suspend fun upsert(entry: LibraryEntity)

    @Query("DELETE FROM library WHERE itemKey = :itemKey AND kind = :kind")
    suspend fun delete(itemKey: String, kind: String)

    @Query("SELECT itemKey FROM library WHERE kind = :kind")
    fun keys(kind: String): Flow<List<String>>

    @Query("SELECT itemKey FROM library WHERE kind = :kind")
    suspend fun keysOnce(kind: String): List<String>

    @Query("SELECT COUNT(*) FROM library WHERE kind = :kind")
    fun count(kind: String): Flow<Int>

    @Query(
        "SELECT i.json FROM library l INNER JOIN items i ON i.itemKey = l.itemKey " +
            "WHERE l.kind = :kind ORDER BY l.at DESC LIMIT :limit",
    )
    fun itemsJson(kind: String, limit: Int): Flow<List<String>>

    @Query(
        "SELECT l.itemKey AS itemKey, l.at AS at FROM library l WHERE l.kind = :kind ORDER BY l.at DESC LIMIT :limit",
    )
    fun times(kind: String, limit: Int): Flow<List<KeyAt>>

    @Query("DELETE FROM library WHERE kind = 'HISTORY' AND at < :before")
    suspend fun purgeHistory(before: Long)

    @Query("DELETE FROM library WHERE kind = 'HISTORY'")
    suspend fun clearHistory()

    // Collections are library lists too, with kind "C:<name>".
    @Query(
        "SELECT kind AS kind, COUNT(*) AS count, MAX(at) AS at FROM library WHERE kind LIKE 'C:%' " +
            "GROUP BY kind ORDER BY MAX(at) DESC",
    )
    fun collections(): Flow<List<KindCount>>

    @Query(
        "SELECT i.json FROM library l INNER JOIN items i ON i.itemKey = l.itemKey WHERE l.kind = :kind ORDER BY l.at DESC LIMIT 1",
    )
    suspend fun latestJson(kind: String): String?

    @Query("SELECT kind FROM library WHERE itemKey = :itemKey AND kind LIKE 'C:%'")
    suspend fun collectionsOf(itemKey: String): List<String>

    @Query("DELETE FROM library WHERE itemKey = :itemKey AND kind LIKE 'C:%'")
    suspend fun removeFromAllCollections(itemKey: String)

    /** Everything but history, with each post's stored copy: for the backup file. */
    @Query(
        "SELECT l.itemKey AS itemKey, l.kind AS kind, l.at AS at, i.json AS json FROM library l " +
            "INNER JOIN items i ON i.itemKey = l.itemKey WHERE l.kind != 'HISTORY'",
    )
    suspend fun exportRows(): List<LibraryRow>

    @Query("UPDATE OR REPLACE library SET kind = :to WHERE kind = :from")
    suspend fun renameKind(from: String, to: String)

    @Query("DELETE FROM library WHERE kind = :kind")
    suspend fun deleteKind(kind: String)
}

@Dao
interface SignalDao {
    @Insert
    suspend fun insert(signal: SignalEntity)

    @Query("DELETE FROM signals WHERE at < :before")
    suspend fun purge(before: Long)

    @Query("DELETE FROM signals")
    suspend fun clear()
}

@Dao
interface AffinityDao {
    @Query("SELECT * FROM tag_affinity WHERE tag IN (:tags)")
    suspend fun tags(tags: List<String>): List<TagAffinityEntity>

    @Query("SELECT * FROM tag_affinity")
    suspend fun allTags(): List<TagAffinityEntity>

    @Upsert
    suspend fun upsertTags(rows: List<TagAffinityEntity>)

    @Query("SELECT * FROM creator_affinity WHERE creator = :creator")
    suspend fun creator(creator: String): CreatorAffinityEntity?

    @Query("SELECT * FROM creator_affinity")
    suspend fun allCreators(): List<CreatorAffinityEntity>

    @Upsert
    suspend fun upsertCreator(row: CreatorAffinityEntity)

    @Query("DELETE FROM tag_affinity")
    suspend fun clearTags()

    @Query("DELETE FROM creator_affinity")
    suspend fun clearCreators()

    @Query("DELETE FROM tag_affinity WHERE tag IN (:tags)")
    suspend fun deleteTags(tags: List<String>)

    @Query("DELETE FROM creator_affinity WHERE creator IN (:creators)")
    suspend fun deleteCreators(creators: List<String>)
}

@Dao
interface FollowDao {
    @Query("SELECT * FROM follows ORDER BY at DESC")
    fun all(): Flow<List<FollowEntity>>

    @Query("SELECT * FROM follows")
    suspend fun allOnce(): List<FollowEntity>

    @Upsert
    suspend fun upsert(row: FollowEntity)

    @Query("DELETE FROM follows WHERE kind = :kind AND value = :value")
    suspend fun delete(kind: String, value: String)
}

@Dao
interface BlockDao {
    @Query("SELECT * FROM blocks ORDER BY at DESC")
    fun all(): Flow<List<BlockEntity>>

    @Query("SELECT * FROM blocks")
    suspend fun allOnce(): List<BlockEntity>

    @Upsert
    suspend fun upsert(row: BlockEntity)

    @Query("DELETE FROM blocks WHERE kind = :kind AND value = :value")
    suspend fun delete(kind: String, value: String)

    @Query("DELETE FROM blocks WHERE kind = :kind")
    suspend fun deleteKind(kind: String)
}

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials WHERE sourceId = :sourceId")
    suspend fun get(sourceId: String): CredentialEntity?

    @Upsert
    suspend fun upsert(row: CredentialEntity)

    @Query("DELETE FROM credentials WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String)
}

/** When a post went into a library list. */
data class KeyAt(val itemKey: String, val at: Long)

/** A library entry with its post, for backups. */
data class LibraryRow(val itemKey: String, val kind: String, val at: Long, val json: String)

/** A library list (a collection) with how many posts it has and when one was last added. */
data class KindCount(val kind: String, val count: Int, val at: Long)
