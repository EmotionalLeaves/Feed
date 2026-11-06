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

    @Query(
        "SELECT i.json FROM library l INNER JOIN items i ON i.itemKey = l.itemKey " +
            "WHERE l.kind = :kind ORDER BY l.at DESC LIMIT :limit",
    )
    fun itemsJson(kind: String, limit: Int): Flow<List<String>>

    @Query("DELETE FROM library WHERE kind = 'HISTORY' AND at < :before")
    suspend fun purgeHistory(before: Long)

    @Query("DELETE FROM library WHERE kind = 'HISTORY'")
    suspend fun clearHistory()
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
